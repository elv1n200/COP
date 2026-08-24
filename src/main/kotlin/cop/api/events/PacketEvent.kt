package cop.api.events

import cop.api.events.core.CancellableEvent
import cop.api.events.core.Event
import net.minecraft.network.protocol.Packet

interface PacketEvent {
    val packet: Packet<*>
    class Received(override val packet: Packet<*>) : CancellableEvent(), PacketEvent
    /**
     * Non-cancellable receive notification dispatched on the Minecraft client
     * thread after synchronous [Received] interception has accepted a packet.
     * It preserves the pre-vanilla observation point and therefore does not
     * imply that vanilla has already applied the packet to the world.
     * Use this for observers that touch client world, player, or UI state.
     */
    class ReceivedClient(override val packet: Packet<*>) : Event(), PacketEvent
    class Sent(override val packet: Packet<*>) : CancellableEvent(), PacketEvent
    class ReceivedPost(override val packet: Packet<*>) : Event(), PacketEvent
}
