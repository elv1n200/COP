package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.NoammAddons
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.dungeon.waypoints.DungeonWaypoints
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.enums.SecretType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render3D
import com.github.noamm9.utils.render.RenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

object PersistentSecretHeads : Feature(
    description = "Keeps clicked dungeon secret heads visible as ghost blocks for route recording.",
    name = "Persistent Secret Heads",
    toggled = true
) {
    private data class SecretWaypoint(val pos: BlockPos, val type: SecretType) {
        val color: Color = when (type) {
            SecretType.REDSTONE_KEY -> Color.RED
            SecretType.WITHER_ESSANCE -> Color.BLACK
            else -> Color.MAGENTA
        }
    }

    private val secretDefinitions by lazy { ScanUtils.roomList.associate { it.name to it.secretCoords } }
    private val persistentHeadTypes = setOf(SecretType.REDSTONE_KEY, SecretType.WITHER_ESSANCE)

    private val currentHeads = CopyOnWriteArrayList<SecretWaypoint>()
    private val currentBatSecrets = CopyOnWriteArrayList<BlockPos>()
    private var currentRoom: UniqueRoom? = null

    override fun init() {
        register<DungeonEvent.RoomEvent.onEnter> {
            onRoomEnter(event.room)
        }

        register<RenderWorldEvent> {
            onRenderWorld(event.ctx)
        }

        register<WorldChangeEvent> {
            clear()
        }
    }

    private fun onRoomEnter(room: UniqueRoom) {
        currentRoom = room
        currentHeads.clear()
        currentBatSecrets.clear()

        val rotation = room.rotation?.let { 360 - it } ?: return
        val corner = room.corner ?: return
        val coords = secretDefinitions[room.name] ?: return

        coords.redstoneKey.forEach {
            currentHeads.add(SecretWaypoint(ScanUtils.getRealCoord(it, corner, rotation), SecretType.REDSTONE_KEY))
        }
        coords.wither.forEach {
            currentHeads.add(SecretWaypoint(ScanUtils.getRealCoord(it, corner, rotation), SecretType.WITHER_ESSANCE))
        }
        coords.bat.forEach {
            currentBatSecrets.add(ScanUtils.getRealCoord(it, corner, rotation))
        }
    }

    private fun onRenderWorld(ctx: RenderContext) {
        if (!DungeonWaypoints.secretWaypoints.value) return
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return
        if (currentHeads.isEmpty()) return

        currentHeads.forEach { waypoint ->
            if (WorldUtils.getBlockAt(waypoint.pos) == Blocks.PLAYER_HEAD) return@forEach
            renderGhostHead(ctx, waypoint)
        }
    }

    fun findGhostHeadTargetForRoute(maxDistance: Double = 6.0): BlockPos? {
        val player = NoammAddons.mc.player ?: return null
        val eye = player.eyePosition
        val look = player.lookAngle.normalize()

        return currentHeads.asSequence()
            .filter { it.type in persistentHeadTypes && WorldUtils.getBlockAt(it.pos) != Blocks.PLAYER_HEAD }
            .mapNotNull { waypoint ->
                val center = Vec3.atCenterOf(waypoint.pos)
                val delta = center.subtract(eye)
                val forward = delta.dot(look)
                if (forward !in 0.0..maxDistance) return@mapNotNull null

                val closestPoint = eye.add(look.scale(forward))
                val offset = center.distanceTo(closestPoint)
                if (offset > 0.85) return@mapNotNull null

                Candidate(waypoint.pos, offset, forward)
            }
            .minWithOrNull(compareBy<Candidate>({ it.offset }, { it.forward }))
            ?.pos
    }

    fun hasSpawnedBatInCurrentRoom(): Boolean {
        val level = NoammAddons.mc.level ?: return false
        if (currentBatSecrets.isEmpty()) return false

        return level.entitiesForRendering()
            .filterIsInstance<Bat>()
            .any { bat ->
                !bat.isInvisible && !bat.isRemoved && currentBatSecrets.any { it.distSqr(bat.blockPosition()) <= 9.0 }
            }
    }

    private fun renderGhostHead(ctx: RenderContext, waypoint: SecretWaypoint) {
        Render3D.renderBox(
            ctx,
            waypoint.pos.x + 0.5,
            waypoint.pos.y,
            waypoint.pos.z + 0.5,
            0.5,
            0.5,
            waypoint.color,
            outline = true,
            fill = true,
            phase = true
        )
    }

    private fun clear() {
        currentRoom = null
        currentHeads.clear()
        currentBatSecrets.clear()
    }

    private data class Candidate(
        val pos: BlockPos,
        val offset: Double,
        val forward: Double
    )
}
