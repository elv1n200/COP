package cop.module.impl.dungeon.huds

import cop.api.colour.Colour
import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ui.textPair
import java.util.Locale

/**
 * Port of CritsAddons `M3FFDisplay` (com.github.noamm9.critsaddons.features.impl.critsaddons.M3FFDisplay).
 *
 * M3's Professor has a brief Fire Freeze window — about 5s after the trigger line
 * "Oh? You found my Guardians' one weakness?". This HUD shows a live countdown until
 * you should fire your Fire Freeze Staff so you don't eyeball it.
 */
object M3FFDisplay : Module(
    "M3 FF Display",
    area = Island.Dungeon(3, inBoss = true),
    desc = "Shows a Fire Freeze countdown in M3 once the Professor's trigger line fires."
) {
    private const val PROFESSOR_FIRE_FREEZE_LINE =
        "[BOSS] The Professor: Oh? You found my Guardians' one weakness?"
    private const val FIRE_FREEZE_DELAY_MS = 5000L

    private var fireAtMs = 0L
    private var lastTriggerAtMs = 0L

    init {
        on<ChatEvent.Receive> {
            if (message != PROFESSOR_FIRE_FREEZE_LINE) return@on
            val now = System.currentTimeMillis()
            if (now - lastTriggerAtMs < 10_000L) return@on
            lastTriggerAtMs = now
            fireAtMs = now + FIRE_FREEZE_DELAY_MS
        }

        on<WorldEvent.Change> {
            fireAtMs = 0L
            lastTriggerAtMs = 0L
        }

        textHud(
            name = "M3 FF countdown",
            colour = Colour.CYAN,
            toggleable = false
        ) {
            visibleIf {
                if (preview) return@visibleIf true
                val fireAt = fireAtMs
                if (fireAt <= 0L) return@visibleIf false
                val remaining = fireAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    fireAtMs = 0L
                    return@visibleIf false
                }
                true
            }
            textPair(
                string = "M3 FF:",
                supplier = {
                    if (preview) "§e5.00s"
                    else {
                        val remaining = fireAtMs - System.currentTimeMillis()
                        "§e${String.format(Locale.US, "%.2f", (remaining.coerceAtLeast(0L)) / 1000.0)}s"
                    }
                },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()
    }
}
