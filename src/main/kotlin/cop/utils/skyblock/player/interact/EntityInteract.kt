package cop.utils.skyblock.player.interact

import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import cop.CopMod.mc
import cop.utils.eyePosition
import cop.utils.skyblock.player.interact.AuraManager.debugBox

class EntityInteract(private val entity: Entity, private val action: AuraAction) {
    fun execute() { // todo improve
        val player = mc.player ?: return
        if (player.eyePosition().distanceTo(entity.position()) > 5) return

        if (action == AuraAction.INTERACT_AT) {
            val expand = entity.pickRadius.toDouble()
            val boundingBox = entity.boundingBox.inflate(expand)

            val clipResult = boundingBox.clip(player.eyePosition(), boundingBox.center)

            if (clipResult.isPresent) {
                val hitVec = clipResult.get()

                mc.connection?.send(
                    ServerboundInteractPacket.createInteractionPacket(
                        entity,
                        player.isShiftKeyDown,
                        InteractionHand.MAIN_HAND,
                        hitVec.subtract(entity.position())
                    )
                )

                // Before 26, vanilla sent INTERACT_AT followed by the plain
                // INTERACT fallback. The 26.x packet combines both semantics,
                // so sending this second packet would activate the entity twice.
                //? if < 26 {
                mc.connection?.send(
                    ServerboundInteractPacket.createInteractionPacket(
                        entity,
                        player.isShiftKeyDown,
                        InteractionHand.MAIN_HAND
                    )
                )
                //? }

                debugBox(hitVec)
            }
        } else {
            mc.connection?.send(
                ServerboundInteractPacket.createInteractionPacket(
                    entity,
                    player.isShiftKeyDown,
                    InteractionHand.MAIN_HAND
                )
            )
            debugBox(entity.boundingBox.center)
        }
    }
}
