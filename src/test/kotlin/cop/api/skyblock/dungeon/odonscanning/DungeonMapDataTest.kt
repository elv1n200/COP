package cop.api.skyblock.dungeon.odonscanning

import cop.api.skyblock.dungeon.odonscanning.DungeonMapData.Calibration
import cop.api.skyblock.dungeon.odonscanning.DungeonMapData.Cell
import cop.api.skyblock.dungeon.odonscanning.tiles.DoorType
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomState
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType
import cop.utils.Vec2i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonMapDataTest {
    @Test
    fun `calibration does not join entrance pixels across row boundaries`() {
        val colors = ByteArray(MAP_PIXELS)
        for (x in 120 until 128) colors[10 * MAP_SIDE + x] = 30
        for (x in 0 until 8) colors[11 * MAP_SIDE + x] = 30

        assertNull(DungeonMapData.calibrate(colors, 0))
    }

    @Test
    fun `calibration accepts exact room sizes and preserves floor geometry`() {
        val sixteen = entranceStripe(x = 20, z = 40, length = 16)

        assertEquals(
            Calibration(0, Vec2i(22, 22), Vec2i(-137, -137), Vec2i(4, 4), 16),
            DungeonMapData.calibrate(sixteen, 0),
        )
        assertEquals(
            Calibration(1, Vec2i(22, 11), Vec2i(-137, -121), Vec2i(4, 5), 16),
            DungeonMapData.calibrate(sixteen, 1),
        )
        assertEquals(
            Calibration(2, Vec2i(11, 11), Vec2i(-121, -121), Vec2i(5, 5), 16),
            DungeonMapData.calibrate(sixteen, 2),
        )
        assertEquals(
            Calibration(3, Vec2i(11, 11), Vec2i(-121, -121), Vec2i(5, 5), 16),
            DungeonMapData.calibrate(sixteen, 3),
        )

        val eighteen = assertNotNull(DungeonMapData.calibrate(entranceStripe(5, 5, 18), 7))
        assertEquals(18, eighteen.roomSize)
        assertEquals(Vec2i(5, 5), eighteen.startCoords)
        assertEquals(Vec2i(-105, -105), eighteen.mapCentre)
        assertEquals(Vec2i(6, 6), eighteen.mapSize)
        assertEquals(Vec2i(14, 14), eighteen.firstCentre)
        assertEquals(9, eighteen.halfRoom)
        assertEquals(11, eighteen.halfTile)
    }

    @Test
    fun `two room components merge only through a room connector`() {
        val calibration = testCalibration(width = 2)
        val colors = ByteArray(MAP_PIXELS)
        setTile(colors, calibration, 0, 0, center = 63, side = 63)
        setTile(colors, calibration, 1, 0, center = 63, side = 63)
        setTile(colors, calibration, 2, 0, center = 63, side = 63)

        val room = DungeonMapData.parse(colors, calibration).rooms.single()
        assertEquals(setOf(Cell(0, 0), Cell(1, 0)), room.components)
        assertEquals(RoomType.NORMAL, room.type)
        assertEquals(RoomState.DISCOVERED, room.state)
    }

    @Test
    fun `a door remains a door and separates adjacent rooms`() {
        val calibration = testCalibration(width = 2)
        val colors = ByteArray(MAP_PIXELS)
        setTile(colors, calibration, 0, 0, center = 63, side = 63)
        setTile(colors, calibration, 1, 0, center = 119, side = 0)
        setTile(colors, calibration, 2, 0, center = 63, side = 63)

        val snapshot = DungeonMapData.parse(colors, calibration)
        assertEquals(2, snapshot.rooms.size)
        assertEquals(
            DungeonMapData.DoorSnapshot(Cell(1, 0), DoorType.WITHER, RoomState.UNOPENED),
            snapshot.doors.single(),
        )
    }

    @Test
    fun `unknown colors and invalid buffers are ignored safely`() {
        val calibration = testCalibration(width = 2)
        val colors = ByteArray(MAP_PIXELS)
        setTile(colors, calibration, 0, 0, center = 127, side = 127)
        setTile(colors, calibration, 1, 0, center = 127, side = 127)

        assertEquals(DungeonMapData.Snapshot.EMPTY, DungeonMapData.parse(colors, calibration))
        assertEquals(DungeonMapData.Snapshot.EMPTY, DungeonMapData.parse(ByteArray(0), calibration))
        assertNull(DungeonMapData.calibrate(ByteArray(0), 7))
    }

    @Test
    fun `room state aggregation follows the documented priority`() {
        val orderedStates = listOf(
            30 to RoomState.GREEN,
            34 to RoomState.CLEARED,
            18 to RoomState.FAILED,
            66 to RoomState.DISCOVERED,
            85 to RoomState.UNOPENED,
            0 to RoomState.UNDISCOVERED,
        )

        orderedStates.zipWithNext().forEach { (higher, lower) ->
            val calibration = testCalibration(width = 2)
            val colors = ByteArray(MAP_PIXELS)
            setTile(colors, calibration, 0, 0, center = higher.first, side = 66)
            setTile(colors, calibration, 1, 0, center = 66, side = 66)
            setTile(colors, calibration, 2, 0, center = lower.first, side = 66)

            val room = DungeonMapData.parse(colors, calibration).rooms.single()
            assertEquals(higher.second, room.state, "${higher.second} should outrank ${lower.second}")
        }
    }

    @Test
    fun `decoration at first room center maps to eight eight`() {
        val calibration = testCalibration(width = 2)
        val first = calibration.firstCentre
        val firstMapPos = Vec2i((first.x - 64) * 2, (first.z - 64) * 2)

        assertEquals(8f to 8f, DungeonMapData.decorationToRender(firstMapPos, calibration))

        val nextMapPos = Vec2i((first.x + calibration.roomSize + 4 - 64) * 2, firstMapPos.z)
        assertEquals(28f to 8f, DungeonMapData.decorationToRender(nextMapPos, calibration))
    }

    @Test
    fun `sampling bounds reject impossible calibration without throwing`() {
        val invalid = Calibration(7, Vec2i(120, 120), Vec2i(0, 0), Vec2i(6, 6), 18)
        assertEquals(DungeonMapData.Snapshot.EMPTY, DungeonMapData.parse(ByteArray(MAP_PIXELS), invalid))
        assertTrue(DungeonMapData.parse(ByteArray(MAP_PIXELS), invalid).rooms.isEmpty())
    }

    private fun entranceStripe(x: Int, z: Int, length: Int): ByteArray =
        ByteArray(MAP_PIXELS).also { colors ->
            repeat(length) { offset -> colors[z * MAP_SIDE + x + offset] = 30 }
        }

    private fun testCalibration(width: Int, height: Int = 1) = Calibration(
        floorNumber = 7,
        startCoords = Vec2i(10, 10),
        mapCentre = Vec2i(-121, -121),
        mapSize = Vec2i(width, height),
        roomSize = 16,
    )

    private fun setTile(
        colors: ByteArray,
        calibration: Calibration,
        gridX: Int,
        gridZ: Int,
        center: Int,
        side: Int,
    ) {
        val mapX = calibration.firstCentre.x + gridX * calibration.halfTile
        val mapZ = calibration.firstCentre.z + gridZ * calibration.halfTile
        colors[mapZ * MAP_SIDE + mapX] = center.toByte()

        val (sideX, sideZ) = when {
            gridX % 2 == 0 && gridZ % 2 == 0 ->
                mapX - calibration.halfRoom to mapZ - calibration.halfRoom

            gridZ % 2 == 1 -> mapX - 4 to mapZ
            else -> mapX to mapZ - 4
        }
        colors[sideZ * MAP_SIDE + sideX] = side.toByte()
    }

    private companion object {
        const val MAP_SIDE = 128
        const val MAP_PIXELS = MAP_SIDE * MAP_SIDE
    }
}
