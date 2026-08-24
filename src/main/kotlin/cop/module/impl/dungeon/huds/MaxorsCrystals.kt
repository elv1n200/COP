package cop.module.impl.dungeon.huds

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.EntityType
import cop.api.colour.Colour
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.M7Phases
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.ui.textPair
import kotlin.math.hypot

/**
 * Port of NoammAddons MaxorsCrystals (com.github.noamm9.features.impl.floor7.MaxorsCrystals).
 * - Spawn timer shows when a picked-up Maxor crystal should respawn (34 server ticks after Maxor stun).
 * - Place timer reports how long it took you to place the crystal.
 * - Place alert warns if you still have an unplaced crystal in slot 9 during P1.
 */
object MaxorsCrystals : Module(
    "Maxor's Crystals",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Utilities for Maxor's Energy Crystals (spawn timer, place timer, unplaced warning)."
) {
    private val spawnTimer by switch("Spawn Timer", true,
        desc = "Shows an on-screen tick timer for when the crystal will respawn.")
    private val placeTimer by switch("Place Timer", true,
        desc = "Sends a chat message showing how long it took to place the crystal.")
    private val placeAlert by switch("Place Alert", true,
        desc = "Warns when you still have an unplaced Energy Crystal in hotbar slot 9.")

    private val pickupRegex = Regex("^(\\w+) picked up an Energy Crystal!")

    private var pickupTime: Long? = null
    private var tickTimer: Int = 0

    init {
        on<WorldEvent.Change> {
            pickupTime = null
            tickTimer = 0
        }

        on<ChatEvent.Receive> {
            val msg = message.noControlCodes
            if (placeTimer) pickupRegex.find(msg)?.let {
                if (it.groupValues[1] != mc.user.name) return@let
                pickupTime = System.currentTimeMillis()
            }
            if (spawnTimer && (msg == "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!" ||
                        msg == "[BOSS] Maxor: YOU TRICKED ME!")) {
                tickTimer = 34
            }
        }

        on<PacketEvent.ReceivedClient, ClientboundAddEntityPacket> {
            val pt = pickupTime ?: return@on
            if (!placeTimer) return@on
            if (packet.type != EntityType.END_CRYSTAL) return@on
            if (packet.y.toInt() != 224) return@on
            val player = mc.player ?: return@on

            val dx = packet.x - player.x
            val dz = packet.z - player.z
            if (hypot(dx, dz) >= 5.0) return@on

            val seconds = (System.currentTimeMillis() - pt) / 1000.0
            modMessage("&aCrystal placed in &e%.3fs&a.".format(seconds))
            pickupTime = null
        }

        on<TickEvent.Server> {
            if (tickTimer > 0) tickTimer--
        }

        textHud(
            name = "Maxor Crystal Timer",
            colour = Colour.CYAN,
            toggleable = false
        ) {
            visibleIf { this@MaxorsCrystals.enabled && spawnTimer && tickTimer > 0 }
            textPair(
                string = "Crystal:",
                supplier = { "%.2fs".format(tickTimer / 20.0) },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()

        textHud(
            name = "Maxor Crystal Warning",
            colour = Colour.YELLOW,
            toggleable = false
        ) {
            visibleIf {
                this@MaxorsCrystals.enabled && placeAlert && Dungeon.getF7Phase() == M7Phases.P1 &&
                    (mc.player?.inventory?.getItem(8)?.hoverName?.string?.noControlCodes
                        ?.equals("energy crystal", ignoreCase = true) == true)
            }
            textPair(
                string = "§e⚠",
                supplier = { "§bCrystal §e⚠" },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()
    }
}
