package cop.api.skyblock

import cop.CopMod.mc
import cop.api.events.AreaEvent
import cop.api.events.core.EventBus
import cop.api.events.PacketEvent
import cop.api.events.ServerEvent
import cop.api.events.WorldEvent
import cop.api.events.core.Priority
import cop.utils.StringUtils.noControlCodes
import cop.utils.equalsOneOf
import cop.utils.StringUtils.startsWithOneOf
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import cop.annotations.Init
import cop.module.impl.render.ClickGui

/**
 * modified OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/skyblock/LocationUtils.kt
 */
@Init
object Location {
    var onHypixel: Boolean = false
        private set
    var inSkyblock: Boolean = false
        private set
    var currentArea: Island = Island.Unknown
        private set
    var subarea: String? = null
        private set
    var currentServer: String? = null
        private set
    var previousServer: String? = null
        private set

    val onModernIsland: Boolean get() = currentArea.equalsOneOf(Island.ThePark, Island.Galatea, Island.Hub, Island.SpiderDen)

    fun isHypixelAddress(address: String): Boolean {
        val host = address.trim().substringBefore(':').trimEnd('.').lowercase()
        return host == "hypixel.net" || host.endsWith(".hypixel.net")
    }

    private val teamRegex = Regex("^team_(\\d+)$")
    private val subAreaRegex = Regex("^ ([⏣ф]) .*")
    private val serverIdRegex = Regex("\\d\\d/\\d\\d/\\d\\d (\\w{0,6}) *")

    init {
        EventBus.on<PacketEvent.ReceivedClient> {
            when (packet) {
                is ClientboundPlayerInfoUpdatePacket -> {
                    if (!currentArea.isArea(Island.Unknown) || packet.actions()
                            .none { it.equalsOneOf(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,) }
                    ) return@on
                    val area = packet.entries().find {
                        it.displayName?.string?.startsWithOneOf(
                            "Area: ",
                            "Dungeon: "
                        ) == true
                    }?.displayName?.string ?: return@on
                    val newArea = Island.entries.firstOrNull { area.contains(it.displayName, true) } ?: Island.Unknown
                    if (newArea !== currentArea) {
                        currentArea = newArea
                        AreaEvent.Main(newArea).post()
                    }
                }

                is ClientboundSetObjectivePacket ->
                    if (!inSkyblock) inSkyblock = onHypixel && packet.objectiveName == "SBScoreboard" || ClickGui.forceSkyblock

                is ClientboundSetPlayerTeamPacket -> {
                    val team = packet.parameters.orElse(null) ?: return@on
                    val text = team.playerPrefix.string.noControlCodes + team.playerSuffix.string.noControlCodes

                    if (packet.name.matches(teamRegex) && text.matches(subAreaRegex) && text.lowercase() != subarea) {
                        subarea = text.lowercase()
                        AreaEvent.Sub(text).post()
                    }

                if (currentArea == Island.Unknown) serverIdRegex.find(text)?.groupValues?.getOrNull(1)?.let {
                        if (currentServer != it) {
                            previousServer = currentServer
                            currentServer = it
                        }
                    }
                }
            }
        }

        EventBus.on<WorldEvent.Change>(Priority.LOW) {
            currentArea = Island.Unknown
            inSkyblock = ClickGui.forceSkyblock
            AreaEvent.Main(currentArea).post()

            if (subarea !== null) {
                AreaEvent.Sub(null).post()
                subarea = null
            }
        }

        EventBus.on<ServerEvent.Connect> {
            if (mc.isSingleplayer) {
                currentArea = Island.SinglePlayer
                return@on
            }
            onHypixel = mc.runCatching { isHypixelAddress(ip) }.getOrDefault(false)
        }

        EventBus.on<ServerEvent.Disconnect> {
            currentArea = Island.Unknown
            subarea = null
            inSkyblock = false
            onHypixel = false
        }
    }
}
