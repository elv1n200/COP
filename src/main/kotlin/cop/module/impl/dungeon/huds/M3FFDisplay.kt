package cop.module.impl.dungeon.huds

import cop.api.abobaui.dsl.px
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Floor
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import java.util.Locale

object M3FFDisplay : Module(
    "M3 Fire Freeze Display",
    area = Island.Dungeon(3, inBoss = true),
    desc = "Counts down the Professor's Fire Freeze cast window in M3.",
) {
    private val showLabel by switch("Show label", true)
    private val decimalPlaces by slider(
        "Decimal places",
        2,
        0,
        2,
        1,
        desc = "Number of decimal places shown on the timer.",
    )
    private val castWindowSeconds by slider(
        "Cast window",
        5.0,
        4.0,
        6.0,
        0.05,
        unit = "s",
        desc = "Countdown length after the Professor trigger line.",
    )

    @Volatile
    private var deadlineNanos = 0L
    private var lastTriggerNanos = 0L

    @Suppress("unused")
    private val timerHud by textHud("M3 Fire Freeze") {
        visibleIf { preview || remainingNanos() > 0L }
        val timer = textSupplied(
            supplier = { formatTimer(if (preview) previewRemainingNanos() else remainingNanos()) },
            font = font,
            size = 18.px,
            colour = colour,
        )
        timer.shadow = shadow
    }.withSettings(::showLabel, ::decimalPlaces, ::castWindowSeconds).setting()

    init {
        on<ChatEvent.Receive> {
            if (Dungeon.floor != Floor.M3) return@on
            if (message.noControlCodes.trim() != TRIGGER_MESSAGE) return@on

            val now = System.nanoTime()
            if (lastTriggerNanos != 0L && now - lastTriggerNanos < TRIGGER_DEBOUNCE_NANOS) return@on
            lastTriggerNanos = now
            deadlineNanos = now + (castWindowSeconds * NANOS_PER_SECOND).toLong()
        }

        on<WorldEvent.Change> {
            resetTimer()
        }
    }

    override fun onDisable() {
        resetTimer()
        super.onDisable()
    }

    private fun remainingNanos(now: Long = System.nanoTime()): Long =
        (deadlineNanos - now).coerceAtLeast(0L)

    private fun previewRemainingNanos(): Long =
        (castWindowSeconds * NANOS_PER_SECOND * 0.64).toLong()

    private fun formatTimer(remainingNanos: Long): String {
        val totalNanos = (castWindowSeconds * NANOS_PER_SECOND).coerceAtLeast(1.0)
        val fraction = remainingNanos / totalNanos
        val timerColour = when {
            fraction > 2.0 / 3.0 -> "§a"
            fraction > 1.0 / 3.0 -> "§e"
            else -> "§c"
        }
        val seconds = remainingNanos / NANOS_PER_SECOND
        val value = String.format(Locale.ROOT, "%.${decimalPlaces}f", seconds)
        val label = if (showLabel) "Fire Freeze: " else ""
        return "$label$timerColour${value}s"
    }

    private fun resetTimer() {
        deadlineNanos = 0L
        lastTriggerNanos = 0L
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val TRIGGER_DEBOUNCE_NANOS = 10_000_000_000L

    private const val TRIGGER_MESSAGE =
        "[BOSS] The Professor: Even if you took my barrier down, I can still fight."
}
