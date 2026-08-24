package cop.module.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatWaypointDataTest {
    @Test
    fun `party coordinates with rank and punctuation parse`() {
        assertEquals(
            ParsedChatWaypoint(ChatWaypointSource.PARTY, "Elv1n_", -123, 64, 987),
            ChatWaypointParser.parse("Party > [MVP+] Elv1n_: meet me at x: -123, y: 64, z: 987!"),
        )
    }

    @Test
    fun `hypixel chat suffix icon remains supported`() {
        assertEquals(
            ParsedChatWaypoint(ChatWaypointSource.PARTY, "Elv1n_", 12, 70, -5),
            ChatWaypointParser.parse("Party > [MVP+] Elv1n_ ⚒: x: 12, y: 70, z: -5"),
        )
    }

    @Test
    fun `public coordinates accept equals and decimal separators`() {
        assertEquals(
            ParsedChatWaypoint(ChatWaypointSource.PUBLIC, "Miner", 10, -4, 22),
            ChatWaypointParser.parse("[VIP] Miner: x=10; y=-4; z=22."),
        )
    }

    @Test
    fun `system text and out of bounds values are rejected`() {
        assertNull(ChatWaypointParser.parse("Objective updated: x: 1, y: 2, z: 3"))
        assertNull(ChatWaypointParser.parse("Player: x: 30000001, y: 2, z: 3"))
        assertNull(ChatWaypointParser.parse("Player: x: 1, y: 2049, z: 3"))
        assertNull(ChatWaypointParser.parse("Player: x: -2147483648, y: 2, z: 3"))
    }
}
