package cop.module.impl.dungeon

import cop.CopMod
import cop.api.colour.Colour
import cop.api.events.GuiEvent
import cop.api.events.TickEvent
import cop.api.input.CatKeys
import cop.api.skyblock.croesus.ChestInfo
import cop.api.skyblock.croesus.ChestParseResult
import cop.api.skyblock.croesus.CroesusParser
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.PriceClient
import cop.utils.skyblock.player.ContainerUtils
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component

/**
 * Auto Croesus — Phase 3a (manual-trigger auto-claim).
 *
 *  - Highlights unclaimed runs on the top-level Croesus screen (Phase 2).
 *  - On a run sub-screen, draws an overlay listing each chest tier with cost,
 *    total value, and profit (Phase 2).
 *  - **Phase 3a:** while in a run sub-screen, pressing the [claimKey] sends
 *    a two-click sequence that opens the highest-profit chest's buy-confirm
 *    and clicks the "Open Reward Chest" button — the loot drops straight
 *    into the player's inventory. Refuses if profit < [minProfit]. Aborts
 *    if anything unexpected happens (different screen opens, timeout, etc).
 *
 * Still no NPC walking, no multi-chest, no multi-run, no kismet/reroll, no
 * loot log — saved for Phase 3b/3c/4/5. Master kill switch [autoClaim] is
 * default-OFF so the key is inert until the user explicitly opts in.
 */
object AutoCroesus : Module(
    "Auto Croesus",
    desc = "Highlights unclaimed runs, shows per-chest profit, and (with the master switch on) lets you key-bind a one-shot auto-claim of the most profitable chest in a run."
) {
    private val unclaimedColour by colourPicker(
        "Unclaimed highlight", Colour.RGB(0, 255, 0, 0.7f), allowAlpha = true,
        desc = "Colour drawn around runs with unopened chests."
    )
    private val borderWidth by slider(
        "Border width", 2, 1, 4, 1,
        desc = "Thickness of the unclaimed-run outline.", unit = "px"
    )
    private val showOverlay by switch(
        "Show profit overlay", true,
        desc = "Draw the per-chest cost/value/profit summary in the run sub-screen."
    )
    private val highlightBest by switch(
        "Highlight best chest", true,
        desc = "Prepend a ★ in the overlay and draw an outline around the highest-profit chest icon in the GUI."
    )
    private val bestColour by colourPicker(
        "Best-chest highlight", Colour.RGB(255, 215, 0, 0.85f), allowAlpha = true,
        desc = "Outline colour drawn around the best-profit chest icon."
    )
    private val refreshTicks by slider(
        "Refresh rate", 5, 1, 40, 1,
        desc = "Re-parse the open chest GUI every N ticks (lower = snappier, higher = cheaper).", unit = "t"
    )
    private val debugDumpKey by keybind(
        "Debug dump key",
        desc = "While in any chest GUI: dump title + every slot's name + lore to latest.log " +
            "(for diagnosing parser failures — chat isn't available in container screens, so this lives here)."
    )

    // -- Phase 3a (auto-claim driver) ----------------------------------------

    private val autoClaim by switch(
        "Auto claim (master)", false,
        desc = "Master kill-switch for the auto-claim driver. Default OFF; turn ON to arm " +
            "the claim keybind. With this off, the claim key is inert."
    )
    private val claimKey by keybind(
        "Claim best chest",
        desc = "Press while in a run sub-screen to auto-claim the highest-profit chest. " +
            "Sends two clicks (chest tier → \"Open Reward Chest\"). Refuses if best profit " +
            "is below the threshold or if Auto claim (master) is off."
    )
    private val minProfit by slider(
        "Min profit", 100_000, 0, 5_000_000, 50_000,
        desc = "Refuse to auto-claim if the best chest's profit is below this. Set to 0 " +
            "to always claim regardless. Units are raw coins (not formatted)."
    )
    private val claimTimeoutTicks by slider(
        "Claim timeout", 60, 20, 200, 10,
        desc = "Abort the in-flight claim if the buy-confirm screen doesn't open within N ticks (20 = 1 second).",
        unit = "t"
    )
    private val chainClaim by switch(
        "Chain claim (this run)", false,
        desc = "After a successful buy, automatically return to the run sub-screen, re-parse, " +
            "and claim the next chest above the threshold. Stops cleanly when no chest meets " +
            "the threshold. Per-run only — does not navigate between runs (Phase 3c)."
    )

    /** Cached parser output for the currently-open run sub-screen. */
    @Volatile private var lastChests: List<ChestParseResult> = emptyList()
    /** Sticky containerId snapshot — wipes cache when the user opens a different container. */
    private var cachedContainerId: Int = -1
    private var ticksSinceParse = 0

    /** The claim driver's state machine.
     *   - IDLE             — nothing in flight; keybind is the only way in.
     *   - AWAIT_CONFIRM    — sent chest-tier click, waiting for Hypixel to open
     *                        the buy-confirm screen so we can press "Open Reward Chest".
     *   - AWAIT_NEXT_PARSE — chain mode only. Sent the buy click; waiting for
     *                        the run sub-screen to reappear with freshly-parsed
     *                        contents so we can pick the next-best chest. */
    private enum class ClaimState { IDLE, AWAIT_CONFIRM, AWAIT_NEXT_PARSE }
    @Volatile private var claimState = ClaimState.IDLE
    /** Monotonic tick counter, only used to schedule the claim timeout. */
    private var monotonicTick = 0L
    private var claimDeadlineTick = 0L
    /** Tier display label captured at click time, used in the success chat line. */
    private var pendingTier = ""
    /** Counter for the per-cycle chat summary at the end of a chain. */
    private var chainClaimsThisCycle = 0

    init {
        // Wipe parser cache whenever a GUI opens/closes — stale data from the
        // previous run would otherwise leak into the next overlay.
        on<GuiEvent.Close> {
            reset()
            // Only abort on close while waiting for the buy-confirm — that's
            // unambiguously a failed transition. AWAIT_NEXT_PARSE is fine to
            // ride through close+open transitions (server may briefly close
            // the run sub-screen before reopening it); the deadline check
            // catches genuine failures.
            if (claimState == ClaimState.AWAIT_CONFIRM) {
                modMessage("&7AutoCroesus: GUI closed mid-claim — state reset.")
                claimState = ClaimState.IDLE
                chainClaimsThisCycle = 0
            }
        }
        on<GuiEvent.Open> {
            reset()
            // Step 2 — buy-confirm just opened. Click "Open Reward Chest";
            // loot drops straight into inventory, screen closes server-side.
            if (claimState == ClaimState.AWAIT_CONFIRM) {
                if (CroesusParser.inBuyConfirmMenu(screen)) {
                    if (ContainerUtils.click(CroesusParser.BUY_CONFIRM_SLOT)) {
                        chainClaimsThisCycle++
                        modMessage("&a✓ AutoCroesus: bought &r$pendingTier&a chest.")
                        if (chainClaim) {
                            // Hand off to AWAIT_NEXT_PARSE; the run sub-screen
                            // will reopen and the parse loop will pick up the
                            // next-best chest. Give a generous timeout — the
                            // close→open cycle can take longer than a single
                            // chest-click roundtrip.
                            claimState = ClaimState.AWAIT_NEXT_PARSE
                            claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
                        } else {
                            claimState = ClaimState.IDLE
                            chainClaimsThisCycle = 0
                        }
                    } else {
                        modMessage("&cAutoCroesus: buy-confirm opened but click failed " +
                            "(no container id).")
                        claimState = ClaimState.IDLE
                        chainClaimsThisCycle = 0
                    }
                } else {
                    // Some other screen opened instead — abort defensively.
                    val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "?"
                    modMessage("&cAutoCroesus: aborted — expected buy-confirm, got \"$title\".")
                    claimState = ClaimState.IDLE
                    chainClaimsThisCycle = 0
                }
            }
        }

        on<TickEvent.Start> {
            // Monotonic tick — drives the claim timeout. Wraps after ~14
            // billion years so overflow is not a concern.
            monotonicTick++
            if (claimState != ClaimState.IDLE && monotonicTick >= claimDeadlineTick) {
                modMessage("&cAutoCroesus: timeout (state=$claimState) — aborting.")
                claimState = ClaimState.IDLE
                chainClaimsThisCycle = 0
            }

            val screen = mc.screen as? AbstractContainerScreen<*>
            if (screen == null) { reset(); return@on }

            // Kick the bulk refresh in the background — first time the user
            // opens Croesus this session, prices start streaming in. Subsequent
            // opens are instant (cached for 30 min).
            PriceClient.refreshIfStale()

            if (CroesusParser.inRunMenu(screen)) {
                val cid = screen.menu.containerId
                if (cid != cachedContainerId) {
                    lastChests = emptyList()
                    cachedContainerId = cid
                    // Mark for immediate parse on the next tick. Use refreshTicks
                    // (not Int.MAX_VALUE — the ++ below would overflow to MIN_VALUE
                    // and the threshold check would then never fire).
                    ticksSinceParse = refreshTicks
                }
                ticksSinceParse++
                // Also parse whenever we have no data yet, so we never sit in a
                // Croesus run sub-screen with an empty overlay due to a counter race.
                if (lastChests.isEmpty() || ticksSinceParse >= refreshTicks) {
                    lastChests = CroesusParser.parseChests(screen.menu)
                    ticksSinceParse = 0
                }

                // Chain mode: we just bought a chest and are waiting for fresh
                // parser data on the reopened run sub-screen. As soon as we
                // have a non-empty parse result, kick off the next claim.
                if (claimState == ClaimState.AWAIT_NEXT_PARSE && lastChests.isNotEmpty()) {
                    claimState = ClaimState.IDLE  // tryStartClaim requires IDLE
                    tryStartClaim(screen, fromChain = true)
                }
            } else if (lastChests.isNotEmpty()) {
                lastChests = emptyList()
            }
        }

        on<GuiEvent.Slot.Draw> {
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            when {
                // Top-level Croesus screen: outline every unopened run.
                CroesusParser.inCroesusMenu(screen) -> {
                    val stack = slot.item.takeUnless { it.isEmpty } ?: return@on
                    val lore = stack.get(DataComponents.LORE) ?: return@on
                    // Plain-text contains — works regardless of which colour codes
                    // Hypixel wraps the line in (see CroesusParser.LORE_UNCLAIMED_MARKER).
                    val hasMarker = lore.lines.any { CroesusParser.LORE_UNCLAIMED_MARKER in it.string }
                    if (hasMarker) drawSlotOutline(ctx, slot.x, slot.y, unclaimedColour.rgb, borderWidth)
                }
                // Run sub-screen: outline the best-profit chest icon.
                highlightBest && CroesusParser.inRunMenu(screen) -> {
                    val bestSlot = lastChests
                        .filterIsInstance<ChestParseResult.Success>()
                        .maxByOrNull { it.chest.profit }
                        ?.chest?.slot ?: return@on
                    if (slot.index == bestSlot) {
                        drawSlotOutline(ctx, slot.x, slot.y, bestColour.rgb, borderWidth)
                    }
                }
            }
        }

        on<GuiEvent.Draw.Post> {
            if (!showOverlay) return@on
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!CroesusParser.inRunMenu(screen)) return@on
            if (lastChests.isEmpty()) return@on
            renderProfitOverlay(ctx)
        }

        on<GuiEvent.Key.Press> {
            if (key == CatKeys.KEY_NONE) return@on
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on

            // Debug dump key — fires in any container screen so we can dump
            // the buy-confirm sub-menu and the reward chest, not just the two
            // parser-recognised ones.
            if (key == debugDumpKey.key) {
                dumpScreen(screen)
                cancel()
                return@on
            }

            // Claim key — only meaningful in a run sub-screen, and only when
            // the master switch is on. Everything else is a friendly diagnostic.
            if (key == claimKey.key) {
                tryStartClaim(screen)
                cancel()
                return@on
            }
        }
    }

    /** Validates preconditions, picks the best chest, sends the first click,
     *  and transitions to AWAIT_CONFIRM.
     *
     *  When [fromChain] is true the call comes from the chain driver (not a
     *  keybind press), so:
     *    - precondition failures don't shout in chat (the driver tried best
     *      effort, no user attention needed),
     *    - "no chest above threshold" prints a friendly summary that the
     *      chain has finished, including how many chests it claimed. */
    private fun tryStartClaim(screen: AbstractContainerScreen<*>, fromChain: Boolean = false) {
        if (!autoClaim) {
            if (!fromChain) modMessage("&cAutoCroesus: turn on the &fAuto claim (master)&c switch first.")
            return
        }
        if (!CroesusParser.inRunMenu(screen)) {
            if (!fromChain) modMessage("&cAutoCroesus: claim key only works inside a run sub-screen " +
                "(\"Catacombs - Floor X\" / \"Master Catacombs - Floor X\").")
            return
        }
        if (claimState != ClaimState.IDLE) {
            if (!fromChain) modMessage("&cAutoCroesus: already claiming (state=$claimState) — wait or close GUI.")
            return
        }
        val best: ChestInfo? = lastChests
            .filterIsInstance<ChestParseResult.Success>()
            .maxByOrNull { it.chest.profit }
            ?.chest
        if (best == null) {
            if (!fromChain) modMessage("&cAutoCroesus: no chests parsed yet — wait a moment for the overlay.")
            return
        }
        if (best.profit < minProfit) {
            if (fromChain) {
                modMessage(
                    "&aAutoCroesus chain complete — bought $chainClaimsThisCycle chest" +
                        (if (chainClaimsThisCycle == 1) "" else "s") +
                        "; next-best (&r${best.tierColourCode}${best.tierName}&a) profit " +
                        "&f${PriceClient.formatPrice(best.profit)}&a is below threshold."
                )
                chainClaimsThisCycle = 0
            } else {
                modMessage(
                    "&cAutoCroesus: best chest profit &f${PriceClient.formatPrice(best.profit)}&c " +
                        "below threshold &f${PriceClient.formatPrice(minProfit.toDouble())}&c — refusing."
                )
            }
            return
        }
        if (!ContainerUtils.click(best.slot)) {
            modMessage("&cAutoCroesus: click failed (no container id tracked).")
            return
        }
        // Manual start clears the per-cycle counter so chained-then-aborted-
        // then-manual flows don't add up across unrelated sessions.
        if (!fromChain) chainClaimsThisCycle = 0
        claimState = ClaimState.AWAIT_CONFIRM
        claimDeadlineTick = monotonicTick + claimTimeoutTicks.toLong()
        pendingTier = "${best.tierColourCode}${best.tierName}"
        modMessage(
            "&aAutoCroesus: claiming ★ &r$pendingTier&a chest " +
                "&7(profit ${PriceClient.formatPrice(best.profit)})."
        )
    }

    /** Logs the current GUI's title + every non-empty slot (chest area only,
     *  player inv excluded) with name + lore so the parser can be debugged
     *  against real Hypixel data. Output goes to the game log because chat
     *  isn't usable inside container screens. */
    private fun dumpScreen(screen: AbstractContainerScreen<*>) {
        val title = screen.title.string
        val menu = screen.menu
        // Whole chest area (everything except the player inventory tail).
        val end = (menu.slots.size - 36).coerceAtLeast(0)
        val log = CopMod.logger
        log.info("[cop] CroesusDump — title=\"$title\", slots=${menu.slots.size}, chestArea=$end")
        for (i in 0 until end) {
            val stack = menu.slots.getOrNull(i)?.item ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string
            val lore = stack.get(DataComponents.LORE)?.lines?.map { it.string } ?: emptyList()
            log.info("[cop]   slot=$i name=\"$name\"")
            for ((j, line) in lore.withIndex()) log.info("[cop]     lore[$j]=\"$line\"")
        }
        // Confirmation message — visible once the player closes the GUI.
        modMessage("&aDumped \"$title\" to latest.log ($end chest-area slots scanned).")
    }

    private fun reset() {
        lastChests = emptyList()
        cachedContainerId = -1
        ticksSinceParse = 0
    }

    private fun drawSlotOutline(ctx: GuiGraphics, x: Int, y: Int, colour: Int, bw: Int) {
        ctx.fill(x - bw, y - bw, x + 16 + bw, y, colour)            // top
        ctx.fill(x - bw, y + 16, x + 16 + bw, y + 16 + bw, colour)  // bottom
        ctx.fill(x - bw, y, x, y + 16, colour)                       // left
        ctx.fill(x + 16, y, x + 16 + bw, y + 16, colour)             // right
    }

    /** Top-left overlay: one block per chest with cost/value/profit.
     *  Highest-profit chest gets a ★ prefix so the player can see at a glance
     *  which one to open. Computed once across all successful parses; ties
     *  resolve to the first match (deterministic across frames). */
    private fun renderProfitOverlay(ctx: GuiGraphics) {
        val font = mc.font
        val x = 4
        var y = 4
        val bestSlot = if (highlightBest) {
            lastChests
                .filterIsInstance<ChestParseResult.Success>()
                .maxByOrNull { it.chest.profit }
                ?.chest?.slot
        } else null
        val lines = buildList<Component> {
            for (result in lastChests) {
                when (result) {
                    is ChestParseResult.Success -> {
                        val c = result.chest
                        val profitColour = if (c.profit >= 0) "§a+" else "§c"
                        val marker = if (c.slot == bestSlot) "§e§l★ " else "  "
                        add(Component.literal(
                            "$marker${c.tierColourCode}${c.tierName} Chest §7(${PriceClient.formatPrice(c.cost)}) " +
                                "$profitColour${PriceClient.formatPrice(c.profit)}"
                        ))
                        for (item in c.items.take(6)) {
                            val v = item.unitValue * item.qty
                            val vColour = if (v > 0) "§a" else "§7"
                            // Strip the legacy "§5§o" prefix Hypixel slaps on every
                            // lore line for italic formatting — looks bad in overlay.
                            val name = item.displayName.removePrefix("§5§o")
                            add(Component.literal("    $name $vColour${PriceClient.formatPrice(v)}"))
                        }
                        if (c.items.size > 6) add(Component.literal("    §7… +${c.items.size - 6} more"))
                        add(Component.literal(""))  // blank separator
                    }
                    is ChestParseResult.Failure -> {
                        add(Component.literal("  §c${result.tierName} Chest §7(${result.reason})"))
                        add(Component.literal(""))
                    }
                }
            }
        }
        if (lines.isEmpty()) return
        val maxWidth = lines.maxOf { font.width(it) }
        val totalHeight = lines.size * (font.lineHeight + 1) + 6
        ctx.fill(x - 2, y - 2, x + maxWidth + 4, y + totalHeight, 0xC0000000.toInt())
        for (line in lines) {
            ctx.drawString(font, line, x, y, 0xFFFFFFFF.toInt(), false)
            y += font.lineHeight + 1
        }
    }
}
