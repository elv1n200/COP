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
import cop.utils.skyblock.ItemUtils.skyblockId
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
 * Auto Croesus — Phase 4 (kismet rerolls layered on top of multi-run auto-claim).
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
 *  - **Phase 4:** [useKismet] — when entering a buy-confirm whose profit is
 *    below [rerollThreshold], consumes a Kismet Feather from inventory to
 *    reroll once. After the reroll, buys if the new profit is at or above
 *    [minProfit]; otherwise backs out (kismet is gone — that's the gamble).
 *
 * No pagination, no loot log — saved for 5+. Master kill switch [autoClaim]
 * defaults OFF so the keybind is inert until the user explicitly opts in.
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

    // -- Phase 4 (kismet rerolls) --------------------------------------------

    private val useKismet by switch(
        "Use kismet", false,
        desc = "On a buy-confirm whose chest profit is below the Reroll threshold, consume a " +
            "Kismet Feather from inventory to reroll the chest contents once. If the reroll " +
            "leaves profit below Min profit, the driver backs out and continues the cycle " +
            "(the kismet is gone — that's the gamble). One kismet max per chest per run."
    )
    private val rerollThreshold by slider(
        "Reroll threshold", 500_000, 0, 5_000_000, 50_000,
        desc = "Trigger a kismet reroll if the best chest's profit is below this. Should be " +
            "higher than Min profit; otherwise rerolls never fire (the chest would never enter " +
            "the buy-confirm in the first place). Set to 0 to disable the threshold gate."
    )

    /** Cached parser output for the currently-open run sub-screen. */
    @Volatile private var lastChests: List<ChestParseResult> = emptyList()
    /** Sticky containerId snapshot — wipes cache when the user opens a different container. */
    private var cachedContainerId: Int = -1
    private var ticksSinceParse = 0

    /** The claim driver's state machine.
     *   - IDLE                — nothing in flight; keybind is the only way in.
     *   - AWAIT_CONFIRM       — sent chest-tier click, waiting for Hypixel to open
     *                           the buy-confirm screen so we can decide buy vs. reroll.
     *   - AWAIT_REROLL_RESULT — sent kismet reroll click on the buy-confirm; waiting
     *                           for slot 31's lore to refresh with the new chest contents.
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
        AWAIT_REROLL_RESULT,
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

    /** First tick at which the Croesus list was observed populated (slot 4
     *  + slot 49 non-empty). We wait an additional [CROESUS_SYNC_DELAY_TICKS]
     *  after that before clicking — Hypixel pushes slot data asynchronously
     *  and rejects clicks whose lastStateId is behind the server's, refreshing
     *  the menu back at us instead of opening the run. 0 means "not yet
     *  populated". */
    private var croesusReadyAtTick = 0L
    private val CROESUS_SYNC_DELAY_TICKS = 10L

    /** True after we send a reroll click on the current buy-confirm — guards
     *  against double-rerolling the same chest. Reset each time we start a
     *  fresh claim cycle in [tryStartClaim]. */
    private var hasRerolledThisChest = false
    /** Earliest tick at which we'll re-parse the buy-confirm after a reroll
     *  click. Same idea as croesusReadyAtTick — give Hypixel time to push the
     *  refreshed slot lore before we re-evaluate. */
    private var rerollReadyAtTick = 0L
    private val REROLL_SYNC_DELAY_TICKS = 15L

    /** Earliest tick at which we'll evaluate the buy-confirm in the kismet
     *  path. handleConfirmOpen fires on GuiEvent.Open but slot 31's lore
     *  (Contents / Cost / items — what decideBuyOrReroll parses) isn't pushed
     *  until a few ticks later. Without this delay the parse fails, chest=null,
     *  the reroll branch's `chest != null` guard skips, and we fall through to
     *  buy without rerolling — even with a kismet sitting in inv. */
    private var confirmReadyAtTick = 0L
    private val CONFIRM_SYNC_DELAY_TICKS = 10L

    /** Chest tier slot we sent the most recent click on (in the run sub-screen).
     *  Captured at click time so the back-out branch in [decideBuyOrReroll]
     *  can mark it [exhaustedSlotsThisRun] and stop tryStartClaim from re-
     *  selecting the same slot on the next chain iteration. */
    private var pendingChestSlot = -1
    /** Run-sub-screen slots we've already tried to claim in this run — either
     *  rerolled without recovering profit, or otherwise backed out of. Filters
     *  out of tryStartClaim's best-chest selection so we don't loop on the
     *  same chest. Cleared in [handleRunScreenOpen] (new run = clean slate)
     *  and in [resetCycle]. */
    private val exhaustedSlotsThisRun = mutableSetOf<Int>()

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
                ClaimState.AWAIT_CONFIRM        -> handleConfirmOpen(screen)
                ClaimState.AWAIT_AFTER_BUY      -> handleAfterBuyOpen(screen)
                ClaimState.AWAIT_RUN_SCREEN     -> handleRunScreenOpen(screen)
                ClaimState.AWAIT_CROESUS_LIST   -> handleCroesusListOpen(screen)
                ClaimState.AWAIT_NEXT_PARSE,
                ClaimState.AWAIT_REROLL_RESULT,
                ClaimState.IDLE -> {
                    // AWAIT_NEXT_PARSE / AWAIT_REROLL_RESULT both wait passively
                    // for the TickEvent poller to fire when their respective
                    // data has settled. IDLE has nothing to do.
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

            // Kismet poller (first-pass): handleConfirmOpen deferred the
            // decision because slot 31's lore isn't populated yet when the
            // Open event fires. Wait CONFIRM_SYNC_DELAY_TICKS AND require
            // the lore to contain "Cost" (= Hypixel finished pushing) before
            // running decideBuyOrReroll.
            if (claimState == ClaimState.AWAIT_CONFIRM && useKismet &&
                CroesusParser.inBuyConfirmMenu(screen) &&
                monotonicTick >= confirmReadyAtTick) {
                val lore = CroesusParser.lorePlain(screen.menu, CroesusParser.BUY_CONFIRM_SLOT)
                val ready = lore?.any { it.trim() == "Cost" } == true
                if (ready) decideBuyOrReroll(screen)
                // Else keep polling — claimDeadlineTick catches stuck states.
            }

            // Kismet poller (post-reroll): after a reroll click, wait
            // REROLL_SYNC_DELAY_TICKS for Hypixel to refresh slot 31's lore,
            // then re-enter the decide flow. hasRerolledThisChest is true at
            // this point so the reroll branch won't fire again — only buy or
            // skip is reachable.
            if (claimState == ClaimState.AWAIT_REROLL_RESULT &&
                CroesusParser.inBuyConfirmMenu(screen) &&
                monotonicTick >= rerollReadyAtTick) {
                decideBuyOrReroll(screen)
            }

            // Multi-run polling: in AWAIT_CROESUS_LIST, wait for the menu to
            // fully populate, then wait CROESUS_SYNC_DELAY_TICKS extra ticks
            // so Hypixel's lastStateId is current before we click — otherwise
            // the server rejects our click and refreshes the menu at us
            // instead of opening the run sub-screen.
            if (claimState == ClaimState.AWAIT_CROESUS_LIST &&
                CroesusParser.inCroesusMenu(screen)) {
                val slot4 = screen.menu.slots.getOrNull(4)?.item?.isEmpty == false
                val slot49 = screen.menu.slots.getOrNull(49)?.item?.isEmpty == false
                val populated = slot4 && slot49
                if (populated) {
                    if (croesusReadyAtTick == 0L) croesusReadyAtTick = monotonicTick
                    if (monotonicTick - croesusReadyAtTick >= CROESUS_SYNC_DELAY_TICKS) {
                        val unclaimed = CroesusParser.findUnclaimedRunSlots(screen.menu)
                        if (unclaimed.isNotEmpty()) {
                            clickUnclaimedRun(unclaimed.first())
                        } else {
                            completeMultiRun()
                        }
                        croesusReadyAtTick = 0L
                    }
                } else {
                    // Slot data still arriving — reset and keep waiting.
                    croesusReadyAtTick = 0L
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

    /** Step 2 of a claim cycle: the buy-confirm just opened.
     *
     *  Fast path (useKismet off): no decision to make — slot 31's lore isn't
     *  needed, we just want to click slot 31 by index. Handing straight to
     *  decideBuyOrReroll skips the reroll branch and clicks buy.
     *
     *  Kismet path: defer until the TickEvent poller sees slot 31 populated.
     *  Hypixel pushes slot data asynchronously after the open packet, so
     *  parsing here would read empty lore, fail, and silently fall through
     *  to buy (bug observed on real data — the kismet was never used). */
    private fun handleConfirmOpen(screen: net.minecraft.client.gui.screens.Screen) {
        if (!CroesusParser.inBuyConfirmMenu(screen)) {
            val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "?"
            modMessage("&cAutoCroesus: aborted — expected buy-confirm, got \"$title\".")
            resetCycle()
            return
        }
        if (!useKismet) {
            decideBuyOrReroll(screen as AbstractContainerScreen<*>)
            return
        }
        // Kismet armed: TickEvent will fire decideBuyOrReroll once the
        // CONFIRM_SYNC_DELAY_TICKS deadline has passed AND slot 31's lore
        // contains a "Cost" line (i.e. Hypixel finished pushing it).
        confirmReadyAtTick = monotonicTick + CONFIRM_SYNC_DELAY_TICKS
    }

    /** Single decision point for the buy-confirm screen.
     *
     *  Called twice in the kismet-reroll path: once from [handleConfirmOpen]
     *  when the screen first opens (eligible for reroll), and once from the
     *  TickEvent poller after the reroll lore has refreshed
     *  ([hasRerolledThisChest] is then true, so only buy/skip are reachable). */
    private fun decideBuyOrReroll(screen: AbstractContainerScreen<*>) {
        val title = screen.title.string.trim()
        val parsed = CroesusParser.parseBuyConfirmChest(screen.menu, title)
        val chest = (parsed as? ChestParseResult.Success)?.chest

        // First branch: try a reroll if the user has it armed and we haven't
        // already burned a feather on this chest.
        if (chest != null && useKismet && !hasRerolledThisChest &&
            chest.profit < rerollThreshold && hasKismetFeather()) {
            if (!ContainerUtils.click(CroesusParser.BUY_REROLL_SLOT)) {
                modMessage("&cAutoCroesus: kismet click failed (no container id).")
                resetCycle()
                return
            }
            hasRerolledThisChest = true
            modMessage(
                "&d⟳ AutoCroesus: rerolling &r$pendingTier&d chest with kismet " +
                    "&7(profit ${PriceClient.formatPrice(chest.profit)} < threshold " +
                    "${PriceClient.formatPrice(rerollThreshold.toDouble())})."
            )
            claimState = ClaimState.AWAIT_REROLL_RESULT
            rerollReadyAtTick = monotonicTick + REROLL_SYNC_DELAY_TICKS
            claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
            return
        }

        // Second branch: back out if profit is below Min profit. Two ways to
        // reach here:
        //   (a) post-reroll: kismet was burned but the new contents are still
        //       below Min profit — buying would be a guaranteed loss.
        //   (b) speculative-enter without reroll: useKismet was on at click
        //       time, but the feather vanished before the buy-confirm opened
        //       (rare race), so we entered but can't actually upgrade.
        // Either way, mark the slot exhausted so the next chain iteration
        // doesn't re-pick it.
        if (chest != null && chest.profit < minProfit) {
            if (!ContainerUtils.click(CroesusParser.BUY_BACK_SLOT)) {
                modMessage("&cAutoCroesus: back-out click failed.")
                resetCycle()
                return
            }
            if (pendingChestSlot >= 0) exhaustedSlotsThisRun.add(pendingChestSlot)
            val reason = if (hasRerolledThisChest) "post-reroll profit" else "profit"
            modMessage(
                "&cAutoCroesus: skipping &r${chest.tierColourCode}${chest.tierName}&c — " +
                    "$reason &f${PriceClient.formatPrice(chest.profit)}&c below " +
                    "Min profit. Continuing cycle…"
            )
            // Back at run sub-screen: in any auto mode, fall through to
            // AWAIT_NEXT_PARSE. tryStartClaim now filters exhausted slots,
            // so the chain handler picks the next-best (or backs out of the
            // run entirely in multi-run mode).
            claimState = when {
                multiRun || chainClaim -> ClaimState.AWAIT_NEXT_PARSE
                else -> ClaimState.IDLE
            }
            if (claimState != ClaimState.IDLE) {
                claimDeadlineTick = monotonicTick + (claimTimeoutTicks * 2).toLong()
            }
            return
        }

        // Third branch: buy normally.
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

    /** Returns true if the player has at least one Kismet Feather in the
     *  main 36-slot inventory (hotbar + main inv). Checks both the skyblock
     *  id (preferred — exact match) and the display name (fallback — survives
     *  the id changing if Hypixel ever renames it). */
    private fun hasKismetFeather(): Boolean {
        val player = mc.player ?: return false
        for (i in 0 until 36) {
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) continue
            if (stack.skyblockId == "KISMET_FEATHER") return true
            // Fallback: display-name contains. hoverName.string is plain text
            // (formatting stripped), so this catches "§dKismet Feather" too.
            if (stack.hoverName.string.contains("Kismet Feather", ignoreCase = true)) return true
        }
        return false
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
            // Fresh run = clean slate for exhausted slot tracking.
            exhaustedSlotsThisRun.clear()
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
        croesusReadyAtTick = 0L
        hasRerolledThisChest = false
        rerollReadyAtTick = 0L
        confirmReadyAtTick = 0L
        pendingChestSlot = -1
        exhaustedSlotsThisRun.clear()
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
     *      multi-run cycle to the next unclaimed run.
     *
     *  Kismet upgrade path: when [useKismet] is on AND a feather is in inv
     *  AND best chest profit is below [rerollThreshold], we enter the buy-
     *  confirm even if profit is also below [minProfit] — the reroll might
     *  recover. [decideBuyOrReroll] backs out if the post-reroll profit is
     *  still below [minProfit] and adds the slot to [exhaustedSlotsThisRun]
     *  so we don't re-pick it on the next chain iteration. */
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
        // Filter out chests we've already attempted (failed reroll + skip)
        // so chainClaim / multiRun don't pick the same slot forever.
        val best: ChestInfo? = lastChests
            .filterIsInstance<ChestParseResult.Success>()
            .filter { it.chest.slot !in exhaustedSlotsThisRun }
            .maxByOrNull { it.chest.profit }
            ?.chest
        if (best == null) {
            if (!fromChain) modMessage("&cAutoCroesus: no chests parsed yet — wait a moment for the overlay.")
            return
        }
        // Speculative-enter for kismet: if profit is below minProfit BUT we
        // have a kismet armed and the chest is also below rerollThreshold,
        // enter anyway to try the reroll.
        val canKismetUpgrade = useKismet && hasKismetFeather() && best.profit < rerollThreshold
        val canEnterBuyConfirm = best.profit >= minProfit || canKismetUpgrade
        if (!canEnterBuyConfirm) {
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
        // Each new chest gets a fresh reroll opportunity.
        hasRerolledThisChest = false
        pendingChestSlot = best.slot
        claimDeadlineTick = monotonicTick + claimTimeoutTicks.toLong()
        pendingTier = "${best.tierColourCode}${best.tierName}"
        val msg = if (best.profit < minProfit) {
            // Entered speculatively for the kismet upgrade path.
            "&dAutoCroesus: opening ★ &r$pendingTier&d chest to try a kismet upgrade " +
                "&7(profit ${PriceClient.formatPrice(best.profit)})."
        } else {
            "&aAutoCroesus: claiming ★ &r$pendingTier&a chest " +
                "&7(profit ${PriceClient.formatPrice(best.profit)})."
        }
        modMessage(msg)
    }

    /** Logs the current GUI's title + every non-empty slot (chest area only,
     *  player inv excluded) with name + lore so the parser can be debugged
     *  against real Hypixel data. Also dumps the player's main 36-slot
     *  inventory with skyblock IDs — needed to debug Phase 4 kismet detection.
     *  Output goes to the game log because chat isn't usable inside container
     *  screens. */
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
        // Player inventory (hotbar 0..8, main 9..35) with skyblock IDs —
        // used to diagnose Phase 4 kismet detection ("why didn't reroll fire").
        val player = mc.player
        if (player != null) {
            log.info("[cop] CroesusDump — player inventory (36 slots, hotbar + main):")
            for (i in 0 until 36) {
                val stack = player.inventory.getItem(i)
                if (stack.isEmpty) continue
                val name = stack.hoverName.string
                val id = stack.skyblockId ?: "(no skyblock id)"
                log.info("[cop]   inv[$i] name=\"$name\" id=\"$id\"")
            }
        }
        // Confirmation message — visible once the player closes the GUI.
        modMessage("&aDumped \"$title\" to latest.log ($end chest slots + 36 inv slots scanned).")
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
