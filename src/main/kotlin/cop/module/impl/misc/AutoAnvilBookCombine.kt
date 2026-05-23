package cop.module.impl.misc

import cop.api.events.GuiEvent
import cop.api.events.TickEvent
import cop.module.Module
import cop.utils.Scheduler.scheduleTask
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.loreString
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * Ported in spirit from a ChatTriggers Auto Anvil Book Combine module.
 *
 * In Hypixel's anvil chest GUI: scan the player inventory for two enchanted
 * books with the same single enchantment + level (1..maxLevel), shift-click
 * them into the input slots (29, 33), then click the result slot (22) twice —
 * first to combine, second to claim. Loops until no matching pair remains.
 *
 * Anvil chest layout (54-slot chest + 36 inventory = slots 0..89):
 *   - slot 22       — result / status (lore says "Click to combine!" once two
 *                     matching inputs are loaded, then changes after combining)
 *   - slot 29, 33   — input slots (left and right of the result)
 *   - slots 54..89  — the player's inventory (where we look for pairs)
 */
object AutoAnvilBookCombine : Module(
    "Auto Anvil Book Combine",
    desc = "In Hypixel's anvil, automatically pairs identical enchanted books and combines them."
) {
    private val maxLevel by slider(
        "Max level", 4, 1, 4, 1,
        desc = "Only auto-combines pairs at or below this level (lvl 4+4 -> 5 is usually the cap)."
    )
    private val delayTicks by slider(
        "Click delay", 5, 2, 20, 1,
        desc = "Ticks between each click (Hypixel needs time to respond between clicks).", unit = "t"
    )

    private enum class State { IDLE, LOADING, COMBINING }
    @Volatile private var state: State = State.IDLE

    /** Container ID the current async chain was scheduled against. */
    private var activeContainerId: Int = -1

    init {
        on<GuiEvent.Close> { reset() }

        on<TickEvent.Start> {
            if (state != State.IDLE) return@on
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!screen.title.string.contains("Anvil", ignoreCase = true)) return@on

            val menu = screen.menu
            val containerId = menu.containerId

            val slot22 = menu.slots.getOrNull(22)?.item
            val slot29 = menu.slots.getOrNull(29)?.item
            val slot33 = menu.slots.getOrNull(33)?.item

            // Two matching inputs already loaded + status lit -> combine + claim.
            if (slot22 != null && slot22.loreString?.contains("Click to combine!") == true
                && isEnchantedBook(slot29) && isEnchantedBook(slot33)
                && bookMatchKey(slot29) != null
                && bookMatchKey(slot29) == bookMatchKey(slot33)
            ) {
                state = State.COMBINING
                activeContainerId = containerId
                combineAndClaim()
                return@on
            }

            // Inputs empty -> try to load a fresh pair from the player inventory.
            if (slot29.empty && slot33.empty) {
                val (a, b) = findMatchingPair(menu) ?: return@on
                state = State.LOADING
                activeContainerId = containerId
                loadPair(a, b)
            }
        }
    }

    private fun reset() {
        state = State.IDLE
        activeContainerId = -1
    }

    private fun isEnchantedBook(stack: ItemStack?): Boolean = stack?.skyblockId == "ENCHANTED_BOOK"

    /** Stable key for a single-enchant book at lvl in 1..maxLevel; null otherwise. */
    private fun bookMatchKey(stack: ItemStack?): String? {
        if (!isEnchantedBook(stack)) return null
        val ench = stack!!.extraAttributes?.getCompound("enchantments")?.orElse(null) ?: return null
        val keys = ench.keySet()
        // Only single-enchantment books combine cleanly.
        if (keys.size != 1) return null
        val name = keys.first()
        val level = ench.getInt(name).orElse(0)
        if (level < 1 || level > maxLevel) return null
        return "$name:$level"
    }

    private fun findMatchingPair(menu: AbstractContainerMenu): Pair<Int, Int>? {
        val seen = HashMap<String, Int>()
        for (i in 54..89) {
            val key = bookMatchKey(menu.slots.getOrNull(i)?.item) ?: continue
            val prev = seen[key]
            if (prev != null) return prev to i
            seen[key] = i
        }
        return null
    }

    private fun loadPair(slotA: Int, slotB: Int) {
        val id = activeContainerId
        scheduleTask(delayTicks) {
            if (!stillOnSameAnvil(id)) return@scheduleTask
            mc.player?.clickSlot(slotA, id, button = 0, shift = true)
            scheduleTask(delayTicks) {
                if (stillOnSameAnvil(id)) {
                    mc.player?.clickSlot(slotB, id, button = 0, shift = true)
                }
                // Either way, drop back to IDLE — the next tick re-inspects the GUI
                // and either picks up "Click to combine!" or just waits for slot 22.
                state = State.IDLE
            }
        }
    }

    private fun combineAndClaim() {
        val id = activeContainerId
        scheduleTask(delayTicks) {
            if (!stillOnSameAnvil(id)) { state = State.IDLE; return@scheduleTask }
            mc.player?.clickSlot(22, id, button = 0, shift = false)   // combine
            scheduleTask(delayTicks) {
                if (stillOnSameAnvil(id)) {
                    mc.player?.clickSlot(22, id, button = 0, shift = false) // claim
                }
                state = State.IDLE
            }
        }
    }

    /** Cheap guard so a delayed click can't fire into a different/closed GUI. */
    private fun stillOnSameAnvil(expectedId: Int): Boolean {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return false
        if (screen.menu.containerId != expectedId) return false
        return screen.title.string.contains("Anvil", ignoreCase = true)
    }

    private val ItemStack?.empty: Boolean get() = this == null || this.isEmpty
}
