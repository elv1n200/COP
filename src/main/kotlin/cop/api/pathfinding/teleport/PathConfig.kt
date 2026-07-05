package cop.api.pathfinding.teleport

/**
 * Tuning bundle for the teleport pathfinders.
 *
 * Ported from quoi (`quoi.api.pathfinding.PathConfig`, pigeonlover1998).
 */
data class PathConfig(
    val pitchStep: Float = 22f,
    val yawStep: Float = 22f,
    val hWeight: Double = 6.7,
    val threads: Int = 2,
    val timeout: Long = 1000L,
    val feedback: Boolean = true,
)
