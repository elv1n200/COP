package cop.module.impl.dungeon.cheats

import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.module.Module
import cop.utils.skyblock.player.PlayerUtils.leftClick
import kotlin.random.Random

object AutoLCM : Module(
    "Auto LCM",
    desc = "Repeats the Mage left-click attack while the attack button is held in a dungeon."
) {
    private val attackInterval by rangeSlider(
        "Attack interval",
        2 to 4,
        1,
        20,
        1,
        unit = "t",
        desc = "Random client-tick interval between automatic Mage attacks."
    )

    private var ticksUntilAttack = 0

    init {
        on<TickEvent.Start> {
            val localPlayer = mc.player
            if (localPlayer == null || !canAttack() || !mc.options.keyAttack.isDown) {
                resetAttackTimer()
                return@on
            }

            if (ticksUntilAttack > 0 && --ticksUntilAttack > 0) return@on

            localPlayer.leftClick()
            ticksUntilAttack = nextInterval()
        }

        on<WorldEvent.Change> { resetAttackTimer() }
    }

    override fun onDisable() {
        resetAttackTimer()
        super.onDisable()
    }

    private fun canAttack(): Boolean =
        mc.screen == null &&
            Dungeon.inDungeons &&
            !Dungeon.isDead &&
            Dungeon.currentDungeonPlayer.clazz == DungeonClass.Mage

    private fun nextInterval(): Int {
        val low = attackInterval.first.coerceAtLeast(1)
        val high = attackInterval.second.coerceAtLeast(low)
        return if (low == high) low else Random.nextInt(low, high + 1)
    }

    private fun resetAttackTimer() {
        ticksUntilAttack = 0
    }
}
