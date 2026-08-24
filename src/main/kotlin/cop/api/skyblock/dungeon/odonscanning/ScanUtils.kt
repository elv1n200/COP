package cop.api.skyblock.dungeon.odonscanning

import com.google.common.reflect.TypeToken
import com.google.gson.GsonBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import cop.CopMod.logger
import cop.CopMod.mc
import cop.api.events.DungeonEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus.on
import cop.api.skyblock.Island
import cop.api.skyblock.Location
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.odonscanning.tiles.DoorType
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonDoor
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomComponent
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomData
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomDataDeserializer
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomShape
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomState
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType
import cop.api.skyblock.dungeon.odonscanning.tiles.Rotations
import cop.utils.Vec2i
import cop.utils.WorldUtils.getBlockEntityList
import cop.utils.WorldUtils.state
import cop.utils.equalsOneOf
import kotlin.math.round

// https://github.com/Noamm9/NoammAddons/blob/master/src/main/kotlin/com/github/noamm9/utils/dungeons/map/handlers/DungeonScanner.kt
// https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/utils/skyblock/dungeon/ScanUtils.kt
object ScanUtils {
    private const val START = -185
    private const val SCAN_INTERVAL_NANOS = 250_000_000L

    private val roomList: Set<RoomData> = loadRoomData()
    val coreToRoomData: Map<Int, RoomData> =
        roomList.flatMap { room -> room.cores.map { core -> core to room } }.toMap()

    private val horizontals = Direction.entries.filter { it.axis.isHorizontal }
    private val mutableBlockPos = BlockPos.MutableBlockPos()
    private var lastRoomPos: Vec2i? = null
    private var lastScanNanos = 0L

    val grid = Array<Any?>(121) { null }

    var currentRoom: OdonRoom? = null
        private set
    val passedRooms: MutableSet<OdonRoom> = mutableSetOf()
    val scannedRooms: MutableSet<OdonRoom> = mutableSetOf()
    val scannedDoors: MutableSet<OdonDoor> = mutableSetOf()

    var mimicRoom: OdonRoom? = null
        private set

    private fun loadRoomData(): Set<RoomData> {
        return try {
            GsonBuilder()
                .registerTypeAdapter(RoomData::class.java, RoomDataDeserializer())
                .create().fromJson(
                    (ScanUtils::class.java.getResourceAsStream("/assets/cop/odon_rooms.json")
                        ?: throw kotlinx.io.files.FileNotFoundException()).bufferedReader(),
                    object : TypeToken<Set<RoomData>>() {}.type
                )
        } catch (e: Exception) {
            logger.error("Error reading room data", e)
            setOf()
        }
    }

    fun init() {
        on<TickEvent.End> {
            if ((!Dungeon.inDungeons && !Location.currentArea.isArea(Island.SinglePlayer)) || Dungeon.inBoss) {
                currentRoom?.let { DungeonEvent.Room.Enter(null).post() }
                return@on
            } // We want the current room to register as null if we are not in a dungeon

            val scannedThisTick = scanDungeon()

            if (scannedThisTick) {
                scannedRooms.filter {
                    it.rotation == Rotations.NONE &&
                        it.roomComponents.size == it.data.shape.expectedTileCount
                }.forEach { room ->
                    val comp = room.roomComponents.firstOrNull() ?: return@forEach
                    val level = mc.level ?: return@forEach

                    if (level.hasChunk(comp.x shr 4, comp.z shr 4)) {
                        val chunk = level.getChunk(comp.x shr 4, comp.z shr 4)
                        val height = getTopLayerOfRoom(Vec2i(comp.x, comp.z), chunk)

                        if (height > 0) {
                            updateRotation(room, height)
                        }
                    }
                }
            }

            if (scannedThisTick && mimicRoom == null && (Dungeon.floor?.floorNumber ?: -1) > 5) {
                scanMimic()
            }

            val pX = mc.player?.x?.toInt() ?: return@on
            val pZ = mc.player?.z?.toInt() ?: return@on

            val room = getRoomFromPos(pX, pZ)

            val gx = (round((pX - START) / 32.0).toInt() * 2).coerceIn(0, 10)
            val gz = (round((pZ - START) / 32.0).toInt() * 2).coerceIn(0, 10)

            val roomPos = Vec2i(gx, gz)
            if (roomPos == lastRoomPos && room === currentRoom) return@on
            lastRoomPos = roomPos

            if (room !== currentRoom) {
                DungeonEvent.Room.Enter(room).post()
            }
        }

        on<DungeonEvent.Room.Enter> {
            currentRoom = room
            passedRooms.add(room ?: return@on)
        }

        on<DungeonEvent.Room.Scan> {
            MapRenderer.refresh()
        }

        on<WorldEvent.Chunk.Load> {
            MapRenderer.onChunkLoad()
        }

        on<WorldEvent.Change> {
            passedRooms.clear()
            scannedRooms.clear()
            scannedDoors.clear()
            grid.fill(null)
            currentRoom = null
            mimicRoom = null
            lastRoomPos = null
            lastScanNanos = 0L
            MapRenderer.reset()
        }
    }

    private fun scanDungeon(): Boolean {
        val now = System.nanoTime()
        if (lastScanNanos != 0L && now - lastScanNanos < SCAN_INTERVAL_NANOS) return false
        lastScanNanos = now

        val level = mc.level ?: return false

        for (z in 0..10) {
            for (x in 0..10) {
                val i = z * 11 + x
                if (grid[i] != null) continue

                val wx = START + x * 16
                val wz = START + z * 16

                if (!level.hasChunk(wx shr 4, wz shr 4)) continue

                val chunk = level.getChunk(wx shr 4, wz shr 4)
                val height = getTopLayerOfRoom(Vec2i(wx, wz), chunk)
                if (height <= 0) continue

                val tile = scanTile(wx, wz, x, z, height, chunk)
                if (tile != null) {
                    grid[i] = tile
                }
            }
        }
        return true
    }

    private fun scanTile(x: Int, z: Int, col: Int, row: Int, height: Int, chunk: LevelChunk): Any? {
        val rowEven = row % 2 == 0
        val colEven = col % 2 == 0

        return when {
            rowEven && colEven -> { // rooms
                if (height <= 0) return null
                val core = getCoreAtHeight(Vec2i(x, z), height, chunk)
                val data = coreToRoomData[core] ?: return null
                addRoomCentre(x, z, col, row, data, core, height)
            }

            !rowEven && !colEven -> { // 2x2 centres
                val tile = grid[(row - 1) * 11 + col - 1] as? OdonRoom
                if (tile != null) {
                    connectRoom(tile, height)
                } else null
            }

            height in intArrayOf(73, 74, 81, 82) -> { // dors
                val block = chunk.level.getBlockState(mutableBlockPos.set(x, 69, z)).block
                val type = when (block) {
                    Blocks.COAL_BLOCK -> DoorType.WITHER
                    Blocks.RED_TERRACOTTA -> DoorType.BLOOD
                    Blocks.INFESTED_CHISELED_STONE_BRICKS -> DoorType.ENTRANCE
                    else -> DoorType.NORMAL
                }
                val door = OdonDoor(Vec2i(x, z), type)
                scannedDoors.add(door)
                door
            }

            else -> { // connection between big rooms
                val beforeIndex = if (rowEven) row * 11 + col - 1 else (row - 1) * 11 + col
                val afterIndex = if (rowEven) row * 11 + col + 1 else (row + 1) * 11 + col
                val before = grid.getOrNull(beforeIndex) as? OdonRoom
                val after = grid.getOrNull(afterIndex) as? OdonRoom
                val room = reconcileConnectedRooms(before, after) ?: return null

                if (room.data.type == RoomType.ENTRANCE) {
                    val door = OdonDoor(Vec2i(x, z), DoorType.NORMAL)
                    scannedDoors.add(door)
                    door
                } else {
                    connectRoom(room, height)
                }
            }
        }
    }

    private fun addRoomCentre(
        x: Int,
        z: Int,
        col: Int,
        row: Int,
        data: RoomData,
        core: Int,
        height: Int,
    ): OdonRoom {
        val expectedComponents = data.shape.expectedTileCount
        val connectedRooms = buildList<OdonRoom> {
            fun addConnected(index: Int) {
                val room = grid.getOrNull(index) as? OdonRoom ?: return
                if (room.data.name != data.name || room.roomComponents.size >= expectedComponents) return
                if (none { it === room }) add(room)
            }

            if (col > 0) addConnected(row * 11 + col - 1)
            if (row > 0) addConnected((row - 1) * 11 + col)
        }

        val joinableRooms = if (
            connectedRooms.sumOf { it.roomComponents.size } + 1 <= expectedComponents
        ) {
            connectedRooms
        } else {
            connectedRooms.take(1)
        }

        val room = scannedRooms.firstOrNull { scanned -> joinableRooms.any { it === scanned } }
            ?: joinableRooms.firstOrNull()
            ?: OdonRoom(data = data, roomComponents = linkedSetOf()).also { scannedRooms.add(it) }

        joinableRooms.filterNot { it === room }.forEach { mergeRooms(room, it) }
        room.roomComponents.add(RoomComponent(x, z, core))

        if (room.rotation == Rotations.NONE) updateRotation(room, height)
        DungeonEvent.Room.Scan(room).post()
        return room
    }

    private fun connectRoom(room: OdonRoom, height: Int): OdonRoom {
        if (room.rotation == Rotations.NONE) updateRotation(room, height)
        DungeonEvent.Room.Scan(room).post()
        return room
    }

    private fun reconcileConnectedRooms(first: OdonRoom?, second: OdonRoom?): OdonRoom? {
        if (first == null) return null
        if (second == null) return first
        if (first === second || first.data.name != second.data.name) return first

        val componentCount = (first.roomComponents + second.roomComponents).toSet().size
        if (componentCount > first.data.shape.expectedTileCount) return first

        val target = scannedRooms.firstOrNull { it === first || it === second } ?: first
        val source = if (target === first) second else first
        mergeRooms(target, source)
        return target
    }

    private fun mergeRooms(target: OdonRoom, source: OdonRoom) {
        if (target === source) return

        target.roomComponents.addAll(source.roomComponents)
        if (target.rotation == Rotations.NONE && source.rotation != Rotations.NONE) {
            target.rotation = source.rotation
            target.clayPos = source.clayPos
        }
        target.updateState(listOf(target.state, source.state).minBy(::roomStatePriority))

        for (index in grid.indices) {
            if (grid[index] === source) grid[index] = target
        }

        if (currentRoom === source) {
            currentRoom = target
            DungeonEvent.Room.Enter(target).post()
        }
        if (mimicRoom === source) mimicRoom = target
        if (passedRooms.remove(source)) passedRooms.add(target)
        scannedRooms.remove(source)
    }

    private fun roomStatePriority(state: RoomState): Int = when (state) {
        RoomState.GREEN -> 0
        RoomState.CLEARED -> 1
        RoomState.FAILED -> 2
        RoomState.DISCOVERED -> 3
        RoomState.UNOPENED -> 4
        RoomState.UNDISCOVERED -> 5
    }

    fun scanMimic() {
        val trappedChestsByRoom = mutableMapOf<OdonRoom, Int>()

        for (pos in getBlockEntityList()) {
            if (!pos.state.`is`(Blocks.TRAPPED_CHEST)) continue
            val room = getRoomFromPos(pos.x, pos.z) ?: continue
            if (room.data.type == RoomType.TRAP) continue
            trappedChestsByRoom[room] = (trappedChestsByRoom[room] ?: 0) + 1
        }

        val found = trappedChestsByRoom.entries.firstOrNull { (room, count) ->
            count > room.data.trappedChests
        }?.key ?: return

        if (mimicRoom !== found) {
            mimicRoom = found
            MapRenderer.refresh()
        }
    }

    fun getRoomFromPos(x: Int, z: Int): OdonRoom? {
        val gx = (round((x - START) / 32.0).toInt() * 2).coerceIn(0, 10)
        val gz = (round((z - START) / 32.0).toInt() * 2).coerceIn(0, 10)

        return grid[gz * 11 + gx] as? OdonRoom
    }

    fun updateRotation(room: OdonRoom, roomHeight: Int) {
        if (room.roomComponents.size != room.data.shape.expectedTileCount) return

        if (room.data.name == "Fairy") { // Fairy room doesn't have a clay block so we need to set it manually
            val minX = room.roomComponents.minOfOrNull { it.x } ?: return
            val minZ = room.roomComponents.minOfOrNull { it.z } ?: return
            room.clayPos = BlockPos(minX - 15, roomHeight, minZ - 15)
            room.rotation = Rotations.SOUTH
            return
        }

        val level = mc.level ?: return
        val candidates = if (room.data.shape == RoomShape.L) {
            buildList<Pair<Rotations, BlockPos>> {
                for (rotation in Rotations.entries.dropLast(1)) {
                    for (component in room.roomComponents) {
                        add(rotation to BlockPos(component.x + rotation.x, roomHeight, component.z + rotation.z))
                    }
                }
            }
        } else {
            val minX = room.roomComponents.minOf { it.x }
            val maxX = room.roomComponents.maxOf { it.x }
            val minZ = room.roomComponents.minOf { it.z }
            val maxZ = room.roomComponents.maxOf { it.z }
            listOf(
                Rotations.NORTH to BlockPos(maxX + 15, roomHeight, maxZ + 15),
                Rotations.SOUTH to BlockPos(minX - 15, roomHeight, minZ - 15),
                Rotations.WEST to BlockPos(maxX + 15, roomHeight, minZ - 15),
                Rotations.EAST to BlockPos(minX - 15, roomHeight, maxZ + 15),
            )
        }

        for ((rotation, blockPos) in candidates) {
            val positionsToRead = buildList<BlockPos> {
                add(blockPos)
                if (room.roomComponents.size > 1) {
                    horizontals.forEach { facing ->
                        add(blockPos.offset(facing.stepX, 0, facing.stepZ))
                    }
                }
            }
            if (positionsToRead.any { !level.hasChunk(it.x shr 4, it.z shr 4) }) continue
            if (level.getBlockState(blockPos).block != Blocks.BLUE_TERRACOTTA) continue
            if (positionsToRead.drop(1).any {
                    !level.getBlockState(it).block.equalsOneOf(Blocks.AIR, Blocks.BLUE_TERRACOTTA)
                }) continue

            room.clayPos = blockPos
            room.rotation = rotation
            return
        }

        room.rotation = Rotations.NONE // Rotation isn't found until its corner chunks are available.
    }

    private fun getCoreAtHeight(vec2: Vec2i, roomHeight: Int, chunk: LevelChunk): Int {
        val sb = StringBuilder(150)
        val clampedHeight = roomHeight.coerceIn(11..140)
        sb.append(CharArray(140 - clampedHeight) { '0' })
        var bedrock = 0

        for (y in clampedHeight downTo 12) {
            mutableBlockPos.set(vec2.x, y, vec2.z)
            val block = chunk.getBlockState(mutableBlockPos).block
            if (block == Blocks.AIR && bedrock >= 2 && y < 69) {
                sb.append(CharArray(y - 11) { '0' })
                break
            }

            if (block == Blocks.BEDROCK) bedrock++
            else {
                bedrock = 0
                if (block.equalsOneOf(Blocks.OAK_PLANKS, Blocks.TRAPPED_CHEST, Blocks.CHEST)) continue
            }
            sb.append(block)
        }
        return sb.toString().hashCode()
    }

    fun getTopLayerOfRoom(vec2: Vec2i, chunk: LevelChunk): Int {
        for (y in 160 downTo 12) {
            mutableBlockPos.set(vec2.x, y, vec2.z)
            val blockState = chunk.getBlockState(mutableBlockPos)
            if (!blockState.isAir) return if (blockState.block == Blocks.GOLD_BLOCK) y - 1 else y
        }
        return 0
    }
}
