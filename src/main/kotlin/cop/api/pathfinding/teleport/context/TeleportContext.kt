package cop.api.pathfinding.teleport.context

import net.minecraft.core.BlockPos
import cop.api.pathfinding.context.PathContext
import cop.api.pathfinding.teleport.Raycasts
import cop.api.pathfinding.teleport.TeleportPathNode

/**
 * Base A* context for teleport pathfinders. Ported from quoi
 * (`quoi.api.pathfinding.context.TeleportContext`, pigeonlover1998).
 */
open class TeleportContext(
    goal: BlockPos,
    val dist: Double,
    val hWeight: Double,
    val raycasts: Raycasts,
    timeout: Long,
) : PathContext<TeleportPathNode>(goal, timeout)
