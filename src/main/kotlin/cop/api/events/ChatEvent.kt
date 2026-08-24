package cop.api.events

import cop.api.events.core.CancellableEvent
import cop.api.events.core.Event
import net.minecraft.network.chat.Component

abstract class ChatEvent {
    class Receive(val message: String, val text: Component, val id: Int) : CancellableEvent() {
        class Post(val message: String, val text: Component, val id: Int) : CancellableEvent()
    }
    /** Synchronous network interception event retained for packet cancellation. */
    class Packet(val message: String, val text: Component) : CancellableEvent()
    /** Non-cancellable [Packet] observation delivered on the Minecraft client thread. */
    class PacketClient(val message: String, val text: Component) : Event()
    class Sent(val message: String, val isCommand: Boolean) : CancellableEvent()
    /** Synchronous network interception event retained for packet cancellation. */
    class ActionBar(val message: String, val text: Component) : CancellableEvent()
    /** Non-cancellable [ActionBar] observation delivered on the Minecraft client thread. */
    class ActionBarClient(val message: String, val text: Component) : Event()
}
