package cop.module.impl.dungeon.cheats

import cop.api.events.GuiEvent
import cop.api.events.TickEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.abs
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
 *  - **RUBIX**    pick a target colour minimizing total cycle distance, click
 *                 each non-target item the optimal direction.
 *  - **MELODY**   detect lime indicator + magenta column marker, click the
 *                 button at column 7 of the lime row when columns align.
 *
 * @author elvin
 */
object AutoTerms : Module(
    "Auto Terms",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Automatically solves the F7/M7 chest terminals.",
) {
    // ---- settings ----
    private val solveNumbers by switch("Numbers", true)
    private val solvePanes   by switch("Panes",   true)
    private val solveName    by switch("Name",    true)
    private val solveColors  by switch("Colors",  true)
    private val solveRubix   by switch("Rubix",   true)
    private val solveMelody  by switch("Melody",  true)

    private val minDelayMs by slider(
        "Min delay", 80, 0, 500, 10, unit = "ms",
        desc = "Lower bound for the randomized delay between clicks.",
    )
    private val maxDelayMs by slider(
        "Max delay", 160, 0, 500, 10, unit = "ms",
        desc = "Upper bound for the randomized delay between clicks.",
    )
    private val melodyDebounceMs by slider(
        "Melody debounce", 250, 100, 600, 10, unit = "ms",
        desc = "Minimum gap between melody button presses; prevents double-clicks " +
                "when the moving indicator lingers on the correct column for more than one tick.",
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
        COLORS (54, Regex("^Select all the ([\\w ]+) items!$")),
        RUBIX  (45, Regex("^Change all to same color!$")),
        MELODY (54, Regex("^Click the button on time!$"));

        companion object {
            fun fromTitle(title: String): Type? = entries.firstOrNull { it.regex.matches(title) }
        }
    }

    @Volatile private var active: Type? = null
    @Volatile private var titleArg: String? = null
    @Volatile private var nextClickAt: Long = 0L
    @Volatile private var lastClickedSlot: Int = -1
    @Volatile private var lastMelodyClickAt: Long = 0L
    @Volatile private var rubixTarget: Int = -1   // index into RUBIX_CYCLE; locked once chosen

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
            lastMelodyClickAt = 0L
            rubixTarget = -1
        }

        on<GuiEvent.Close> {
            active = null
            titleArg = null
            nextClickAt = 0L
            lastClickedSlot = -1
            lastMelodyClickAt = 0L
            rubixTarget = -1
        }

        on<TickEvent.End> {
            val t = active ?: return@on
            val player = mc.player ?: return@on
            val menu = player.containerMenu
            if (menu === player.inventoryMenu) return@on

            // Melody is timing-driven — bypass the random-delay gate so we don't
            // miss the on-beat tick. Its own debounce keeps it from chain-firing.
            if (t == Type.MELODY) {
                val slot = pickMelody(menu) ?: return@on
                val now = System.currentTimeMillis()
                if (now - lastMelodyClickAt < melodyDebounceMs) return@on
                player.clickSlot(slot, menu.containerId, button = 2)
                lastMelodyClickAt = now
                lastClickedSlot = slot
                return@on
            }

            if (System.currentTimeMillis() < nextClickAt) return@on
            val slot = pickSlot(t, menu) ?: return@on
            val button = if (t == Type.RUBIX) pickRubixButton(menu, slot) else 2
            player.clickSlot(slot, menu.containerId, button = button)
            lastClickedSlot = slot
            nextClickAt = System.currentTimeMillis() + nextDelay()
        }
    }

    private fun Type.enabledByUser(): Boolean = when (this) {
        Type.NUMBERS -> solveNumbers
        Type.PANES   -> solvePanes
        Type.NAME    -> solveName
        Type.COLORS  -> solveColors
        Type.RUBIX   -> solveRubix
        Type.MELODY  -> solveMelody
    }

    private fun pickSlot(t: Type, menu: AbstractContainerMenu): Int? = when (t) {
        Type.NUMBERS -> pickNumbers(menu)
        Type.PANES   -> pickPanes(menu)
        Type.NAME    -> pickName(menu)
        Type.COLORS  -> pickColors(menu)
        Type.RUBIX   -> pickRubix(menu)
        Type.MELODY  -> pickMelody(menu)
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
            if (i == lastClickedSlot) continue
            return i
        }
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
            if (item.hasFoil()) continue
            val name = item.hoverName.string.noControlCodes.lowercase()
            if (colourWord in name) return i
        }
        return null
    }

    // ---- Rubix --------------------------------------------------------------

    /** Cycle order of the 5 stained-pane colours used by the rubix terminal.
     *  Each click moves +1 (left button) or -1 (right button) along this list. */
    private val RUBIX_CYCLE: List<Item> = listOf(
        Items.RED_STAINED_GLASS_PANE,
        Items.ORANGE_STAINED_GLASS_PANE,
        Items.YELLOW_STAINED_GLASS_PANE,
        Items.GREEN_STAINED_GLASS_PANE,
        Items.BLUE_STAINED_GLASS_PANE,
    )

    /** The 3×3 grid of colour-cyclable slots inside the chest. */
    private val RUBIX_SLOTS = intArrayOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    private fun pickRubix(menu: AbstractContainerMenu): Int? {
        val target = chooseRubixTarget(menu)
        if (target < 0) return null

        for (slot in RUBIX_SLOTS) {
            val item = menu.slots.getOrNull(slot)?.item ?: continue
            val idx = RUBIX_CYCLE.indexOf(item.item)
            if (idx < 0) continue
            if (idx == target) continue
            return slot
        }
        return null
    }

    /** Choose `0=forward (left)` or `1=backward (right)` for the next rubix
     *  click, picking whichever travels fewer steps along the 5-cycle. */
    private fun pickRubixButton(menu: AbstractContainerMenu, slot: Int): Int {
        val target = chooseRubixTarget(menu)
        val item = menu.slots.getOrNull(slot)?.item ?: return 2
        val idx = RUBIX_CYCLE.indexOf(item.item).takeIf { it >= 0 } ?: return 2

        var diff = target - idx
        if (diff > 2) diff -= 5
        else if (diff < -2) diff += 5
        // Hypixel rubix: button 0 = left-click cycles +1, button 1 = right-click cycles -1.
        return if (diff > 0) 0 else 1
    }

    /** First call computes the target colour with minimum total cycle distance
     *  across the 9 cyclable slots, then locks it for the rest of the session
     *  so we don't oscillate as items change colour mid-solve. */
    private fun chooseRubixTarget(menu: AbstractContainerMenu): Int {
        if (rubixTarget >= 0) return rubixTarget

        val indices = IntArray(9)
        var count = 0
        for (slot in RUBIX_SLOTS) {
            val item = menu.slots.getOrNull(slot)?.item ?: continue
            val idx = RUBIX_CYCLE.indexOf(item.item)
            if (idx < 0) continue
            indices[count++] = idx
        }
        if (count == 0) return -1

        var best = 0
        var bestCost = Int.MAX_VALUE
        for (t in 0 until 5) {
            var cost = 0
            for (i in 0 until count) {
                val d = abs(t - indices[i])
                cost += if (d > 2) 5 - d else d
            }
            if (cost < bestCost) {
                bestCost = cost
                best = t
            }
        }
        rubixTarget = best
        return best
    }

    // ---- Melody -------------------------------------------------------------

    /** When the moving lime indicator's column matches the magenta target
     *  column (in row 0), return the slot of the column-7 button on the same
     *  row as the lime indicator. */
    private fun pickMelody(menu: AbstractContainerMenu): Int? {
        val limit = Type.MELODY.slots

        var limeSlot = -1
        var magentaCol = -1
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            when (item.item) {
                Items.LIME_STAINED_GLASS_PANE    -> if (limeSlot < 0) limeSlot = i
                Items.MAGENTA_STAINED_GLASS_PANE -> if (magentaCol < 0) magentaCol = i % 9
                else -> {}
            }
        }
        if (limeSlot < 0 || magentaCol < 0) return null

        val limeCol = limeSlot % 9
        if (limeCol != magentaCol) return null

        val limeRow = limeSlot / 9
        val buttonSlot = limeRow * 9 + 7  // column 7 of the lime's row
        if (buttonSlot >= limit) return null
        return buttonSlot
    }

    // ---- helpers ------------------------------------------------------------

    private fun nextDelay(): Long {
        val lo = minDelayMs.toLong()
        val hi = max(lo, maxDelayMs.toLong())
        return if (lo == hi) lo else Random.nextLong(lo, hi + 1)
    }

    /** True if the stack has the enchantment glint visual (used by Hypixel to
     *  mark "already-correct" items in NAME and COLORS terminals). */
    private fun ItemStack.hasFoil(): Boolean = !isEmpty && this.isEnchanted
}
