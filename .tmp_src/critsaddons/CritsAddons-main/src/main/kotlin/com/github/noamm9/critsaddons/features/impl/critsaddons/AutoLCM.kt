package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils
import kotlin.random.Random

object AutoLCM : Feature(
    name = "Auto LCM",
    description = "While you hold left click in dungeons as Mage, automatically triggers LCM clicks on a tick timer."
) {
    private val minClickDelayTicks by SliderSetting("Min Delay (ticks)", 4, 1, 20, 1)
        .section("timing")
        .withDescription("Minimum delay between auto LCM left clicks.")

    private val maxClickDelayTicks by SliderSetting("Max Delay (ticks)", 6, 1, 20, 1)
        .section("timing")
        .withDescription("Maximum delay between auto LCM left clicks.")

    private var ticksUntilNextClick = 0

    override fun init() {
        register<TickEvent.Start> {
            if (!shouldAutoLCM()) {
                ticksUntilNextClick = 0
                return@register
            }

            if (ticksUntilNextClick > 0) {
                ticksUntilNextClick--
                return@register
            }

            PlayerUtils.leftClick()
            ticksUntilNextClick = nextDelayTicks()
        }
    }

    override fun onDisable() {
        super.onDisable()
        ticksUntilNextClick = 0
    }

    private fun shouldAutoLCM(): Boolean {
        if (!enabled) return false
        if (!LocationUtils.inDungeon) return false
        if (mc.screen != null) return false
        if (mc.player == null) return false
        if (!mc.options.keyAttack.isDown) return false

        val self = DungeonListener.thePlayer ?: return false
        return self.clazz == DungeonClass.Mage
    }

    private fun nextDelayTicks(): Int {
        val min = minClickDelayTicks.value
        val max = maxClickDelayTicks.value
        return if (min >= max) min else Random.nextInt(min, max + 1)
    }
}
