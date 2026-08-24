package cop.module.impl.mining

/** Pure tab-list parser for the Commission Display HUD. */
internal object CommissionParser {
    private val entry = Regex("""^\s+([^:]{1,80}):\s+(\d+(?:[.,]\d+)?%|DONE)\s*$""")

    fun parse(lines: List<String>): List<CommissionEntry> {
        val header = lines.indexOfFirst { it.trim() == "Commissions:" }
        if (header == -1) return emptyList()

        return lines.asSequence()
            .drop(header + 1)
            .takeWhile { it.startsWith(' ') }
            .mapNotNull { line ->
                val match = entry.matchEntire(line) ?: return@mapNotNull null
                val rawProgress = match.groupValues[2]
                val progress = if (rawProgress == "DONE") {
                    100f
                } else {
                    rawProgress.dropLast(1).replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
                }
                CommissionEntry(match.groupValues[1].trim(), progress.coerceIn(0f, 100f))
            }
            .take(5)
            .toList()
    }
}

internal data class CommissionEntry(
    val name: String,
    val progress: Float,
)
