package cop.module.impl.dungeon.worldrender

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.DungeonEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.inBoss
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.odonscanning.SecretCoords
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.WorldUtils.state
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Port of CritsAddons `PersistentSecretHeads` (com.github.noamm9.critsaddons.features.impl.critsaddons.PersistentSecretHeads).
 *
 * When you enter a dungeon room, this populates a list of that room's known
 * redstone-key / wither-essence secret positions and renders them as coloured
 * ghost boxes so the player-head skulls remain visible even after someone grabs them.
 *
 * The underlying secret-coordinate table comes from `rooms.json` via [SecretCoords].
 * The module also exposes two helpers used by sibling modules:
 *
 *  - [findGhostHeadTargetForRoute] — returns the nearest redstone-key/wither head
 *    the player is looking at (used by SecretRoutes when recording a click).
 *  - [hasSpawnedBatInCurrentRoom] — true if a live bat is within 3 blocks of a
 *    known bat-secret spot in the current room (used by SecretRoutes for bat waits).
 */
object PersistentSecretHeads : Module(
    "Persistent Secret Heads",
    area = Island.Dungeon(inClear = true),
    desc = "Keeps clicked secret heads visible as ghost blocks (for eye-tracking and route recording)."
) {
    private enum class HeadType { REDSTONE_KEY, WITHER, OTHER }

    private data class SecretWaypoint(val pos: BlockPos, val type: HeadType) {
        val colour: Colour = when (type) {
            HeadType.REDSTONE_KEY -> Colour.RED.withAlpha(0.47f)
            HeadType.WITHER -> Colour.BLACK.withAlpha(0.47f)
            HeadType.OTHER -> Colour.PURPLE.withAlpha(0.47f)
        }
    }

    private val currentHeads = CopyOnWriteArrayList<SecretWaypoint>()
    private val currentBatSecrets = CopyOnWriteArrayList<BlockPos>()
    private var currentRoom: OdonRoom? = null

    init {
        on<DungeonEvent.Room.Enter> {
            onRoomEnter(room)
        }

        on<RenderEvent.World> {
            if (!inDungeons || inBoss) return@on
            if (currentHeads.isEmpty()) return@on

            val level = mc.level ?: return@on
            for (waypoint in currentHeads) {
                if (level.getBlockState(waypoint.pos).`is`(Blocks.PLAYER_HEAD)) continue
                ctx.drawFilledBox(waypoint.pos.aabb, waypoint.colour, depth = true)
            }
        }

        on<WorldEvent.Change> {
            clear()
        }
    }

    private fun onRoomEnter(room: OdonRoom?) {
        currentHeads.clear()
        currentBatSecrets.clear()
        currentRoom = room
        if (room == null) return

        val secrets = SecretCoords[room.name]
        for (rel in secrets.redstoneKey) {
            currentHeads += SecretWaypoint(room.getRealCoords(rel), HeadType.REDSTONE_KEY)
        }
        for (rel in secrets.wither) {
            currentHeads += SecretWaypoint(room.getRealCoords(rel), HeadType.WITHER)
        }
        for (rel in secrets.bat) {
            currentBatSecrets += room.getRealCoords(rel)
        }
    }

    /**
     * Returns the nearest un-collected redstone-key/wither head the player is
     * currently looking at, within [maxDistance] blocks and 0.85 blocks of
     * offset from the look-ray. Used by SecretRoutes route recording.
     */
    fun findGhostHeadTargetForRoute(maxDistance: Double = 6.0): BlockPos? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val eye = player.eyePosition
        val look = player.lookAngle.normalize()

        return currentHeads.asSequence()
            .filter { it.type != HeadType.OTHER && !level.getBlockState(it.pos).`is`(Blocks.PLAYER_HEAD) }
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
            .minWithOrNull(compareBy({ it.offset }, { it.forward }))
            ?.pos
    }

    /** Returns true if there's a live bat near any known bat-secret spot in the current room. */
    fun hasSpawnedBatInCurrentRoom(): Boolean {
        val level = mc.level ?: return false
        if (currentBatSecrets.isEmpty()) return false

        return level.entitiesForRendering()
            .filterIsInstance<Bat>()
            .any { bat ->
                !bat.isInvisible && !bat.isRemoved &&
                    currentBatSecrets.any { it.distSqr(bat.blockPosition()) <= 9.0 }
            }
    }

    private fun clear() {
        currentRoom = null
        currentHeads.clear()
        currentBatSecrets.clear()
    }

    private data class Candidate(
        val pos: BlockPos,
        val offset: Double,
        val forward: Double,
    )
}
