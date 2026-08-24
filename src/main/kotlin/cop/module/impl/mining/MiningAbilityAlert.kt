package cop.module.impl.mining

import cop.api.events.ChatEvent
import cop.api.skyblock.IslandArea
import cop.module.Module
import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.player.PlayerUtils
import net.minecraft.sounds.SoundEvents

/** Local mining-ability readiness alert, independently implemented from the
 * behaviour exposed by Quoi 26.1.x `AbilityAlert`. */
object MiningAbilityAlert : Module(
    "Mining Ability Alert",
    area = IslandArea.MiningIslands,
    desc = "Shows a title and sound when a mining ability becomes available again.",
) {
    private val playSound by switch("Play sound", true)
    private val duration by slider("Title duration", 50, 10, 120, 5, unit = "t")

    init {
        on<ChatEvent.PacketClient> {
            val formatted = text.formattedString
            if (!formatted.startsWith("§6") || !formatted.endsWith("§ais now available!")) return@on

            val ability = message.removeSuffix(" is now available!").trim()
            if (ability.isEmpty() || ability.length > 64 || !ABILITY_NAME.matches(ability)) return@on

            PlayerUtils.setTitle(
                title = "§6${ability.uppercase()}",
                subtitle = "§aREADY",
                playSound = playSound,
                sound = SoundEvents.EXPERIENCE_ORB_PICKUP,
                volume = 1f,
                pitch = 1f,
                stayAlive = duration,
                fadeOut = 10,
            )
        }
    }

    private val ABILITY_NAME = Regex("""[A-Za-z0-9 '\-]{1,64}""")
}
