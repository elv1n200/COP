package cop.module.impl.dungeon.huds

internal class CooldownTimer(private val durationMillis: Long) {
    private var endsAtMillis = 0L

    fun start(nowMillis: Long) {
        if (remaining(nowMillis) == 0L) endsAtMillis = nowMillis + durationMillis
    }

    fun remaining(nowMillis: Long): Long = (endsAtMillis - nowMillis).coerceAtLeast(0L)

    fun clear() {
        endsAtMillis = 0L
    }
}

internal object DungeonEntryParser {
    private val entryLine = Regex(
        """^\[[^]\r\n]{1,32}] [A-Za-z0-9_]{1,16} entered (?:MM )?[A-Za-z]+ Catacombs, Floor [A-Z0-9]+!$""",
    )

    fun isEntryMessage(message: String): Boolean = message
        .replace("\r\n", "\n")
        .lineSequence()
        .map(String::trim)
        .any(entryLine::matches)
}
