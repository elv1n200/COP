package cop.utils.skyblock.player.interact

import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * 26.x reworked [ServerboundInteractPacket] from a factory-built class into a
 * plain record `(entityId, hand, location, usingSecondaryAction)` and dropped
 * the old `createInteractionPacket(...)` statics (attack moved to its own
 * `ServerboundAttackPacket`). Vanilla now always sends a single location-bearing
 * interact packet.
 *
 * These shims keep the pre-26 call shape so callers stay version-agnostic; the
 * Stonecutter `>= 26` replacement redirects every
 * `ServerboundInteractPacket.createInteractionPacket(...)` here.
 */
object InteractPacketCompat {

    /** interact-at: explicit relative hit [location]. */
    fun createInteractionPacket(
        entity: Entity,
        usingSecondaryAction: Boolean,
        hand: InteractionHand,
        location: Vec3,
    ): ServerboundInteractPacket {
        //? if >= 26 {
        /*return ServerboundInteractPacket(entity.id, hand, location, usingSecondaryAction)*/
        //? } else {
        return ServerboundInteractPacket.createInteractionPacket(entity, usingSecondaryAction, hand, location)
        //? }
    }

    /** plain interact: no explicit hit. 26.x still requires a location, so we
     *  use the entity centre relative to its origin (vanilla's interact path
     *  sends the relative hit point too). */
    fun createInteractionPacket(
        entity: Entity,
        usingSecondaryAction: Boolean,
        hand: InteractionHand,
    ): ServerboundInteractPacket {
        //? if >= 26 {
        /*return ServerboundInteractPacket(
            entity.id, hand, entity.boundingBox.center.subtract(entity.position()), usingSecondaryAction
        )*/
        //? } else {
        return ServerboundInteractPacket.createInteractionPacket(entity, usingSecondaryAction, hand)
        //? }
    }
}
