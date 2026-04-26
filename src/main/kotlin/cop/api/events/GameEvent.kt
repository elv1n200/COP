package cop.api.events

import cop.api.events.core.Event

abstract class GameEvent {
    class Load : Event()
    class Unload : Event()
}