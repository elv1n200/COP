package cop.api.skyblock.dungeon.odonscanning

import cop.api.skyblock.dungeon.odonscanning.tiles.DoorType
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomState
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType
import cop.utils.Vec2i

/**
 * Stateless decoding of Hypixel's 128 x 128 dungeon-map pixels.
 *
 * The decoder deliberately has no dependency on the current world, packets, or
 * [ScanUtils]. This makes a map update an atomic snapshot and keeps malformed or
 * incomplete map data from corrupting the world-scanned dungeon grid.
 */
object DungeonMapData {
    private const val MAP_SIDE = 128
    private const val MAP_PIXELS = MAP_SIDE * MAP_SIDE

    data class Calibration(
        val floorNumber: Int,
        val startCoords: Vec2i,
        val mapCentre: Vec2i,
        val mapSize: Vec2i,
        val roomSize: Int,
    ) {
        val halfRoom: Int get() = roomSize / 2
        val halfTile: Int get() = halfRoom + 2
        val firstCentre: Vec2i get() = startCoords.add(halfRoom, halfRoom)
    }

    /**
     * A room component uses the compact room grid (0..5). A door uses the
     * interleaved dungeon grid (0..10).
     */
    data class Cell(val x: Int, val z: Int)

    data class RoomSnapshot(
        val components: Set<Cell>,
        val type: RoomType,
        val state: RoomState,
    )

    data class DoorSnapshot(
        val cell: Cell,
        val type: DoorType,
        val state: RoomState,
    )

    data class Snapshot(
        val rooms: Set<RoomSnapshot>,
        val doors: Set<DoorSnapshot>,
    ) {
        companion object {
            val EMPTY = Snapshot(emptySet(), emptySet())
        }
    }

    /**
     * Finds the entrance-room stripe and derives the same floor geometry COP
     * already uses. Runs are checked per row so pixels on adjacent rows can
     * never accidentally form one room.
     */
    fun calibrate(colors: ByteArray, floorNumber: Int): Calibration? {
        if (colors.size != MAP_PIXELS) return null

        val (entranceStart, roomSize) = findEntranceRun(colors) ?: return null
        val startX = entranceStart % MAP_SIDE
        val startZ = entranceStart / MAP_SIDE

        val (startCoords, mapCentre, mapSize) = when (floorNumber) {
            0 -> Triple(Vec2i(22, 22), Vec2i(-137, -137), Vec2i(4, 4))
            1 -> Triple(Vec2i(22, 11), Vec2i(-137, -121), Vec2i(4, 5))
            2, 3 -> Triple(Vec2i(11, 11), Vec2i(-121, -121), Vec2i(5, 5))
            else -> {
                val stride = roomSize + 4
                val start = Vec2i(startX % stride, startZ % stride)
                val extraX = if (start.x == 5) 1 else 0
                val extraZ = if (start.z == 5) 1 else 0

                Triple(
                    start,
                    Vec2i(-121 + extraX * 16, -121 + extraZ * 16),
                    Vec2i(5 + extraX, 5 + extraZ),
                )
            }
        }

        return Calibration(floorNumber, startCoords, mapCentre, mapSize, roomSize)
            .takeIf(::hasValidSamplingBounds)
    }

    /**
     * Parses rooms and doors from a single immutable map-color snapshot.
     * Unknown pixels and incomplete/out-of-bounds calibration data are ignored.
     */
    fun parse(colors: ByteArray, calibration: Calibration): Snapshot {
        if (colors.size != MAP_PIXELS || !hasValidSamplingBounds(calibration)) return Snapshot.EMPTY

        val maxGridX = (calibration.mapSize.x - 1) * 2
        val maxGridZ = (calibration.mapSize.z - 1) * 2
        val nodes = linkedMapOf<GridCell, RoomNode>()

        for (gridZ in 0..maxGridZ step 2) {
            for (gridX in 0..maxGridX step 2) {
                val gridCell = GridCell(gridX, gridZ)
                val centerColor = sampleCenter(colors, calibration, gridCell) ?: continue
                val sideColor = sampleSide(colors, calibration, gridCell) ?: continue
                val type = roomType(sideColor) ?: continue
                val state = roomState(centerColor, type) ?: continue

                nodes[gridCell] = RoomNode(
                    component = Cell(gridX / 2, gridZ / 2),
                    type = type,
                    state = state,
                )
            }
        }

        val unionFind = UnionFind(nodes.keys)
        val doors = mutableListOf<DoorSnapshot>()

        for (gridZ in 0..maxGridZ) {
            for (gridX in 0..maxGridX) {
                if ((gridX and 1) == (gridZ and 1)) continue

                val connector = GridCell(gridX, gridZ)
                val centerColor = sampleCenter(colors, calibration, connector) ?: continue
                val sideColor = sampleSide(colors, calibration, connector) ?: continue

                if (sideColor == 0) {
                    val type = doorType(centerColor) ?: continue
                    doors += DoorSnapshot(Cell(gridX, gridZ), type, doorState(centerColor))
                    continue
                }

                val connectorType = roomType(sideColor) ?: continue
                if (centerColor == 0 || roomState(centerColor, connectorType) == null) continue

                val (first, second) = if ((gridX and 1) == 1) {
                    GridCell(gridX - 1, gridZ) to GridCell(gridX + 1, gridZ)
                } else {
                    GridCell(gridX, gridZ - 1) to GridCell(gridX, gridZ + 1)
                }

                val firstNode = nodes[first] ?: continue
                val secondNode = nodes[second] ?: continue

                // A colored separator is a room connector only when all three
                // samples identify the same room type. This avoids merging two
                // adjacent rooms when a packet is only partially updated.
                if (firstNode.type == connectorType && secondNode.type == connectorType) {
                    unionFind.union(first, second)
                }
            }
        }

        val groupedNodes = linkedMapOf<GridCell, MutableList<Pair<GridCell, RoomNode>>>()
        nodes.forEach { (cell, node) ->
            groupedNodes.getOrPut(unionFind.find(cell)) { mutableListOf() } += cell to node
        }

        val rooms = groupedNodes.values
            .map { group ->
                val ordered = group.sortedWith(compareBy({ it.first.z }, { it.first.x }))
                RoomSnapshot(
                    components = ordered.mapTo(linkedSetOf()) { it.second.component },
                    type = ordered.first().second.type,
                    state = ordered.minBy { statePriority(it.second.state) }.second.state,
                )
            }
            .sortedWith(compareBy({ it.components.minOf(Cell::z) }, { it.components.minOf(Cell::x) }))
            .toCollection(linkedSetOf())

        val orderedDoors = doors
            .sortedWith(compareBy({ it.cell.z }, { it.cell.x }))
            .toCollection(linkedSetOf())

        return Snapshot(rooms, orderedDoors)
    }

    /** Converts a map-decoration byte position directly to COP's 20 px room grid. */
    fun decorationToRender(mapPos: Vec2i, calibration: Calibration): Pair<Float, Float> {
        val mapPixelX = mapPos.x / 2f + 64f
        val mapPixelZ = mapPos.z / 2f + 64f
        val pixelsPerHudRoom = 20f / (calibration.roomSize + 4f)

        return 8f + (mapPixelX - calibration.firstCentre.x) * pixelsPerHudRoom to
            8f + (mapPixelZ - calibration.firstCentre.z) * pixelsPerHudRoom
    }

    private fun findEntranceRun(colors: ByteArray): Pair<Int, Int>? {
        for (z in 0 until MAP_SIDE) {
            var x = 0
            while (x < MAP_SIDE) {
                if (unsigned(colors[z * MAP_SIDE + x]) != 30) {
                    x++
                    continue
                }

                val startX = x
                while (x < MAP_SIDE && unsigned(colors[z * MAP_SIDE + x]) == 30) x++
                val length = x - startX
                if (length == 16 || length == 18) return z * MAP_SIDE + startX to length
            }
        }
        return null
    }

    private fun hasValidSamplingBounds(calibration: Calibration): Boolean {
        if (calibration.roomSize != 16 && calibration.roomSize != 18) return false
        if (calibration.mapSize.x !in 1..6 || calibration.mapSize.z !in 1..6) return false

        val maxGridX = (calibration.mapSize.x - 1) * 2
        val maxGridZ = (calibration.mapSize.z - 1) * 2

        for (gridZ in 0..maxGridZ) {
            for (gridX in 0..maxGridX) {
                if ((gridX and 1) == 1 && (gridZ and 1) == 1) continue

                val cell = GridCell(gridX, gridZ)
                if (centerPosition(calibration, cell).indexOrNull() == null) return false
                if (sidePosition(calibration, cell).indexOrNull() == null) return false
            }
        }
        return true
    }

    private fun sampleCenter(colors: ByteArray, calibration: Calibration, cell: GridCell): Int? =
        centerPosition(calibration, cell).indexOrNull()?.let { unsigned(colors[it]) }

    private fun sampleSide(colors: ByteArray, calibration: Calibration, cell: GridCell): Int? =
        sidePosition(calibration, cell).indexOrNull()?.let { unsigned(colors[it]) }

    private fun centerPosition(calibration: Calibration, cell: GridCell): Pixel = Pixel(
        calibration.firstCentre.x + cell.x * calibration.halfTile,
        calibration.firstCentre.z + cell.z * calibration.halfTile,
    )

    private fun sidePosition(calibration: Calibration, cell: GridCell): Pixel {
        val center = centerPosition(calibration, cell)
        return when {
            (cell.x and 1) == 0 && (cell.z and 1) == 0 ->
                Pixel(center.x - calibration.halfRoom, center.z - calibration.halfRoom)

            (cell.z and 1) == 1 -> Pixel(center.x - 4, center.z)
            else -> Pixel(center.x, center.z - 4)
        }
    }

    private fun Pixel.indexOrNull(): Int? =
        if (x in 0 until MAP_SIDE && z in 0 until MAP_SIDE) z * MAP_SIDE + x else null

    private fun roomType(color: Int): RoomType? = when (color) {
        18 -> RoomType.BLOOD
        82 -> RoomType.FAIRY
        34 -> RoomType.RARE
        74 -> RoomType.CHAMPION
        66 -> RoomType.PUZZLE
        62 -> RoomType.TRAP
        63, 85 -> RoomType.NORMAL
        30 -> RoomType.ENTRANCE
        else -> null
    }

    private fun doorType(color: Int): DoorType? = when (color) {
        18 -> DoorType.BLOOD
        30 -> DoorType.ENTRANCE
        119 -> DoorType.WITHER
        74, 82, 66, 62, 85, 63 -> DoorType.NORMAL
        else -> null
    }

    private fun roomState(color: Int, type: RoomType): RoomState? = when (color) {
        0 -> RoomState.UNDISCOVERED
        34 -> RoomState.CLEARED
        18 -> when (type) {
            RoomType.BLOOD -> RoomState.DISCOVERED
            RoomType.PUZZLE -> RoomState.FAILED
            else -> RoomState.DISCOVERED
        }

        30 -> if (type == RoomType.ENTRANCE) RoomState.DISCOVERED else RoomState.GREEN
        85, 119 -> RoomState.UNOPENED
        62, 63, 66, 74, 82 -> RoomState.DISCOVERED
        else -> null
    }

    private fun doorState(color: Int): RoomState =
        if (color == 85 || color == 119) RoomState.UNOPENED else RoomState.DISCOVERED

    private fun statePriority(state: RoomState): Int = when (state) {
        RoomState.GREEN -> 0
        RoomState.CLEARED -> 1
        RoomState.FAILED -> 2
        RoomState.DISCOVERED -> 3
        RoomState.UNOPENED -> 4
        RoomState.UNDISCOVERED -> 5
    }

    private fun unsigned(color: Byte): Int = color.toInt() and 0xff

    private data class Pixel(val x: Int, val z: Int)
    private data class GridCell(val x: Int, val z: Int)
    private data class RoomNode(val component: Cell, val type: RoomType, val state: RoomState)

    private class UnionFind(cells: Set<GridCell>) {
        private val parents = cells.associateWithTo(mutableMapOf()) { it }
        private val ranks = cells.associateWithTo(mutableMapOf()) { 0 }

        fun find(cell: GridCell): GridCell {
            val parent = parents[cell] ?: return cell
            if (parent == cell) return cell
            return find(parent).also { parents[cell] = it }
        }

        fun union(first: GridCell, second: GridCell) {
            var firstRoot = find(first)
            var secondRoot = find(second)
            if (firstRoot == secondRoot) return

            val firstRank = ranks.getValue(firstRoot)
            val secondRank = ranks.getValue(secondRoot)
            if (firstRank < secondRank) {
                val swap = firstRoot
                firstRoot = secondRoot
                secondRoot = swap
            }

            parents[secondRoot] = firstRoot
            if (firstRank == secondRank) ranks[firstRoot] = firstRank + 1
        }
    }
}
