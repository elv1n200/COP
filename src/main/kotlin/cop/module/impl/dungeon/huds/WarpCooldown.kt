package cop.module.impl.dungeon.huds

import cop.api.events.ChatEvent
import cop.api.events.ServerEvent
import cop.api.skyblock.Location
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.formatTime
import cop.utils.StringUtils.noControlCodes
import cop.utils.ui.textPair

/** Passive dungeon-instance cooldown HUD. Behavioural references were Quoi
 * 26.1.x and NoammAddons 26.1.2; timer/parser/command guard are COP code. */
object WarpCooldown : Module(
    "Warp Cooldown",
    desc = "Shows the 30-second dungeon instance warp cooldown.",
) {
    private val blockInstanceCommands by switch(
        "Block early instance commands", true,
        desc = "Prevents /joininstance while the local cooldown is still active.",
    )

    private val timer = CooldownTimer(COOLDOWN_MILLIS)

    @Suppress("unused")
    private val cooldownHud by textHud("Warp cooldown", toggleable = false) {
        visibleIf { preview || remainingMillis() > 0L }
        textPair(
            string = "Warp:",
            supplier = {
                if (preview) "§e24.3s" else "§e${formatRemaining(remainingMillis())}"
            },
            labelColour = colour,
            shadow = shadow,
            font = font,
        )
    }.setting()

    init {
        on<ChatEvent.PacketClient> {
            if (!Location.onHypixel) return@on
            if (DungeonEntryParser.isEntryMessage(message.noControlCodes)) {
                timer.start(System.currentTimeMillis())
            }
        }

        on<ChatEvent.Sent> {
            if (!blockInstanceCommands || !isCommand || remainingMillis() == 0L) return@on
            val commandName = message.trimStart().removePrefix("/").substringBefore(' ')
            if (!commandName.equals("joininstance", ignoreCase = true)) return@on

            cancel()
            modMessage("&cDungeon warp is still on cooldown for &e${formatRemaining(remainingMillis())}&c.")
        }

        on<ServerEvent.Disconnect> { timer.clear() }
    }

    private fun remainingMillis(): Long = timer.remaining(System.currentTimeMillis())

    private fun formatRemaining(remaining: Long): String = formatTime(
        time = remaining,
        decimalPlaces = 1,
        showDays = false,
        showHours = false,
        showMinutes = false,
        showSeconds = true,
    )

    private const val COOLDOWN_MILLIS = 30_000L
}
