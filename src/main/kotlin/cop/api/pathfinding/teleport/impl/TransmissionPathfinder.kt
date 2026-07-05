package cop.api.pathfinding.teleport.impl

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import cop.api.pathfinding.teleport.AbstractTeleportPathfinder
import cop.api.pathfinding.teleport.PathConfig
import cop.api.pathfinding.teleport.Raycasts
import cop.api.pathfinding.teleport.TeleportPathNode
import cop.api.pathfinding.teleport.context.TransmissionContext
import cop.api.pathfinding.teleport.generateRaycasts
import cop.utils.ChatUtils.modMessage
import cop.utils.Direction
import cop.utils.distanceTo
import cop.utils.getTransmissionDirection
import cop.utils.predictTransmission

/**
 * A* pathfinder using the instant-transmission ability (AOTV / AOTE), ~12 blocks,
 * no sneak, lands in air where you aim. Ported from quoi
 * (`quoi.api.pathfinding.impl.TransmissionPathfinder`, pigeonlover1998).
 */
object TransmissionPathfinder : AbstractTeleportPathfinder<TransmissionContext>() {

    private var lastPitchStep = -1.0f
    private var lastYawStep = -1.0f
    private var cachedRaycasts: Raycasts? = null

    fun findPath(
        from: Vec3,
        to: BlockPos,
        config: PathConfig = PathConfig(),
        dist: Double = 12.0,
        ground: Boolean = true,
        withLast: Boolean = false,
        radius: Double = 0.0,
    ): List<TeleportPathNode>? {
        val raycasts = getRaycasts(config.pitchStep, config.yawStep)
        val actualGoal = to.above()
        val ctx = TransmissionContext(actualGoal, dist, config.hWeight, raycasts, config.timeout, ground, radius * radius)
        val startPos = BlockPos.containing(from.x, from.y, from.z)

        ctx.addNode(TeleportPathNode(from.x, from.y, from.z, startPos, 0.0, startPos.distanceTo(actualGoal) / dist, null, 0f, 0f))

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

    override fun isGoal(ctx: TransmissionContext, current: TeleportPathNode): Boolean {
        if (ctx.radius > 0.0) return current.pos.distSqr(ctx.goal) <= ctx.radius
        return current.pos == ctx.goal
    }

    override fun getDirection(from: Vec3, to: BlockPos, dist: Double): Direction? =
        getTransmissionDirection(from, to, dist)

    override fun getHit(ctx: TransmissionContext, eyeX: Double, eyeY: Double, eyeZ: Double, dx: Double, dy: Double, dz: Double): BlockPos? {
        val result = predictTransmission(eyeX, eyeY, eyeZ, dx, dy, dz, ctx.dist)
        return if (result.succeeded) result.pos else null
    }

    private fun getRaycasts(pitchStep: Float, yawStep: Float): Raycasts {
        if (pitchStep == lastPitchStep && yawStep == lastYawStep) {
            cachedRaycasts?.let { return it }
        }
        val raycasts = generateRaycasts(pitchStep, yawStep)
        lastPitchStep = pitchStep
        lastYawStep = yawStep
        cachedRaycasts = raycasts
        return raycasts
    }
}
