package cop.module.impl.misc.automation

import cop.api.events.ServerEvent
import cop.api.events.TickEvent
import cop.api.skyblock.Location
import cop.api.skyblock.SkyblockPlayer
import cop.module.Module
import cop.utils.ChatUtils

object AutoJoinSkyBlock : Module(
    "Auto Join SkyBlock",
    desc = "Automatically joins SkyBlock after connecting to Hypixel.",
) {
    private val waitTicks by slider(
        "Join delay",
        30,
        10,
        100,
        5,
        unit = "t",
        desc = "Waits for the lobby and command connection to finish loading.",
    )

    private var armed = false
    private var ticks = 0
    private var retryTicks = 0
    private var attempts = 0

    init {
        on<ServerEvent.Connect> {
            armed = true
            ticks = 0
            retryTicks = 0
            attempts = 0
        }

        on<ServerEvent.Disconnect> { reset() }

        on<TickEvent.End> {
            if (!armed || !Location.onHypixel) return@on
            if (Location.inSkyblock) {
                reset()
                return@on
            }
            if (!SkyblockPlayer.canUseCommands) return@on
            if (++ticks < waitTicks) return@on
            if (retryTicks > 0) {
                retryTicks--
                return@on
            }

            ChatUtils.command("skyblock")
            attempts++
            if (attempts >= MAX_ATTEMPTS) reset()
            else retryTicks = RETRY_DELAY_TICKS
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun reset() {
        armed = false
        ticks = 0
        retryTicks = 0
        attempts = 0
    }

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_TICKS = 80
}
