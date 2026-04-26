package cop.module.impl.player

import cop.api.colour.Colour
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.ui.textPair

/**
 * Port of Athen `LagDetector` (xyz.aerii.athen.modules.impl.general.LagDetector).
 * Shows a HUD timer that displays how many milliseconds have elapsed since the
 * last server tick once a user-configurable threshold is exceeded.
 */
object LagDetector : Module(
    "Lag Detector",
    desc = "Displays a timer since the last server tick if it exceeds the threshold."
) {
    private val threshold by slider("Threshold", 750, 100, 5000, 50, unit = "ms")

    private var lastTick = 0L

    init {
        on<WorldEvent.Change> { lastTick = 0L }

        on<TickEvent.Server> {
            lastTick = System.currentTimeMillis()
        }

        textHud(
            name = "Lag display",
            colour = Colour.RED,
            toggleable = false
        ) {
            visibleIf {
                if (preview) return@visibleIf true
                if (lastTick == 0L) return@visibleIf false
                System.currentTimeMillis() - lastTick > threshold
            }
            textPair(
                string = "Lag:",
                supplier = {
                    if (preview) "§c67ms"
                    else "§c${System.currentTimeMillis() - lastTick}ms"
                },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()
    }
}
