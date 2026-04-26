package cop.module.impl.misc

import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.ChatUtils.prefix
import cop.utils.WorldUtils
import cop.utils.skyblock.player.PlayerUtils.realName

object AntiNick : Module(
    "AntiNick",
    desc = "Detects nicked players."
) {
    init {
        on<WorldEvent.Load.End> {
            WorldUtils.players.forEach { player ->
                val gp = player.profile
                val real = gp.realName
                if (real != gp.name) {
                    val denicked = real?.let { "&a[DENICKED] $it" } ?: "&c[CANNOT DENICK]"
                    modMessage("${gp.name} &e->&r $denicked", prefix = prefix("AntiNick"))
                }
            }
        }
    }
}