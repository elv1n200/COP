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
 * Master Floor 3 Fire-Freeze HUD.
 *
 * The Professor at the end of M3 spawns a brief vulnerability window five
 * seconds after he says "Oh? You found my Guardians' one weakness?". Players
 * fire their Fire Freeze Staff into that window for max damage. Eyeballing
 * the gap is annoying — this HUD draws a live `M3 FF: 4.21s` countdown so
 * you can time the shot off a number instead.
 *
 * Re-implemented from scratch (May 2026). Behaviour identical to the previous
 * port; the state machine is simpler (a single `Phase` enum), the trigger
 * detection uses a substring match for resilience against Hypixel tweaking
 * the boss name prefix, and the formatter is forced to Locale.US so non-US
 * clients don't render "4,21s" with a comma.
 */
object M3FFDisplay : Module(
    "M3 FF Display",
    area = Island.Dungeon(3, inBoss = true),
    desc = "Counts down the ~5s window between the Professor's trigger line and the Fire Freeze opportunity in M3."
) {
    private const val WINDOW_MILLIS = 5_000L

    /** Substring on the boss line that triggers the countdown. We match by
     *  contains rather than equals so any leading prefix variation (server
     *  re-routes the line through "[BOSS] The Professor:" / "Boss:" / etc)
     *  doesn't break detection. The original port relied on the exact full
     *  line — brittle in practice. */
    private const val TRIGGER_PHRASE = "You found my Guardians' one weakness"

    /** Tiny state machine guarding against double-arm: the trigger line can
     *  arrive twice in pathological cases (chat re-render, packet replay).
     *  Only re-arm when the previous countdown has actually expired. */
    private enum class Phase { Idle, Armed }
    @Volatile private var phase = Phase.Idle
    private var fireAtMs = 0L

    init {
        on<ChatEvent.Receive> {
            if (phase != Phase.Idle) return@on
            if (TRIGGER_PHRASE !in message) return@on
            phase = Phase.Armed
            fireAtMs = System.currentTimeMillis() + WINDOW_MILLIS
        }

        on<WorldEvent.Change> {
            phase = Phase.Idle
            fireAtMs = 0L
        }

        textHud(
            name = "M3 FF countdown",
            colour = Colour.CYAN,
            toggleable = false,
        ) {
            visibleIf {
                if (preview) return@visibleIf true
                if (phase != Phase.Armed) return@visibleIf false
                val remaining = fireAtMs - System.currentTimeMillis()
                if (remaining > 0L) return@visibleIf true
                // Countdown ran out — return to Idle so the next trigger
                // line can re-arm. Hide the HUD this frame.
                phase = Phase.Idle
                false
            }
            textPair(
                string = "M3 FF:",
                supplier = {
                    val secondsRemaining = if (preview) {
                        WINDOW_MILLIS / 1000.0
                    } else {
                        (fireAtMs - System.currentTimeMillis())
                            .coerceAtLeast(0L) / 1000.0
                    }
                    "§e" + String.format(Locale.US, "%.2f", secondsRemaining) + "s"
                },
                labelColour = colour,
                shadow = shadow,
                font = font,
            )
        }.setting()
    }
}
