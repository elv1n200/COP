package cop.api.pathfinding.teleport

import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.floats.FloatArrayList
import cop.utils.getLook
import cop.utils.rad
import kotlin.math.cos
import kotlin.math.max

/**
 * A fan of candidate teleport look-directions used by [AbstractTeleportPathfinder]'s
 * node expansion. [dx]/[dy]/[dz] are the direction vectors (scaled by [scale]);
 * [yaws]/[pitches] are the rotations that produce them.
 *
 * Ported from quoi (`quoi.api.pathfinding.util.Raycasts`, pigeonlover1998).
 */
class Raycasts(
    val dx: DoubleArray,
    val dy: DoubleArray,
    val dz: DoubleArray,
    val yaws: FloatArray,
    val pitches: FloatArray,
    val scale: Double,
)

/**
 * Builds a raycast fan sampling the whole sphere at [pitchStep]/[yawStep] density.
 * [scale] multiplies each direction — etherwarp passes the warp distance (so the
 * ray reaches that far), transmission passes 1.0 (unit dirs) and applies distance
 * inside `predictTransmission`.
 */
fun generateRaycasts(pitchStep: Float, yawStep: Float, scale: Double = 1.0): Raycasts {
    val dx = DoubleArrayList()
    val dy = DoubleArrayList()
    val dz = DoubleArrayList()
    val yaws = FloatArrayList()
    val pitches = FloatArrayList()

    var pitch = -90f
    while (pitch <= 90f) {
        val actualYawStep = (yawStep / max(0.01f, cos(pitch.rad)))
        var yaw = 0f
        while (yaw < 360f) {
            val vec = getLook(yaw, pitch)
            dx.add(vec.x * scale)
            dy.add(vec.y * scale)
            dz.add(vec.z * scale)
            yaws.add(yaw)
            pitches.add(pitch)
            yaw += actualYawStep
        }
        pitch += pitchStep
    }

    return Raycasts(dx.toDoubleArray(), dy.toDoubleArray(), dz.toDoubleArray(), yaws.toFloatArray(), pitches.toFloatArray(), scale)
}
