package cop.api.pathfinding.teleport.context

import net.minecraft.core.BlockPos
import cop.api.pathfinding.teleport.Raycasts

/**
 * Etherwarp A* context. Ported from quoi (`quoi.api.pathfinding.context.EtherwarpContext`,
 * pigeonlover1998), minus the room-radius / next-room fields used only by quoi's
 * dungeon-map segmented pathing (COP does single-room clears).
 */
class EtherwarpContext(
    goal: BlockPos,
    dist: Double,
    hWeight: Double,
    raycasts: Raycasts,
    timeout: Long,
    val offset: Boolean,
) : TeleportContext(goal, dist, hWeight, raycasts, timeout)
