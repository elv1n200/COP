package cop.api.events

import cop.api.colour.Colour
import cop.api.events.core.CancellableEvent
import cop.api.events.core.Event
import net.minecraft.world.entity.Entity

abstract class EntityEvent {
    class Join(val entity: Entity) : CancellableEvent()
    class Leave(val entity: Entity, val reason: Entity.RemovalReason) : CancellableEvent()
    class ForceGlow(val entity: Entity) : Event() {
        var isGlowing: Boolean = false
        var glowColour: Colour = Colour.WHITE
            set(value) {
                isGlowing = true
                field = value
            }
    }
}