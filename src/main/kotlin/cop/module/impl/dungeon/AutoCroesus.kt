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
import cop.utils.EntityUtils
import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.PriceClient
import cop.utils.skyblock.player.ContainerUtils
import cop.utils.skyblock.player.interact.AuraAction
import cop.utils.skyblock.player.interact.AuraManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity

/**
 * Auto Croesus — Phase 3c (multi-run auto-claim).
 *
 *  - Highlights unclaimed runs on the top-level Croesus screen (Phase 2).
 *  - On a run sub-screen, draws an overlay listing each chest tier with cost,
 *    total value, and profit (Phase 2).
 *  - **Phase 3a:** keybind in a run sub-screen → claims the single best chest.
 *  - **Phase 3b:** [chainClaim] — after a successful buy, automatically chain
 *    to the next-best chest in the same run (requires Dungeon Chest Keys; one
 *    key consumed per additional chest beyond the first).
 *  - **Phase 3c:** [multiRun] — keybind in the Croesus list → walks through
 *    every unclaimed run on the current page, claims the best chest in each,
 *    backs out, repeats. Pair with [chainClaim] for full hands-free claim of
 *    every chest above threshold across every run (key-using players).
 *
 * No NPC walking, no pagination, no kismet/reroll, no loot log — saved for
 * 3d / 4 / 5. Master kill switch [autoClaim] defaults OFF so the keybind is
 * inert until the user explicitly opts in.
 */
object AutoCroesus : Module(
    "Auto Croesus",
    desc = "Highlights unclaimed runs, shows per-chest profit, and (with the master switch on) auto-claims chests — one-shot, in-run chain, or full multi-run, depending on which switches you enable."
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
            "the threshold. Per-run only — does not navigate between runs (that's Multi-run claim)."
    )
    private val multiRun by switch(
        "Multi-run claim", false,
        desc = "Full multi-run automation. Press the claim key while in the Croesus list to " +
            "walk through every unclaimed run on the current page: open it, claim the best chest, " +
            "back out, repeat. Stops when no unclaimed runs remain visible. Pair with Chain claim " +
            "to also claim multiple chests per run (requires Dungeon Chest Keys in inventory)."
    )

    /** Cached parser output for the currently-open run sub-screen. */
    @Volatile private var lastChests: List<ChestParseResult> = emptyList()
    /** Sticky containerId snapshot — wipes cache when the user opens a different container. */
    private var cachedContainerId: Int = -1
    private var ticksSinceParse = 0

    /** The claim driver's state machine.
     *   - IDLE                — nothing in flight; keybind is the only way in.
     *   - AWAIT_CONFIRM       — sent chest-tier click, waiting for Hypixel to open
     *                           the buy-confirm screen so we can press "Open Reward Chest".
     *   - AWAIT_NEXT_PARSE    — Chain/multi-run. Sent the buy click; waiting for
     *                           the run sub-screen to repopulate so we can pick the
     *                           next-best chest. Also entered after AWAIT_RUN_SCREEN
     *                           opens a fresh run from the Croesus list.
     *   - AWAIT_AFTER_BUY     — Multi-run only. Sent the buy click, waiting for
     *                           the next screen (could be the run sub-screen OR
     *                           directly the Croesus list, depending on Hypixel).
     *   - AWAIT_RUN_SCREEN    — Multi-run. Clicked a run icon in the Croesus list;
     *                           waiting for the run sub-screen to open.
     *   - AWAIT_CROESUS_LIST  — Multi-run. Sent the "Go Back" click from a run
     *                           sub-screen; waiting for the Croesus list to reopen. */
    private enum class ClaimState {
        IDLE,
        AWAIT_CONFIRM,
        AWAIT_NEXT_PARSE,
        AWAIT_AFTER_BUY,
        AWAIT_RUN_SCREEN,
        AWAIT_CROESUS_LIST,
    }
    @Volatile private var claimState = ClaimState.IDLE
    /** Monotonic tick counter, only used to schedule the claim timeout. */
    private var monotonicTick = 0L
    private var claimDeadlineTick = 0L
    /** Tier display label captured at click time, used in the success chat line. */
    private var pendingTier = ""
    /** Counter for the per-cycle chat summary at the end of a chain. */
    private var chainClaimsThisCycle = 0
    /** Total chests bought during the current multi-run cycle (across all runs). */
    private var multiRunChestsThisCycle = 0
    /** Total runs visited during the current multi-run cycle. */
    private var multiRunRunsThisCycle = 0
    /** First tick at which mc.screen was observed null while in AWAIT_AFTER_BUY.
     *  After buying, Hypixel fully closes the menu — no replacement screen
     *  opens — so we need a tick-based detector instead of waiting on Open.
     *  0 means "not currently tracking". */
    private var noScreenSinceTick = 0L

    init {
        // Wipe parser cache whenever a GUI opens/closes — stale data from the
        // previous run would otherwise leak into the next overlay.
        on<GuiEvent.Close> {
            reset()
            // Only abort on close while waiting for the buy-confirm — that's
            // unambiguously a failed transition. All other transient states
            // (AWAIT_NEXT_PARSE, AWAIT_AFTER_BUY, AWAIT_RUN_SCREEN,
            // AWAIT_CROESUS_LIST) routinely ride through close+open pairs as
            // Hypixel swaps screens; the deadline timer catches real failures.
            if (claimState == ClaimState.AWAIT_CONFIRM) {
                modMessage("&7AutoCroesus: GUI closed mid-claim — state reset.")
                resetCycle()
            }
        }
        on<GuiEvent.Open> {
            reset()
            // Driver dispatch: each non-IDLE state has a specific expected
            // next screen. Anything else is an abort.
            when (claimState) {
                ClaimState.AWAIT_CONFIRM       -> handleConfirmOpen(screen)
                ClaimState.AWAIT_AFTER_BUY     -> handleAfterBuyOpen(screen)
                ClaimState.AWAIT_RUN_SCREEN    -> handleRunScreenOpen(screen)
                ClaimState.AWAIT_CROESUS_LIST  -> handleCroesusListOpen(screen)
                ClaimState.AWAIT_NEXT_PARSE,
                ClaimState.IDLE -> {
                    // AWAIT_NEXT_PARSE waits passively for the TickEvent parser
                    // loop to pick up the next chest. IDLE has nothing to do.
                }
            }
        }

        on<TickEvent.Start> {
            // Monotonic tick — drives the claim timeout. Wraps after ~14
            // billion years so overflow is not a concern.
            monotonicTick++
            if (claimState != ClaimState.IDLE && monotonicTick >= claimDeadlineTick) {
                modMessage("&cAutoCroesus: timeout (state=$claimState) — aborting.")
                resetCycle()
            }

            // After a successful buy in multi-run mode, Hypixel doesn't open
            // any replacement screen — it just closes everything. Detect this
            // by watching mc.screen stay null for a few ticks, then trigger
            // the Croesus NPC re-interaction.
            if (claimState == ClaimState.AWAIT_AFTER_BUY && multiRun) {
                if (mc.screen == null) {
                    if (noScreenSinceTick == 0L) noScreenSinceTick = monotonicTick
                    else if (monotonicTick - noScreenSinceTick >= 10L) {
                        // ~500ms with no screen = menu fully closed.
                        noScreenSinceTick = 0L
                        tryReopenCroesus()
                    }
                } else {
                    noScreenSinceTick = 0L
                }
            } else {
                noScreenSinceTick = 0L
            }

            val screen = mc.screen as? AbstractContainerScreen<*>
            if (screen == null) { reset(); return@on }

            // Kick the bulk refresh in the background — first time the user
            // opens Croesus this session, prices start streaming in. Subsequent
            // opens are instant (cached for 30 min).
            PriceClient.refreshIfStale()

            // Multi-run polling: in AWAIT_CROESUS_LIST, retry the unclaimed
            // scan each tick until either (a) we find an unclaimed run to
            // click, or (b) slot data has fully loaded but there are none
            // left. Slot 4 (the Croesus info icon) being non-empty is the
            // signal that Hypixel has finished sending slot updates.
            if (claimState == ClaimState.AWAIT_CROESUS_LIST &&
                CroesusParser.inCroesusMenu(screen)) {
                val unclaimed = CroesusParser.findUnclaimedRunSlots(screen.menu)
                if (unclaimed.isNotEmpty()) {
                    clickUnclaimedRun(unclaimed.first())
                } else {
                    val populated = screen.menu.slots.getOrNull(4)?.item?.isEmpty == false
                    if (populated) completeMultiRun()
                    // else: keep polling — slots still loading
                }
            }

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

            // Claim key — dispatches based on which Croesus screen we're in:
            //   - Run sub-screen → single/chain claim on the current run.
            //   - Croesus list   → multi-run cycle (requires Multi-run switch).
            //   - Anything else  → friendly nudge in chat.
            if (key == claimKey.key) {
                if (!autoClaim) {
                    modMessage("&cAutoCroesus: turn on the &fAuto claim (master)&c switch first.")
                } else if (claimState != ClaimState.IDLE) {
                    modMessage("&cAutoCroesus: already claiming (state=$claimState) — wait or close GUI.")
                } else if (CroesusParser.inRunMenu(screen)) {
                    tryStartClaim(screen)
                } else if (CroesusParser.inCroesusMenu(screen)) {
                    tryStartMultiRun(screen)
                } else {
                    modMessage("&cAutoCroesus: claim key works only in the Croesus list or a run sub-screen.")
                }
                cancel()
                return@on
            }
        }
    }

    // -- GuiEvent.Open dispatch handlers ---------------------------------------

    /** Step 2 of a claim cycle: the buy-confirm just opened. Click "Open
     *  Reward Chest"; loot drops straight into inventory, screen closes
     *  server-side. Then pick the next state based on which automation
     *  modes the user has enabled. */
    private fun handleConfirmOpen(screen: net.minecraft.client.gui.screens.Screen) {
        if (!CroesusParser.inBuyConfirmMenu(screen)) {
            val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "?"
            modMessage("&cAutoCroesus: aborted — expected buy-confirm, got \"$title\".")
            resetCycle()
            return
        }
        if (!ContainerUtils.click(CroesusParser.BUY_CONFIRM_SLOT)) {
            modMessage("&cAutoCroesus: buy-confirm opened but click failed (no container id).")
            resetCycle()
            return
        }
        chainClaimsThisCycle++
        multiRunChestsThisCycle++
        modMessage("&a✓ AutoCroesus: bought &r$pendingTier&a chest.")
        // Pick the next state. Order matters: multi-run is the broadest mode
        // and prefers to land in AWAIT_AFTER_BUY so the next-screen handler
        // can decide whether to chain in this run or move to the next.
        claimState = when {
            multiRun -> ClaimState.AWAIT_AFTER_BUY
            chainClaim -> ClaimState.AWAIT_NEXT_PARSE
            else -> {
                chainClaimsThisCycle = 0
                ClaimState.IDLE
            }
        }
        if (claimState != ClaimState.IDLE) {
            // Generous timeout — close → open cycle can take longer than a
            // single chest-click roundtrip.
            claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
        }
    }

    /** Multi-run, post-buy. The buy-confirm has closed; Hypixel may:
     *    - drop us back into the run sub-screen,
     *    - go directly to the Croesus list,
     *    - close everything entirely (most common — handled by the TickEvent
     *      "no screen for 10 ticks" detector, which calls [tryReopenCroesus]).
     *
     *  Non-container screens (chat, pause menu, etc.) are ignored — the
     *  tick detector handles cleanup. */
    private fun handleAfterBuyOpen(screen: net.minecraft.client.gui.screens.Screen) {
        val containerScreen = screen as? AbstractContainerScreen<*> ?: return
        when {
            CroesusParser.inRunMenu(containerScreen) -> {
                if (chainClaim) {
                    // Try to chain in the same run first (Dungeon Chest Keys
                    // case). If the next-best chest is below threshold the
                    // chain handler will fall through to "click Go Back".
                    claimState = ClaimState.AWAIT_NEXT_PARSE
                    claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
                } else {
                    // No chain: leave this run immediately.
                    clickGoBackToList()
                }
            }
            CroesusParser.inCroesusMenu(containerScreen) -> {
                // Server skipped the close phase; route through the same
                // polling path so we wait for slots to populate.
                claimState = ClaimState.AWAIT_CROESUS_LIST
                claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
            }
            else -> {
                modMessage("&cAutoCroesus: aborted after buy — unexpected container \"" +
                    "${containerScreen.title.string}\".")
                resetCycle()
            }
        }
    }

    /** Multi-run, just clicked a run icon. We expect a run sub-screen; once
     *  it opens we hand off to AWAIT_NEXT_PARSE so the TickEvent parser loop
     *  kicks off the first claim as soon as fresh data is available. */
    private fun handleRunScreenOpen(screen: net.minecraft.client.gui.screens.Screen) {
        if (CroesusParser.inRunMenu(screen)) {
            claimState = ClaimState.AWAIT_NEXT_PARSE
            claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
        } else {
            val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "?"
            modMessage("&cAutoCroesus: aborted — expected run sub-screen, got \"$title\".")
            resetCycle()
        }
    }

    /** Multi-run, just clicked "Go Back" or re-interacted with the NPC. We
     *  expect the Croesus list; once it opens, the TickEvent polling loop
     *  picks the next unclaimed run as soon as the slot data finishes loading
     *  (Hypixel sends ClientboundContainerSetSlotPacket asynchronously after
     *  the open packet, so scanning immediately would find empty slots and
     *  wrongly declare the cycle complete). */
    private fun handleCroesusListOpen(screen: net.minecraft.client.gui.screens.Screen) {
        if (!CroesusParser.inCroesusMenu(screen)) {
            val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "?"
            modMessage("&cAutoCroesus: aborted — expected Croesus list, got \"$title\".")
            resetCycle()
            return
        }
        // Refresh the deadline — the actual click happens in TickEvent once
        // slot 4 (the Croesus info icon) becomes non-empty (= slots loaded).
        claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
    }

    /** Send the "Go Back" click from the current run sub-screen → Croesus list. */
    private fun clickGoBackToList() {
        if (!ContainerUtils.click(CroesusParser.RUN_BACK_SLOT)) {
            modMessage("&cAutoCroesus: failed to click Go Back (no container id).")
            resetCycle()
            return
        }
        claimState = ClaimState.AWAIT_CROESUS_LIST
        claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
    }

    /** Fully reset the driver state plus per-cycle counters. */
    private fun resetCycle() {
        claimState = ClaimState.IDLE
        chainClaimsThisCycle = 0
        multiRunChestsThisCycle = 0
        multiRunRunsThisCycle = 0
        noScreenSinceTick = 0L
    }

    /** Try to reopen the Croesus menu by interacting with the nearby NPC
     *  entity. Called from the TickEvent detector when the menu has been
     *  fully closed for ~500ms post-buy. */
    private fun tryReopenCroesus() {
        val entity = findCroesusEntity()
        if (entity == null) {
            modMessage(
                "&aMulti-run complete — bought &f$multiRunChestsThisCycle&a chest" +
                    (if (multiRunChestsThisCycle == 1) "" else "s") + " across " +
                    "&f$multiRunRunsThisCycle&a run" +
                    (if (multiRunRunsThisCycle == 1) "" else "s") +
                    "; can't find Croesus NPC within 6 blocks to continue."
            )
            resetCycle()
            return
        }
        AuraManager.interactEntity(entity, AuraAction.INTERACT_AT)
        // Hand off to AWAIT_CROESUS_LIST — when the menu reopens, the list
        // handler picks the next unclaimed run automatically.
        claimState = ClaimState.AWAIT_CROESUS_LIST
        // Triple the timeout — entity interaction has its own ~1t cooldown
        // and the server can take a moment to push the menu back.
        claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 3).toLong()
    }

    /** Find the nearest entity within 6 blocks whose custom name or hover
     *  name contains "Croesus". Returns null if no such entity is in range —
     *  the player may have walked away. */
    private fun findCroesusEntity(): Entity? {
        val player = mc.player ?: return null
        val playerPos = player.position()
        return EntityUtils.getEntities()
            .filter { e ->
                if (e === player) return@filter false
                if (e.position().distanceTo(playerPos) > 6.0) return@filter false
                val custom = e.customName?.string ?: ""
                val hover = e.name.string  // Entity.getName() never returns null
                "Croesus" in custom || "Croesus" in hover
            }
            .minByOrNull { it.position().distanceTo(playerPos) }
    }

    /** Kick off a multi-run cycle from the Croesus list. The actual scan +
     *  click happens in the TickEvent polling loop — going through the
     *  same code path as the post-back-out and post-NPC-reopen flows
     *  guarantees we always wait for slot data to populate first. */
    private fun tryStartMultiRun(screen: AbstractContainerScreen<*>) {
        if (!multiRun) {
            modMessage("&cAutoCroesus: enable the &fMulti-run claim&c switch to use the " +
                "claim key from the Croesus list.")
            return
        }
        multiRunChestsThisCycle = 0
        multiRunRunsThisCycle = 0
        chainClaimsThisCycle = 0
        modMessage("&aAutoCroesus: starting multi-run cycle…")
        claimState = ClaimState.AWAIT_CROESUS_LIST
        claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
    }

    /** Click an unclaimed run by slot index and transition to AWAIT_RUN_SCREEN.
     *  Called from the TickEvent polling loop once slot data has populated. */
    private fun clickUnclaimedRun(slot: Int): Boolean {
        if (!ContainerUtils.click(slot)) {
            modMessage("&cAutoCroesus: failed to click run slot $slot.")
            resetCycle()
            return false
        }
        multiRunRunsThisCycle++
        claimState = ClaimState.AWAIT_RUN_SCREEN
        claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
        return true
    }

    /** End-of-cycle summary — fires when the Croesus list shows no unclaimed
     *  runs (after slot data has loaded, so we know it's the real answer). */
    private fun completeMultiRun() {
        modMessage(
            "&aMulti-run complete — bought &f$multiRunChestsThisCycle&a chest" +
                (if (multiRunChestsThisCycle == 1) "" else "s") + " across " +
                "&f$multiRunRunsThisCycle&a run" +
                (if (multiRunRunsThisCycle == 1) "" else "s") +
                "; no more unclaimed runs visible."
        )
        resetCycle()
    }

    /** Validates preconditions, picks the best chest, sends the first click,
     *  and transitions to AWAIT_CONFIRM.
     *
     *  When [fromChain] is true the call comes from the chain driver (not a
     *  keybind press), so:
     *    - precondition failures don't shout in chat (the driver tried best
     *      effort, no user attention needed),
     *    - "no chest above threshold" either prints a chain-complete summary
     *      (single-run mode) or sends the "Go Back" click to advance the
     *      multi-run cycle to the next unclaimed run. */
    private fun tryStartClaim(screen: AbstractContainerScreen<*>, fromChain: Boolean = false) {
        // The keybind handler already checked autoClaim / IDLE for the manual
        // path; chain calls have those preconditions checked by their callers.
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
                if (multiRun) {
                    // Continue the multi-run cycle: leave this run, advance
                    // to the next unclaimed one. The summary fires when the
                    // cycle eventually ends in tryClickNextRun.
                    modMessage(
                        "&7Run done — best remaining (&r${best.tierColourCode}${best.tierName}&7) profit " +
                            "&f${PriceClient.formatPrice(best.profit)}&7 below threshold; backing out."
                    )
                    clickGoBackToList()
                } else {
                    modMessage(
                        "&aAutoCroesus chain complete — bought $chainClaimsThisCycle chest" +
                            (if (chainClaimsThisCycle == 1) "" else "s") +
                            "; next-best (&r${best.tierColourCode}${best.tierName}&a) profit " +
                            "&f${PriceClient.formatPrice(best.profit)}&a is below threshold."
                    )
                    chainClaimsThisCycle = 0
                }
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
