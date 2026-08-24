package cop.module.impl.render

/** Pure parser kept separate from Minecraft rendering so chat-format changes
 * can be covered by unit tests. Inspired by Quoi's coordinate-waypoint idea;
 * the parser and bounded runtime model are COP implementations. */
internal object ChatWaypointParser {
    private val coordinates = Regex(
        """(?i)(?:^|\s)x\s*[:=]\s*(-?\d{1,8})\s*[,;]?\s+y\s*[:=]\s*(-?\d{1,6})\s*[,;]?\s+z\s*[:=]\s*(-?\d{1,8})(?=\s|$|[,.!?])""",
    )

    private val partySender = Regex(
        """^Party > (?:\[[^]\r\n]{1,32}]\s*)*([A-Za-z0-9_]{1,16})(?:\s+[^\p{ASCII}\s:]{1,2})?:\s+""",
    )
    private val publicSender = Regex(
        """^(?:\[[^]\r\n]{1,32}]\s*)*([A-Za-z0-9_]{1,16})(?:\s+[^\p{ASCII}\s:]{1,2})?:\s+""",
    )

    fun parse(message: String): ParsedChatWaypoint? {
        val coordinateMatch = coordinates.find(message) ?: return null
        val sourceMatch = partySender.find(message)
        val source = if (sourceMatch != null) ChatWaypointSource.PARTY else ChatWaypointSource.PUBLIC
        val senderMatch = sourceMatch ?: publicSender.find(message) ?: return null

        val x = coordinateMatch.groupValues[1].toIntOrNull() ?: return null
        val y = coordinateMatch.groupValues[2].toIntOrNull() ?: return null
        val z = coordinateMatch.groupValues[3].toIntOrNull() ?: return null
        if (x !in -30_000_000..30_000_000 || z !in -30_000_000..30_000_000 || y !in -2_048..2_048) {
            return null
        }

        return ParsedChatWaypoint(
            source = source,
            sender = senderMatch.groupValues[1],
            x = x,
            y = y,
            z = z,
        )
    }
}

internal data class ParsedChatWaypoint(
    val source: ChatWaypointSource,
    val sender: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal enum class ChatWaypointSource {
    PARTY,
    PUBLIC,
}
