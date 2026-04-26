package cop.api.pathfinding.context

import net.minecraft.core.BlockPos
import cop.api.pathfinding.EtherPathNode
import cop.api.pathfinding.impl.EtherwarpPathfinder.Raycasts

class EtherwarpContext(
    goal: BlockPos,
    val dist: Double,
    val hWeight: Double,
    val raycasts: Raycasts,
    timeout: Long,
    val offset: Boolean,
) : PathContext<EtherPathNode>(goal, timeout)