package cop.api.commands

import cop.CopMod.mc
import cop.api.commands.internal.BaseCommand
import cop.api.commands.internal.GreedyString
import cop.api.events.core.EventBus
import cop.api.skyblock.Island
import cop.api.skyblock.Location
import cop.api.skyblock.Location.currentArea
import cop.api.skyblock.Location.currentServer
import cop.api.skyblock.Location.inSkyblock
import cop.api.skyblock.Location.subarea
import cop.api.skyblock.dungeon.Dungeon
//import cop.api.skyblock.dungeon.Dungeon.uniqueRooms
//import cop.api.skyblock.dungeon.map.utils.ScanUtils.currentRoom
import cop.module.ModuleManager
import cop.module.impl.misc.Chat
import cop.module.impl.render.ClickGui.clickGui
import cop.utils.ChatUtils.command
import cop.utils.ChatUtils.literal
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleLoop
import cop.utils.WorldUtils
import cop.utils.WorldUtils.day
import cop.utils.skyblock.player.MovementUtils.hold
import cop.utils.skyblock.player.MovementUtils.isMoving
import cop.utils.ticker
import cop.utils.ui.hud.HudManager
import cop.utils.ui.screens.UIScreen.Companion.open
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import cop.api.skyblock.dungeon.Dungeon.currentRoom
import cop.utils.StringUtils.capitaliseFirst
import cop.utils.addVec
import cop.utils.skyblock.player.RotationUtils.rotate
import kotlin.collections.sortedBy

object CopCommand {
    val command = BaseCommand("cop", "cope") {
        open(clickGui)
    }

    val devCommand = BaseCommand("copdev")

    private fun warpTicker(cmd: String) = ticker {
        action { command("warp $cmd") }
        action(80) { command("warp hub") }
        delay(80)
    }

    private fun antiAfkTicker(delay: Int) = ticker {
        action { mc.options.keyLeft.hold(1) }
        action(delay) { mc.options.keyRight.hold(1) }
        delay(delay)
    }

    init {

        with(devCommand) {
            "copy" { string: GreedyString ->
                mc.keyboardHandler.clipboard = string.string
            }

            // Phase-1 sanity check for the Auto Croesus price client.
            // Usage: /copdev pricetest <ITEM_ID or display name>
            "pricetest" { query: GreedyString ->
                val raw = query.string.trim()
                if (raw.isEmpty()) {
                    modMessage("&cUsage: /copdev pricetest <ITEM_ID or display name>")
                    return@invoke
                }
                val pc = cop.utils.skyblock.PriceClient
                val ageLabel = if (pc.isLoaded) "${pc.ageMs / 1000}s" else "never"
                modMessage("&7Fetching prices (cache age=$ageLabel)...")
                pc.refreshIfStale {
                    mc.execute {
                        val id = pc.resolveItemId(raw) ?: raw.uppercase().replace(' ', '_')
                        val bz = pc.getBazaarSell(id)
                        val lb = pc.getLowestBin(id)
                        val best = pc.getPrice(id)
                        // Partial successes still populate the cache; surface
                        // any per-source failure but don't suppress the result.
                        pc.lastError?.let { modMessage("&eFetch issues: &f$it") }
                        modMessage(
                            "&bPrice for &f$id&7:" +
                                "\n  &7bazaar sell: " + (bz?.let { "&a$it" } ?: "&8n/a") +
                                "\n  &7lowest BIN:  " + (lb?.let { "&a$it" } ?: "&8n/a") +
                                "\n  &7best:        " + (best?.let { "&e$it" } ?: "&cunknown")
                        )
                    }
                }
            }

            "simulate" { message: GreedyString ->
                EventBus.onPacketReceived(ClientboundSystemChatPacket(literal(message.string), false))
                modMessage("simulated: ${message.string}")
            }

            "currentroom" {
                currentRoom?.let { room ->
                    val player = mc.player!!
                    val currentComp = room.roomComponents.minByOrNull { comp ->
                        val dx = player.x - comp.x
                        val dz = player.z - comp.z
                        dx * dx + dz * dz
                    }

                    val componentsString = room.roomComponents.mapIndexed { index, comp ->
                        val curr = if (comp == currentComp) "&a->&f" else "   "
                        "$curr &7$index: ${comp.vec2} &7| &f${comp.core}"
                    }.joinToString("\n")


                    val msg = listOf(
                        "&e${room.data.name} &7(${room.data.type})",
                        "&7|&fState: &7${room.data.state}",
                        "&7|&fCorner: &7${room.clayPos.x}, ${room.clayPos.y}, ${room.clayPos.z}",
                        "&7|&fRotation: &7${room.rotation} (${room.rotation.deg})",
                        "&7|&fComponents:",
                        componentsString
                    ).joinToString("\n")

                    modMessage(msg, prefix = "")
                }
            }

            "relative" {
                mc.hitResult?.let {
                    if (it !is BlockHitResult) return@let
                    currentRoom?.getRelativeCoords(it.blockPos)?.let { vec2 ->
                        modMessage("Relative coords: ${vec2.x}, ${vec2.z}")
                    }
                    currentRoom?.getRelativeCoords(Vec3(it.blockPos))?.let { vec2 ->
                        modMessage("Relative coords: ${vec2.x}, ${vec2.z}")
                    }

                }
            }

//            "rooms" {
//                modMessage("Rooms: ${uniqueRooms.joinToString(", ") { it.name }}")
//            }

            "area" {
                modMessage("Area: $currentArea, Sub: $subarea, Server: $currentServer, Floor: ${Dungeon.floor?.name}")
            }

            "featurelist" { md: Boolean? ->
                val featureList = StringBuilder()

                for ((category, modulesInCategory) in ModuleManager.modules.groupBy { it.category }.entries) {
                    val categoryName = category.name.capitaliseFirst()

                    if (md == true) {
                        featureList.appendLine("<details>")
                        featureList.appendLine("<summary><b>$categoryName</b></summary>")
                        featureList.appendLine()
                    } else {
                        featureList.appendLine("# $categoryName")
                    }

                    for (module in modulesInCategory.sortedBy { it.name }) {
                        featureList.appendLine("- **${module.name}**")
                        if (module.desc.isNotEmpty()) featureList.appendLine("  - ${module.desc}")
                    }

                    if (md == true) {
                        featureList.appendLine()
                        featureList.appendLine("</details>")
                    }

                    featureList.appendLine()
                }

                mc.keyboardHandler.clipboard = featureList.toString()
            }

            "centre" {
                with(mc.player) {
                    this?.setPos(this.blockPosition().center.addVec(y = -0.5))
                }
            }

            "rotate" { yaw: Float, pitch: Float ->
                mc.player?.rotate(yaw, pitch)
            }
        }

        with(command) {
            "toggle" { moduleName: GreedyString ->
                val module = ModuleManager.getModuleByName(moduleName.string)
                module?.apply {
                    toggle()
                    toggleMessage()
                } ?: modMessage("Unknown module name: ${moduleName.string}")
            }.suggests { ModuleManager.modules.map { it.name } }.description("Toggles specified module.")

            "hud" { open(HudManager.editor()) }.description("Opens Hud editor.")
        }

        command.sub("findlobby") { area: String, criteria: String, value: String ->
            val island = Island.entries
                .firstOrNull { it.command != null && it.displayName.equals(area.replace("_", " "), true) }
                ?: return@sub modMessage("&cIncorrect area!")

            if (criteria !in setOf("day", "server", "player")) return@sub modMessage("&cInvalid criteria!")

            val intValue = if (criteria == "day") value.toIntOrNull()
                ?: return@sub modMessage("&cInvalid day number!") else null

            fun isMet(): Boolean = when (criteria) {
                "day" -> mc.level!!.day <= intValue!!
                "server" -> Location.currentServer.equals(value, true)
                "player" -> WorldUtils.players.any { it.profile.name.equals(value, true) }
                else -> false
            }

            var ticker = warpTicker(island.command!!)

            modMessage("Starting to look for $criteria $value")

            scheduleLoop {
                if (mc.player!!.isMoving) {
                    modMessage("Cancelling, you moved!")
                    it.cancel()
                    return@scheduleLoop
                }

                if (isMet() && currentArea.isArea(island)) {
                    modMessage("Found")
                    it.cancel()
                    return@scheduleLoop
                }

                if (ticker.tick()) ticker = warpTicker(island.command)
            }
        }.description("Finds lobby with specified criteria.")
        .requires("&cYou are not in skyblock!") { inSkyblock }
        .suggests("area") { Island.entries.filter { it.command != null }.map { it.displayName.replace(" ", "_") } }
        .suggests("criteria", "day", "server", "player")

        command.sub("antiafk") { delay: Int ->
            if (delay < 20) return@sub modMessage("&cThe delay is too low!")
            val headRot = mc.player!!.yHeadRot
            modMessage("Starting. Move your camera to cancel")

            var ticker = antiAfkTicker(delay)
            scheduleLoop {
                if (mc.player!!.yHeadRot != headRot) {
                    modMessage("Cancelling, you moved your camera!")
                    it.cancel()
                    return@scheduleLoop
                }

                if (ticker.tick()) ticker = antiAfkTicker(delay)
            }
        }.description("Prevents afk kick.").suggests("delay", "40")
    }

    fun init() {
        command.register()
        devCommand.register()

        BaseCommand("clearchat") { mc.gui.chat.clearMessages(false); Chat.chatList.clear() }.register()

        Floors.entries.forEach { floor ->
            BaseCommand(floor.name.lowercase()) {
                command("joininstance ${floor.instance()}")
            }.requires("&cYou are not in skyblock!") { inSkyblock }.register()
        }
    }

    private enum class Floors {
        F0,
        F1, F2, F3, F4, F5, F6, F7,
        M1, M2, M3, M4, M5, M6, M7;

        private val floors = listOf("one", "two", "three", "four", "five", "six", "seven")

        fun instance(): String {
            if (this == F0) return "catacombs_entrance"

            val adj = ordinal - 1

            return "${if (adj > 6) "master_" else ""}catacombs_floor_${floors[adj % 7]}"
        }
    }
}