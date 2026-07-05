package cop.api.pathfinding.teleport.impl

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.CauldronBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import cop.api.pathfinding.teleport.AbstractTeleportPathfinder
import cop.api.pathfinding.teleport.PathConfig
import cop.api.pathfinding.teleport.Raycasts
import cop.api.pathfinding.teleport.TeleportPathNode
import cop.api.pathfinding.teleport.context.EtherwarpContext
import cop.api.pathfinding.teleport.generateRaycasts
import cop.utils.ChatUtils.modMessage
import cop.utils.Direction
import cop.utils.distanceTo
import cop.utils.getEtherwarpDirection
import cop.utils.traverseVoxels
import cop.utils.WorldUtils.etherwarpable

/**
 * A* pathfinder using the etherwarp ability (~60 blocks, sneak, lands on a solid
 * block you look at). This is the [TeleportPathNode]-returning sibling of COP's
 * legacy [cop.api.pathfinding.impl.EtherwarpPathfinder] (kept untouched for
 * room-nav), so mob-clear can mix it uniformly with [TransmissionPathfinder].
 *
 * Ported from quoi (`quoi.api.pathfinding.impl.EtherwarpPathfinder`, pigeonlover1998),
 * minus the dungeon-map segmented `findDungeonPath`.
 */
object TeleportEtherwarpPathfinder : AbstractTeleportPathfinder<EtherwarpContext>() {

    private var lastDist = -1.0
    private var lastPitchStep = -1.0f
    private var lastYawStep = -1.0f
    private var cachedRaycasts: Raycasts? = null

    fun findPath(
        from: Vec3,
        to: BlockPos,
        config: PathConfig = PathConfig(),
        dist: Double = 60.0,
        offset: Boolean = true,
        withLast: Boolean = false,
    ): List<TeleportPathNode>? {
        if (!to.etherwarpable) return null
        val raycasts = getRaycasts(dist, config.pitchStep, config.yawStep)
        val ctx = EtherwarpContext(to, dist, config.hWeight, raycasts, config.timeout, offset)
        val startPos = BlockPos.containing(from.x, from.y, from.z)

        ctx.addNode(TeleportPathNode(from.x, from.y, from.z, startPos, 0.0, startPos.distanceTo(to) / dist, null, 0f, 0f))

        val path = find(ctx, config.threads)

        return if (path != null) {
            val smoothed = smoothPath(path, dist, withLast)
            val size = if (withLast) path.size else path.size - 1
            if (config.feedback) modMessage("Found path in ${System.currentTimeMillis() - ctx.startTime}ms (${ctx.processed.get()}). $size || ${smoothed.size}")
            smoothed
        } else {
            if (config.feedback) modMessage("&cFailed &rafter ${System.currentTimeMillis() - ctx.startTime}ms (${ctx.processed.get()}).")
            null
        }
    }

    override fun getSneak(): Boolean = true

    override fun getNodeY(ctx: EtherwarpContext, hit: BlockPos): Double =
        hit.y + (if (ctx.offset) 1.05 else 1.0)

    override fun getDirection(from: Vec3, to: BlockPos, dist: Double): Direction? =
        getEtherwarpDirection(from, to, dist)

    override fun getHit(ctx: EtherwarpContext, eyeX: Double, eyeY: Double, eyeZ: Double, dx: Double, dy: Double, dz: Double): BlockPos? {
        val result = traverseVoxels(eyeX, eyeY, eyeZ, eyeX + dx, eyeY + dy, eyeZ + dz, etherwarp = true)
        if (result.succeeded && result.pos != null && (result.pos == ctx.goal || !result.state.blackListed)) {
            return result.pos
        }
        return null
    }

    private fun getRaycasts(dist: Double, pitchStep: Float, yawStep: Float): Raycasts {
        if (dist == lastDist && pitchStep == lastPitchStep && yawStep == lastYawStep) {
            cachedRaycasts?.let { return it }
        }
        val raycasts = generateRaycasts(pitchStep, yawStep, dist)
        lastDist = dist
        lastPitchStep = pitchStep
        lastYawStep = yawStep
        cachedRaycasts = raycasts
        return raycasts
    }

    private val BlockState?.blackListed: Boolean
        get() {
            if (this == null) return true
            val isBottomSlab = block is SlabBlock && hasProperty(SlabBlock.TYPE) && getValue(SlabBlock.TYPE) == SlabType.BOTTOM
            return isBottomSlab ||
                block is CarpetBlock ||
                block is WallBlock ||
                block is FenceBlock ||
                block is FenceGateBlock ||
                block is HopperBlock ||
                block is CauldronBlock ||
                block is BannerBlock
        }
}
