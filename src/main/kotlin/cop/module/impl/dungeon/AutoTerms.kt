package cop.module.impl.dungeon

import cop.api.events.GuiEvent
import cop.api.events.TickEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.max
import kotlin.random.Random

/**
 * Auto-solver for the F7/M7 Catacombs floor-7 chest terminals.
 *
 * Detects terminal type from the chest window title, recomputes the click
 * candidates each tick, and middle-click-clones one candidate per tick window
 * with randomized delay so it doesn't show up as a perfectly periodic robot to
 * Hypixel's chest-click rate-limiter.
 *
 *  - **NUMBERS**  click items in numeric order (item count = number).
 *  - **PANES**    click every red glass pane (toggle to lime).
 *  - **NAME**     click items whose name starts with the prompt letter,
 *                 skipping ones already enchant-glinted (= already correct).
 *  - **COLORS**   click items whose name contains the requested colour word.
 *
 * Rubix and Melody are intentionally not implemented — Rubix needs full
 * cycle-state tracking and Melody is timing-driven; both better as their own
 * dedicated modules later.
 *
 * @author elvin
 */
object AutoTerms : Module(
    "Auto Terms",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Automatically solves the F7/M7 chest terminals (Numbers, Panes, Name, Colors).",
) {
    // ---- settings ----
    private val solveNumbers by switch("Numbers", true)
    private val solvePanes   by switch("Panes",   true)
    private val solveName    by switch("Name",    true)
    private val solveColors  by switch("Colors",  true)

    private val minDelayMs by slider(
        "Min delay", 80, 0, 500, 10, unit = "ms",
        desc = "Lower bound for the randomized delay between clicks.",
    )
    private val maxDelayMs by slider(
        "Max delay", 160, 0, 500, 10, unit = "ms",
        desc = "Upper bound for the randomized delay between clicks.",
    )

    private val toggleKey = keybind(
        "Toggle", CatKeys.KEY_NONE,
        desc = "Quick on/off toggle without opening the ClickGui.",
    ).onPress { toggle(); toggleMessage() }.also { register(it) }

    // ---- runtime state ----
    private enum class Type(val slots: Int, val regex: Regex) {
        NUMBERS(36, Regex("^Click in order!$")),
        PANES  (45, Regex("^Correct all the panes!$")),
        NAME   (45, Regex("^What starts with: '(\\w)'\\?$")),
        COLORS (54, Regex("^Select all the ([\\w ]+) items!$"));

        companion object {
            fun fromTitle(title: String): Type? = entries.firstOrNull { it.regex.matches(title) }
        }
    }

    @Volatile private var active: Type? = null
    @Volatile private var titleArg: String? = null
    @Volatile private var nextClickAt: Long = 0L
    @Volatile private var lastClickedSlot: Int = -1

    init {
        on<GuiEvent.Open.Post> {
            val container = screen as? AbstractContainerScreen<*> ?: return@on
            val title = container.title.string.noControlCodes
            val t = Type.fromTitle(title)
            if (t == null) {
                active = null
                return@on
            }
            if (!t.enabledByUser()) {
                active = null
                return@on
            }
            active = t
            titleArg = t.regex.matchEntire(title)?.groupValues?.getOrNull(1)
            nextClickAt = System.currentTimeMillis() + nextDelay()
            lastClickedSlot = -1
        }

        on<GuiEvent.Close> {
            active = null
            titleArg = null
            nextClickAt = 0L
            lastClickedSlot = -1
        }

        on<TickEvent.End> {
            val t = active ?: return@on
            if (System.currentTimeMillis() < nextClickAt) return@on

            val player = mc.player ?: return@on
            val menu = player.containerMenu
            // The screen may have closed (cancelled by server) or transitioned
            // to a different chest — defer until GuiEvent.Open re-detects.
            if (menu === player.inventoryMenu) return@on

            val slot = pickSlot(t, menu) ?: return@on
            // Hypixel terminals expect middle-click (button 2) with CLONE — our
            // clickSlot translates that automatically.
            player.clickSlot(slot, menu.containerId, button = 2)
            lastClickedSlot = slot
            nextClickAt = System.currentTimeMillis() + nextDelay()
        }
    }

    private fun Type.enabledByUser(): Boolean = when (this) {
        Type.NUMBERS -> solveNumbers
        Type.PANES   -> solvePanes
        Type.NAME    -> solveName
        Type.COLORS  -> solveColors
    }

    private fun pickSlot(t: Type, menu: AbstractContainerMenu): Int? = when (t) {
        Type.NUMBERS -> pickNumbers(menu)
        Type.PANES   -> pickPanes(menu)
        Type.NAME    -> pickName(menu)
        Type.COLORS  -> pickColors(menu)
    }

    /** Click the lowest-count remaining red-pane in the chest. */
    private fun pickNumbers(menu: AbstractContainerMenu): Int? {
        val limit = Type.NUMBERS.slots
        var bestSlot = -1
        var bestCount = Int.MAX_VALUE
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (item.item != Items.RED_STAINED_GLASS_PANE) continue
            val c = item.count
            if (c < bestCount) {
                bestCount = c
                bestSlot = i
            }
        }
        return bestSlot.takeIf { it >= 0 }
    }

    /** Click any remaining red glass pane. */
    private fun pickPanes(menu: AbstractContainerMenu): Int? {
        val limit = Type.PANES.slots
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (item.item != Items.RED_STAINED_GLASS_PANE) continue
            // Skip slot we just clicked — gives the server a tick to register
            // the state change before we hammer it again.
            if (i == lastClickedSlot) continue
            return i
        }
        // If only the just-clicked slot is left, click it again next tick.
        return lastClickedSlot.takeIf { it >= 0 && menu.slots.getOrNull(it)?.item?.item == Items.RED_STAINED_GLASS_PANE }
    }

    /** Click items whose display name starts with `titleArg`, skipping
     *  enchant-glinted ones (already-correct in NAME terminals). */
    private fun pickName(menu: AbstractContainerMenu): Int? {
        val letter = titleArg?.lowercase() ?: return null
        val limit = Type.NAME.slots
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (i == lastClickedSlot) continue
            if (item.isEmpty) continue
            if (item.hasFoil()) continue
            val name = item.hoverName.string.noControlCodes.lowercase()
            if (name.startsWith(letter)) return i
        }
        return null
    }

    /** Click items whose display name contains the prompt colour word. */
    private fun pickColors(menu: AbstractContainerMenu): Int? {
        val colourWord = titleArg?.lowercase() ?: return null
        val limit = Type.COLORS.slots
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (i == lastClickedSlot) continue
            if (item.isEmpty) continue
            // Already-correct items in COLORS show enchant glint.
            if (item.hasFoil()) continue
            val name = item.hoverName.string.noControlCodes.lowercase()
            if (colourWord in name) return i
        }
        return null
    }

    private fun nextDelay(): Long {
        val lo = minDelayMs.toLong()
        val hi = max(lo, maxDelayMs.toLong())
        return if (lo == hi) lo else Random.nextLong(lo, hi + 1)
    }

    /** True if the stack has the enchantment glint visual (used by Hypixel to
     *  mark "already-correct" items in NAME and COLORS terminals). */
    private fun ItemStack.hasFoil(): Boolean = !isEmpty && this.isEnchanted
}
