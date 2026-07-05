package cop.api.pathfinding.teleport.context

import net.minecraft.core.BlockPos
import cop.api.pathfinding.teleport.Raycasts
import cop.api.pathfinding.teleport.TeleportPathNode
import cop.utils.WorldUtils.etherwarpable

/**
 * Transmission (AOTV/AOTE/Wither-Impact) A* context. Ported from quoi
 * (`quoi.api.pathfinding.context.TransmissionContext`, pigeonlover1998).
 *
 * When [ground] is set, mid-air landings (no etherwarpable block directly below)
 * get a +50 g penalty so the search prefers spots you can actually stand on,
 * rather than leaving you floating.
 */
class TransmissionContext(
    goal: BlockPos,
    dist: Double,
    hWeight: Double,
    raycasts: Raycasts,
    timeout: Long,
    val ground: Boolean,
    /** Squared block radius around [goal] that counts as reaching it. 0 = exact.
     *  A transmission overshoots flat same-level targets (nothing stops the ray
     *  horizontally), so an exact landing on the goal block is often impossible;
     *  a small tolerance lets the search accept a landing next to it. */
    val radius: Double = 0.0,
) : TeleportContext(goal, dist, hWeight, raycasts, timeout) {

    override fun addNode(node: TeleportPathNode) {
        if (!ground) {
            super.addNode(node)
            return
        }

        if (!node.pos.below().etherwarpable) {
            super.addNode(
                TeleportPathNode(
                    node.x, node.y, node.z, node.pos,
                    node.g + 50.0, node.h, node.parent, node.yaw, node.pitch,
                )
            )
        } else {
            super.addNode(node)
        }
    }
}
