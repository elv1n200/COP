package cop.module.impl.player.cheats

import cop.api.events.PacketEvent
import cop.api.events.ServerEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Location
import cop.module.Module
import cop.module.settings.impl.KeybindComponent
import cop.utils.ChatUtils.modMessage
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bounded, movement-only blink. Unlike broad packet buffers it never delays
 * chat, inventory or keep-alive traffic and flushes before an interaction.
 */
object DefensiveBlink : Module(
    "Defensive Blink",
    desc = "Briefly buffers only movement packets, with a hard timeout and interaction-safe flush.",
) {
    private val duration by slider("Maximum duration", 250L, 50L, 500L, 25L, unit = "ms")
    private val autoVelocity by switch(
        "Auto on knockback",
        false,
        desc = "Starts a short blink when Hypixel applies velocity to your player.",
    )
    private val feedback by switch("Chat feedback", false)
    @Suppress("unused")
    private val blinkKey = register(
        KeybindComponent("Blink key", CatKeys.KEY_NONE, "Hold for a bounded manual blink.")
            .onPress { if (enabled) startBlink() }
            .onRelease { stopBlink(flush = true) },
    )

    private val queue = ConcurrentLinkedQueue<ServerboundMovePlayerPacket>()
    @Volatile private var blinking = false
    @Volatile private var flushing = false
    private var startedAt = 0L

    init {
        on<PacketEvent.ReceivedClient, ClientboundPlayerPositionPacket> {
            // Movement packets queued before an authoritative correction refer
            // to the old position. Never replay them after vanilla applies the
            // teleport, otherwise the server can immediately correct us again.
            stopBlink(flush = false)
        }

        on<PacketEvent.ReceivedClient, ClientboundPlayerRotationPacket> {
            // 26.1.2 may correct rotation without a position packet. Replaying
            // buffered Rot/PosRot packets afterward would immediately undo the
            // authoritative view correction on the server.
            stopBlink(flush = false)
        }

        on<PacketEvent.ReceivedClient, ClientboundSetEntityMotionPacket> {
            if (!autoVelocity || packet.id != player.id) return@on
            startBlink()
        }

        on<PacketEvent.Sent> {
            if (flushing || !blinking) return@on
            when (val outgoing = packet) {
                is ServerboundMovePlayerPacket -> {
                    if (queue.size >= MAX_PACKETS) {
                        stopBlink(flush = true)
                        return@on
                    }
                    queue.add(outgoing)
                    cancel()
                }

                is ServerboundInteractPacket,
                is ServerboundUseItemPacket,
                is ServerboundUseItemOnPacket,
                is ServerboundPlayerActionPacket -> stopBlink(flush = true)
            }
        }

        on<TickEvent.End> {
            if (blinking && System.currentTimeMillis() - startedAt >= duration) stopBlink(flush = true)
        }

        on<WorldEvent.Change> { stopBlink(flush = false) }
        on<ServerEvent.Disconnect> { stopBlink(flush = false) }
    }

    override fun onDisable() {
        stopBlink(flush = true)
        super.onDisable()
    }

    @Synchronized
    private fun startBlink() {
        if (blinking || flushing || mc.isSingleplayer || !Location.onHypixel || mc.screen != null) return
        if (!AutomationCoordinator.acquire(OWNER, duration + 250L, MOVEMENT)) return
        queue.clear()
        startedAt = System.currentTimeMillis()
        blinking = true
        if (feedback) modMessage("&eDefensive Blink started.", id = MESSAGE_ID)
    }

    @Synchronized
    private fun stopBlink(flush: Boolean) {
        if (!blinking && queue.isEmpty()) {
            AutomationCoordinator.release(OWNER)
            return
        }

        blinking = false
        if (flush) flushQueue() else queue.clear()
        AutomationCoordinator.release(OWNER)
        if (feedback) modMessage("&aDefensive Blink released.", id = MESSAGE_ID)
    }

    private fun flushQueue() {
        val connection = mc.connection
        if (connection == null) {
            queue.clear()
            return
        }

        flushing = true
        try {
            while (true) {
                val packet: Packet<*> = queue.poll() ?: break
                connection.send(packet)
            }
        } finally {
            flushing = false
            queue.clear()
        }
    }

    private const val OWNER = "Defensive Blink"
    private const val MAX_PACKETS = 48
    private const val MESSAGE_ID = 0x424C494E
}
