package cop.api.skyblock.dungeon.odonscanning.tiles

import cop.utils.Vec2i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdonTileStateTest {
    @Test
    fun `room state belongs to a dungeon instance and not the shared catalogue data`() {
        val sharedData = RoomData(
            name = "Test Room",
            type = RoomType.NORMAL,
            cores = listOf(123),
            crypts = 0,
            secrets = 1,
            trappedChests = 0,
        )
        val firstRunRoom = OdonRoom(data = sharedData, roomComponents = linkedSetOf())
        val secondRunRoom = OdonRoom(data = sharedData, roomComponents = linkedSetOf())

        firstRunRoom.updateState(RoomState.CLEARED)

        assertEquals(RoomState.CLEARED, firstRunRoom.state)
        assertEquals(RoomState.UNDISCOVERED, secondRunRoom.state)
    }

    @Test
    fun `room remains addressable after mutable scan data changes`() {
        val room = OdonRoom(
            data = RoomData("Test Room", RoomType.NORMAL, emptyList(), 0, 0, 0),
            roomComponents = linkedSetOf(),
        )
        val rooms = hashSetOf(room)

        room.roomComponents += RoomComponent(-185, -185, 42)
        room.updateState(RoomState.DISCOVERED)

        assertTrue(room in rooms)
    }

    @Test
    fun `door remains addressable after map state and type changes`() {
        val door = OdonDoor(Vec2i(-169, -185), DoorType.NORMAL)
        val doors = hashSetOf(door)

        door.type = DoorType.WITHER
        door.state = RoomState.UNOPENED
        door.locked = true

        assertTrue(door in doors)
    }
}
