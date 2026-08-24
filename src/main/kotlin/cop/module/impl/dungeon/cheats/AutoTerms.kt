package cop.module.impl.dungeon.cheats

import cop.api.events.GuiEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
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

    private val humanisation by text("Humanisation")
    private val clickOrder by selector(
        "Click order", "Human", arrayListOf("Default", "Random", "Human", "Chaotic"),
        desc = "Human follows nearby slots; Chaotic deliberately chooses the furthest candidate.",
    ).childOf(::humanisation)
    private val delayDistribution by selector(
        "Delay distribution", "Gaussian", arrayListOf("Uniform", "Gaussian"),
        desc = "Gaussian delays cluster near the middle instead of looking perfectly random.",
    ).childOf(::humanisation)

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
    private val resyncTimeoutMs by slider(
        "Server resync timeout", 800, 350, 1_500, 50, unit = "ms",
        desc = "Waits for Hypixel to confirm each non-Melody click before sending the next one.",
    ).childOf(::humanisation)
    private val melodyFirstClickDelayMs by slider(
        "Melody first click delay", 200, 0, 750, 25, unit = "ms",
        desc = "Wait after the Melody window opens before allowing the first click.",
    ).childOf(::solveMelody)
    private val melodySkip by switch(
        "Melody skip", desc = "Queues later Melody rows after a confirmed click.",
    ).childOf(::solveMelody)
    private val melodySkipMode by selector(
        "Melody skip mode", "Edges", arrayListOf("Edges", "All"),
        desc = "Edges only skips when the indicator is at an outer position.",
    ).childOf(::melodySkip)
    private val melodySkipFirstRow by switch(
        "Skip first row", desc = "Allows predictive skipping from Melody's first row.",
    ).childOf(::melodySkip)

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
    @Volatile private var lastMelodyLimeSlot: Int = -1
    @Volatile private var melodyReadyAt: Long = 0L
    @Volatile private var activeContainerId: Int = -1
    @Volatile private var awaitingSlot: Int = -1
    @Volatile private var awaitingUntil: Long = 0L
    @Volatile private var rubixTarget: Int = -1   // index into RUBIX_CYCLE; locked once chosen
    private val clickedNameSlots = hashSetOf<Int>()

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
            lastMelodyLimeSlot = -1
            melodyReadyAt = System.currentTimeMillis() + melodyFirstClickDelayMs
            activeContainerId = mc.player?.containerMenu?.containerId ?: -1
            awaitingSlot = -1
            awaitingUntil = 0L
            rubixTarget = -1
            clickedNameSlots.clear()
        }

        on<GuiEvent.Close> {
            active = null
            titleArg = null
            nextClickAt = 0L
            lastClickedSlot = -1
            lastMelodyClickAt = 0L
            lastMelodyLimeSlot = -1
            melodyReadyAt = 0L
            activeContainerId = -1
            awaitingSlot = -1
            awaitingUntil = 0L
            rubixTarget = -1
            clickedNameSlots.clear()
        }

        on<PacketEvent.ReceivedClient> {
            when (val update = packet) {
                is ClientboundContainerSetSlotPacket -> {
                    if (update.containerId == activeContainerId) {
                        if (active == Type.MELODY && update.slot in 0 until Type.MELODY.slots) {
                            when {
                                update.item.item == Items.LIME_STAINED_GLASS_PANE ->
                                    lastMelodyLimeSlot = update.slot
                                update.slot == lastMelodyLimeSlot -> lastMelodyLimeSlot = -1
                            }
                        }
                        if (update.slot == awaitingSlot) {
                            if (active == Type.NAME) clickedNameSlots += update.slot
                            awaitingSlot = -1
                            awaitingUntil = 0L
                        }
                    }
                }

                is ClientboundContainerSetContentPacket -> {
                    if (update.containerId == activeContainerId) {
                        if (active == Type.MELODY) lastMelodyLimeSlot = -1
                        if (active == Type.NAME && awaitingSlot >= 0) clickedNameSlots += awaitingSlot
                        awaitingSlot = -1
                        awaitingUntil = 0L
                    }
                }
            }
        }

        on<TickEvent.End> {
            val t = active ?: return@on
            val player = mc.player ?: return@on
            val menu = player.containerMenu
            if (menu === player.inventoryMenu) return@on

            // Melody is timing-driven — bypass the random-delay gate so we don't
            // miss the on-beat tick. Its own debounce keeps it from chain-firing.
            if (t == Type.MELODY) {
                val melody = melodyState(menu) ?: return@on
                val now = System.currentTimeMillis()
                if (now < melodyReadyAt) return@on
                if (now - lastMelodyClickAt < melodyDebounceMs) return@on
                player.clickSlot(melody.buttonSlot, menu.containerId, button = 2)
                lastMelodyClickAt = now
                lastClickedSlot = melody.buttonSlot
                scheduleMelodySkip(menu, melody)
                lastMelodyLimeSlot = -1
                return@on
            }

            val now = System.currentTimeMillis()
            if (awaitingSlot >= 0) {
                if (now < awaitingUntil) return@on
                if (lastClickedSlot == awaitingSlot) lastClickedSlot = -1
                awaitingSlot = -1
                awaitingUntil = 0L
            }
            if (now < nextClickAt) return@on
            val slot = pickSlot(t, menu) ?: return@on
            val button = if (t == Type.RUBIX) pickRubixButton(menu, slot) else 2
            player.clickSlot(slot, menu.containerId, button = button)
            lastClickedSlot = slot
            awaitingSlot = slot
            awaitingUntil = now + resyncTimeoutMs
            nextClickAt = now + nextDelay()
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
        Type.MELODY  -> melodyState(menu)?.buttonSlot
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
        if (bestSlot < 0) return null
        val candidates = (0 until limit).filter { i ->
            val item = menu.slots.getOrNull(i)?.item ?: return@filter false
            item.item == Items.RED_STAINED_GLASS_PANE && item.count == bestCount
        }
        return selectCandidate(menu, candidates)
    }

    /** Click any remaining red glass pane. */
    private fun pickPanes(menu: AbstractContainerMenu): Int? {
        val limit = Type.PANES.slots
        val candidates = buildList {
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (item.item != Items.RED_STAINED_GLASS_PANE) continue
            if (i == lastClickedSlot) continue
            add(i)
        }
        }
        selectCandidate(menu, candidates)?.let { return it }
        return lastClickedSlot.takeIf { it >= 0 && menu.slots.getOrNull(it)?.item?.item == Items.RED_STAINED_GLASS_PANE }
    }

    /** Click items whose display name starts with `titleArg`. Server-added
     *  glint marks an already-correct item; intrinsically glinting items remain
     *  valid and are tracked locally so they are clicked exactly once. */
    private fun pickName(menu: AbstractContainerMenu): Int? {
        val letter = titleArg?.lowercase() ?: return null
        val limit = Type.NAME.slots
        val candidates = buildList {
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (i == lastClickedSlot) continue
            if (i in clickedNameSlots) continue
            if (item.isEmpty) continue
            if (item.hasFoil() && item.item !in INTRINSIC_GLINT_ITEMS) continue
            val name = item.hoverName.string.noControlCodes.lowercase()
            if (name.startsWith(letter)) add(i)
        }
        }
        return selectCandidate(menu, candidates)
    }

    /** Click items matching the prompt colour, including Hypixel's legacy
     *  dye aliases (Wool=white, Ink=black, Lapis=blue, ...). */
    private fun pickColors(menu: AbstractContainerMenu): Int? {
        val colourWord = titleArg?.lowercase()?.let(::normalizeTerminalColour) ?: return null
        val limit = Type.COLORS.slots
        val candidates = buildList {
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (i == lastClickedSlot) continue
            if (item.isEmpty) continue
            if (item.item == Items.BLACK_STAINED_GLASS_PANE) continue
            if (item.hasFoil()) continue
            val name = normalizeTerminalColour(item.hoverName.string.noControlCodes.lowercase())
            if (name.startsWith(colourWord)) add(i)
        }
        }
        return selectCandidate(menu, candidates)
    }

    private fun normalizeTerminalColour(name: String): String {
        var normalized = name
        COLOR_ALIASES.forEach { (prefix, replacement) ->
            if (normalized.startsWith(prefix)) {
                normalized = replacement + normalized.removePrefix(prefix)
                return@forEach
            }
        }
        return normalized
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

    private val COLOR_ALIASES = linkedMapOf(
        "light gray" to "silver",
        "wool" to "white",
        "bone" to "white",
        "ink" to "black",
        "lapis" to "blue",
        "cocoa" to "brown",
        "dandelion" to "yellow",
        "rose" to "red",
        "cactus" to "green",
    )

    /**
     * Items whose normal appearance already has an enchantment glint.
     *
     * Module objects are constructed from Fabric's entrypoint, before Minecraft
     * has finished binding the built-in item components.  Resolving this set
     * eagerly therefore crashes the client with "Components not bound yet".
     * The first NAME terminal is opened long after registry bootstrap, so defer
     * the lookup until it is actually needed.
     */
    private val INTRINSIC_GLINT_ITEMS: Set<Item> by lazy(LazyThreadSafetyMode.NONE) {
        BuiltInRegistries.ITEM
            .filterTo(hashSetOf()) { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }
            .also { it += Items.GOLDEN_APPLE }
    }

    /** The 3×3 grid of colour-cyclable slots inside the chest. */
    private val RUBIX_SLOTS = intArrayOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    private fun pickRubix(menu: AbstractContainerMenu): Int? {
        val target = chooseRubixTarget(menu)
        if (target < 0) return null

        val candidates = buildList {
        for (slot in RUBIX_SLOTS) {
            val item = menu.slots.getOrNull(slot)?.item ?: continue
            val idx = RUBIX_CYCLE.indexOf(item.item)
            if (idx < 0) continue
            if (idx == target) continue
            add(slot)
        }
        }
        return selectCandidate(menu, candidates)
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
    private data class MelodyState(val buttonSlot: Int, val buttonRow: Int, val indicatorColumn: Int)

    private fun melodyState(menu: AbstractContainerMenu): MelodyState? {
        val limit = Type.MELODY.slots
        val limeSlot = lastMelodyLimeSlot.takeIf { slot ->
            slot in 0 until limit &&
                menu.slots.getOrNull(slot)?.item?.item == Items.LIME_STAINED_GLASS_PANE
        } ?: return null

        var magentaCol = -1
        for (i in 0 until limit) {
            val item = menu.slots.getOrNull(i)?.item ?: continue
            if (item.item == Items.MAGENTA_STAINED_GLASS_PANE) {
                magentaCol = i % 9
                break
            }
        }
        if (magentaCol < 0) return null

        val limeCol = limeSlot % 9
        if (limeCol != magentaCol) return null

        val limeRow = limeSlot / 9
        val buttonSlot = limeRow * 9 + 7  // column 7 of the lime's row
        if (buttonSlot >= limit) return null
        val logicalRow = ((buttonSlot - 16) / 9).coerceIn(0, 3)
        return MelodyState(buttonSlot, logicalRow, limeCol)
    }

    private fun scheduleMelodySkip(menu: AbstractContainerMenu, melody: MelodyState) {
        if (!melodySkip || melody.buttonRow >= 3) return
        if (!melodySkipFirstRow && melody.buttonRow == 0 && melody.indicatorColumn != 5) return
        if (melodySkipMode.selected == "Edges" && melody.indicatorColumn !in setOf(1, 5)) return

        val containerId = menu.containerId
        for (offset in 1..(3 - melody.buttonRow)) {
            scheduleTask(offset) {
                val player = mc.player ?: return@scheduleTask
                if (!enabled || active != Type.MELODY || activeContainerId != containerId) return@scheduleTask
                if (player.containerMenu !== menu || player.containerMenu.containerId != containerId) return@scheduleTask
                player.clickSlot(melody.buttonSlot + offset * 9, containerId, button = 2)
            }
        }
    }

    private fun selectCandidate(menu: AbstractContainerMenu, candidates: List<Int>): Int? {
        if (candidates.isEmpty()) return null
        return when (clickOrder.selected) {
            "Random" -> candidates.random()
            "Human", "Chaotic" -> {
                val anchor = lastClickedSlot.takeIf { it >= 0 } ?: (candidates.maxOrNull() ?: 0) / 2
                val sorted = candidates.shuffled().sortedBy { slotDistanceSquared(menu, it, anchor) }
                if (clickOrder.selected == "Chaotic") sorted.last() else sorted.first()
            }
            else -> candidates.first()
        }
    }

    private fun slotDistanceSquared(menu: AbstractContainerMenu, first: Int, second: Int): Int {
        val a = menu.slots.getOrNull(first) ?: return Int.MAX_VALUE
        val b = menu.slots.getOrNull(second) ?: return Int.MAX_VALUE
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    // ---- helpers ------------------------------------------------------------

    private fun nextDelay(): Long {
        val lo = minDelayMs.toLong()
        val hi = max(lo, maxDelayMs.toLong())
        if (lo == hi) return lo
        if (delayDistribution.selected == "Uniform") return Random.nextLong(lo, hi + 1)

        // Box-Muller normal distribution, clamped to the configured range.
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val normal = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        val mean = (lo + hi) / 2.0
        val sigma = (hi - lo) / 6.0
        return (mean + normal * sigma).toLong().coerceIn(lo, hi)
    }

}
