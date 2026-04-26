package cop.module.impl.dungeon

import cop.api.events.TickEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.currentDungeonPlayer
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.skyblock.player.PlayerUtils.leftClick
import kotlin.random.Random

/**
 * Port of CritsAddons `AutoLCM` (com.github.noamm9.critsaddons.features.impl.critsaddons.AutoLCM).
 *
 * While you hold LMB as a Mage in a dungeon, this triggers automatic LCM (Left-Click Mage)
 * clicks on a randomised tick cadence so your staff procs fire without your finger cramping.
 * Only runs when:
 *   - you're a Mage,
 *   - holding the attack key,
 *   - no screen is open,
 *   - you're inside a dungeon.
 */
object AutoLCM : Module(
    "Auto LCM",
    area = Island.Dungeon,
    desc = "Holds LCM for you: auto-clicks LMB on a random tick cadence while you hold attack as Mage."
) {
    private val minClickDelayTicks by slider("Min delay", 4, 1, 20, 1, unit = "t",
        desc = "Minimum ticks between auto LCM clicks.")
    private val maxClickDelayTicks by slider("Max delay", 6, 1, 20, 1, unit = "t",
        desc = "Maximum ticks between auto LCM clicks.")

    private var ticksUntilNextClick = 0

    init {
        on<TickEvent.End> {
            if (!shouldAutoLCM()) {
                ticksUntilNextClick = 0
                return@on
            }

            if (ticksUntilNextClick > 0) {
                ticksUntilNextClick--
                return@on
            }

            mc.player?.leftClick()
            ticksUntilNextClick = nextDelayTicks()
        }
    }

    private fun shouldAutoLCM(): Boolean {
        if (mc.screen != null) return false
        val player = mc.player ?: return false
        if (!mc.options.keyAttack.isDown) return false
        return currentDungeonPlayer.clazz == DungeonClass.Mage
    }

    private fun nextDelayTicks(): Int {
        val min = minClickDelayTicks
        val max = maxClickDelayTicks
        return if (min >= max) min else Random.nextInt(min, max + 1)
    }
}
