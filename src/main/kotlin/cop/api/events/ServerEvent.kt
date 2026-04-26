package cop.api.events

import cop.api.events.core.Event

abstract class ServerEvent {
    class Connect(val ip: String) : Event()
    class Disconnect(val ip: String) : Event()
}