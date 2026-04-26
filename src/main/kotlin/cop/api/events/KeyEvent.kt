package cop.api.events

import cop.api.events.core.CancellableEvent
import cop.api.events.core.Event
import cop.api.input.MutableInput
import net.minecraft.world.entity.player.Input as ClientInput

abstract class KeyEvent {
    class Press(val key: Int, val scanCode: Int, val modifiers: Int) : CancellableEvent()
    class Release(val key: Int, val scanCode: Int, val modifiers: Int) : CancellableEvent()
    class Input(val clientInput: ClientInput, val input: MutableInput) : Event()
}