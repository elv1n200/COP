package cop.api.pathfinding.teleport

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import cop.api.pathfinding.PathNode

/**
 * A* node for teleport-based pathfinders. Unlike COP's [cop.api.pathfinding.EtherPathNode]
 * (BlockPos-only), this carries the exact fractional standing position [x],[y],[z]
 * plus the [yaw]/[pitch] to teleport toward the next node — needed because
 * transmission (Wither Impact / AOTV) lands in air at fractional positions.
 *
 * Ported from quoi (`quoi.api.pathfinding.TeleportPathNode`, pigeonlover1998).
 */
class TeleportPathNode(
    val x: Double,
    val y: Double,
    val z: Double,
    pos: BlockPos,
    g: Double,
    h: Double,
    parent: PathNode?,
    val yaw: Float,
    val pitch: Float,
) : PathNode(pos, g, h, parent) {
    val vec: Vec3 by lazy { Vec3(x, y, z) }
}
