package cop.api.events

import cop.api.events.core.Event

abstract class TickEvent {
    class Start : Event()
    class End : Event()
    class Server(val ticks: Int) : Event()
}