package cop.module.impl.player

import cop.api.events.TickEvent
import cop.module.Module

object AutoSprint : Module(
    "Auto Sprint",
    desc = "Automatically sprints."
) {
    init {
        on<TickEvent.End> {
            if (player.isInWater || player.isUnderWater) return@on
            mc.options.keySprint.isDown = true
        }
    }
}