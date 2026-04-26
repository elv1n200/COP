package cop.module.impl.dungeon

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.BlockHitResult
import cop.api.events.MouseEvent
import cop.api.skyblock.Island
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.Scheduler
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.leftClick

/**
 * Port of Nebulune `AutoSuperboom` (xyz.aerii.nebulune.modules.impl.dungeons.AutoSuperboom).
 *
 * When you LMB a breakable wall (default: cracked stone bricks + barrier blocks, with
 * optional extras for stained clay and basement crypts) while Superboom TNT is in your
 * hotbar, the module:
 *   1. Cancels the vanilla LMB so you don't wind up punching the wall
 *   2. Swaps to the SB TNT slot
 *   3. Left-clicks (throws the TNT)
 *   4. (Optionally) swaps back to the original slot after a randomised delay
 *
 * The delays are in ticks and accept ranges (min..max.random()) to feel less robotic.
 */
object AutoSuperboom : Module(
    "Auto Superboom",
    area = Island.Dungeon,
    desc = "Auto-swaps to Superboom TNT and detonates breakable walls when you LMB them."
) {
    private val minDelay by slider("Min delay", 1, 1, 5, 1, unit = "t",
        desc = "Lower bound for swap-to-TNT delay.")
    private val maxDelay by slider("Max delay", 3, 1, 5, 1, unit = "t",
        desc = "Upper bound for swap-to-TNT delay.")

    private val swapBack by switch("Swap back", true,
        desc = "Return to your original slot after throwing.")
    private val swapBackMin by slider("Swap-back min", 1, 1, 5, 1, unit = "t").childOf(::swapBack)
    private val swapBackMax by slider("Swap-back max", 3, 1, 5, 1, unit = "t").childOf(::swapBack)

    private val allowCrackedBricks by switch("Cracked stone bricks", true,
        desc = "F7 crypt walls & similar (minecraft:cracked_stone_bricks).")
    private val allowBarriers by switch("Barrier blocks", true,
        desc = "Hypixel hidden walls (minecraft:barrier).")
    private val allowStainedClay by switch("Stained clay",
        desc = "Crypt variant walls (minecraft:*_terracotta).")
    private val allowNetherBricks by switch("Nether bricks",
        desc = "Some M7 rooms (minecraft:nether_bricks).")

    private val superboomIds = setOf("SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT")

    private fun isBreakable(blockId: String): Boolean = when {
        allowCrackedBricks && blockId == "minecraft:cracked_stone_bricks" -> true
        allowBarriers && blockId == "minecraft:barrier" -> true
        allowStainedClay && blockId.endsWith("_terracotta") -> true
        allowNetherBricks && blockId == "minecraft:nether_bricks" -> true
        else -> false
    }

    private fun findSuperboomSlot(): Int? {
        val player = mc.player ?: return null
        for (i in 0..8) {
            val id = player.inventory.getItem(i).skyblockId ?: continue
            if (id in superboomIds) return i
        }
        return null
    }

    init {
        on<MouseEvent.Click> {
            if (button != 0 || !state) return@on           // LMB press only
            if (mc.screen != null) return@on

            val player = mc.player ?: return@on
            val hit = mc.hitResult as? BlockHitResult ?: return@on
            val level = mc.level ?: return@on

            val block = level.getBlockState(hit.blockPos).block
            val blockId = BuiltInRegistries.BLOCK.getKey(block).toString()
            if (!isBreakable(blockId)) return@on

            val originalSlot = player.inventory.selectedSlot
            val tntSlot = findSuperboomSlot()?.takeIf { it != originalSlot } ?: return@on

            cancel()

            val swapDelay = (minDelay..maxDelay.coerceAtLeast(minDelay)).random()
            Scheduler.scheduleTask(swapDelay) {
                val p = mc.player ?: return@scheduleTask
                p.inventory.selectedSlot = tntSlot

                // one tick later → actually throw
                Scheduler.scheduleTask(1) {
                    (mc.player ?: return@scheduleTask).leftClick()

                    if (!swapBack) return@scheduleTask

                    val backDelay = (swapBackMin..swapBackMax.coerceAtLeast(swapBackMin)).random()
                    Scheduler.scheduleTask(backDelay) {
                        (mc.player ?: return@scheduleTask).inventory.selectedSlot = originalSlot
                    }
                }
            }
        }
    }
}
