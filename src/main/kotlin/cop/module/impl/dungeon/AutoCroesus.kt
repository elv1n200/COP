package cop.module.impl.dungeon

import cop.api.colour.Colour
import cop.api.events.GuiEvent
import cop.api.events.TickEvent
import cop.api.skyblock.croesus.ChestParseResult
import cop.api.skyblock.croesus.CroesusParser
import cop.module.Module
import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.PriceClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component

/**
 * Auto Croesus — Phase 2 (overlay-only).
 *
 *  - Highlights unclaimed runs on the top-level Croesus screen.
 *  - On a run sub-screen, draws an overlay listing each chest tier with cost,
 *    total value, and profit (positive in green, negative/zero in red).
 *
 * No clicking. No automation. Future phases will add the auto-claim driver,
 * reroll/kismet logic, loot log, and configurable safety lists on top of this.
 */
object AutoCroesus : Module(
    "Auto Croesus",
    desc = "Highlights unclaimed runs in the Croesus menu and shows per-chest profit. (Phase 2: overlay only — no clicking yet.)"
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
    private val refreshTicks by slider(
        "Refresh rate", 5, 1, 40, 1,
        desc = "Re-parse the open chest GUI every N ticks (lower = snappier, higher = cheaper).", unit = "t"
    )

    /** Cached parser output for the currently-open run sub-screen. */
    @Volatile private var lastChests: List<ChestParseResult> = emptyList()
    /** Sticky containerId snapshot — wipes cache when the user opens a different container. */
    private var cachedContainerId: Int = -1
    private var ticksSinceParse = 0

    init {
        // Wipe caches whenever a GUI opens/closes so we never show stale data
        // from a previous run when the player moves to the next one.
        on<GuiEvent.Close> { reset() }
        on<GuiEvent.Open> { reset() }

        on<TickEvent.Start> {
            val screen = mc.screen as? AbstractContainerScreen<*>
            if (screen == null) { reset(); return@on }

            // Kick the bulk refresh in the background — first time the user
            // opens Croesus this session, prices start streaming in. Subsequent
            // opens are instant (cached for 30 min).
            PriceClient.refreshIfStale()

            if (CroesusParser.inRunMenu(screen)) {
                val cid = screen.menu.containerId
                if (cid != cachedContainerId) { lastChests = emptyList(); cachedContainerId = cid; ticksSinceParse = Int.MAX_VALUE }
                ticksSinceParse++
                if (ticksSinceParse >= refreshTicks) {
                    lastChests = CroesusParser.parseChests(screen.menu)
                    ticksSinceParse = 0
                }
            } else if (lastChests.isNotEmpty()) {
                lastChests = emptyList()
            }
        }

        on<GuiEvent.Slot.Draw> {
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!CroesusParser.inCroesusMenu(screen)) return@on
            val stack = slot.item.takeUnless { it.isEmpty } ?: return@on
            val lore = stack.get(DataComponents.LORE) ?: return@on
            val hasMarker = lore.lines.any { it.formattedString == CroesusParser.LORE_UNCLAIMED_MARKER }
            if (!hasMarker) return@on
            drawSlotOutline(ctx, slot.x, slot.y, unclaimedColour.rgb, borderWidth)
        }

        on<GuiEvent.Draw.Post> {
            if (!showOverlay) return@on
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!CroesusParser.inRunMenu(screen)) return@on
            if (lastChests.isEmpty()) return@on
            renderProfitOverlay(ctx)
        }
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

    /** Top-left overlay: one block per chest with cost/value/profit. */
    private fun renderProfitOverlay(ctx: GuiGraphics) {
        val font = mc.font
        val x = 4
        var y = 4
        val lines = buildList<Component> {
            for (result in lastChests) {
                when (result) {
                    is ChestParseResult.Success -> {
                        val c = result.chest
                        val profitColour = if (c.profit >= 0) "§a+" else "§c"
                        add(Component.literal(
                            "${c.tierColourCode}${c.tierName} Chest §7(${PriceClient.formatPrice(c.cost)}) " +
                                "$profitColour${PriceClient.formatPrice(c.profit)}"
                        ))
                        for (item in c.items.take(6)) {
                            val v = item.unitValue * item.qty
                            val vColour = if (v > 0) "§a" else "§7"
                            // Strip the legacy "§5§o" prefix Hypixel slaps on every
                            // lore line for italic formatting — looks bad in overlay.
                            val name = item.displayName.removePrefix("§5§o")
                            add(Component.literal("  $name $vColour${PriceClient.formatPrice(v)}"))
                        }
                        if (c.items.size > 6) add(Component.literal("  §7… +${c.items.size - 6} more"))
                        add(Component.literal(""))  // blank separator
                    }
                    is ChestParseResult.Failure -> {
                        add(Component.literal("§c${result.tierName} Chest §7(${result.reason})"))
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
