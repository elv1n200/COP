package cop.api.events

import cop.api.events.core.UnfilteredEvent
import cop.api.skyblock.Island

abstract class AreaEvent {
    class Main(val area: Island?) : UnfilteredEvent()
    class Sub(val subarea: String?) : UnfilteredEvent()
}