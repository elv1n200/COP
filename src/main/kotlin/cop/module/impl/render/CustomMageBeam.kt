package cop.module.impl.render

import cop.api.colour.Colour
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.render.drawLine
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque

/**
 * Custom render for mage-class beam attacks (Hyperion, Necron Blade, etc.).
 *
 * Hypixel draws the beam trail with a stream of firework particles. This
 * module intercepts those packets, stitches consecutive particles into
 * "trails" by position + direction, then renders each trail as a single
 * smooth polyline in the world render pass. The vanilla particles can be
 * suppressed so only the custom line shows.
 *
 * Re-implemented from scratch (May 2026). Same behavioural surface as the
 * earlier port; structural differences include a sliding-window direction
 * check (last 3 points) for better tracking on curved beams, an explicit
 * synchronisation pass on point appends since particle packets arrive on
 * the network thread while rendering happens on the render thread, and a
 * deque-based trail store with O(1) trimming of expired entries.
 */
object CustomMageBeam : Module(
    "Custom Mage Beam",
    desc = "Replaces the vanilla firework-particle trail of mage beams with a smooth coloured polyline."
) {
    // -- Settings -----------------------------------------------------------

    private val beamColour by colourPicker(
        "Beam colour", Colour.PINK, allowAlpha = true,
        desc = "Line colour. Ignored when Rainbow is on."
    )
    private val rainbow by switch(
        "Rainbow", false,
        desc = "Cycle through the rainbow gradient instead of a fixed colour."
    )
    private val thickness by slider(
        "Thickness", 2.5, 0.5, 10.0, 0.1,
        desc = "Polyline thickness in pixels."
    )
    private val durationTicks by slider(
        "Duration", 40.0, 10.0, 100.0, 1.0,
        desc = "How long a trail stays drawn after its last particle (in ticks). Lower = trails vanish faster."
    )
    private val minPoints by slider(
        "Min points", 8.0, 2.0, 20.0, 1.0,
        desc = "Trails shorter than this many points are skipped — kills off stray single-particle bursts that aren't actually beams."
    )
    private val throughWalls by switch(
        "Through walls", true,
        desc = "Render the line without depth test so it's visible through terrain."
    )
    private val hideParticles by switch(
        "Hide particles", true,
        desc = "Cancel the vanilla firework particle packets so only the custom line is drawn (no double trail)."
    )
    private val yOffset by slider(
        "Y Offset", 0.0, -1.0, 1.0, 0.05,
        desc = "Vertical adjustment in blocks. Useful if the line sits a hair above or below where it should."
    )

    // -- Trail bookkeeping --------------------------------------------------

    /** A single beam's collected positions plus the tick of first/last sight. */
    private class Trail(val tickStarted: Int) {
        @Volatile var tickLastUpdate: Int = tickStarted
        /** Mutated from the network packet thread; snapshotted on the render
         *  thread via [snapshotPoints]. Guarded by intrinsic lock. */
        private val rawPoints = ArrayList<Vec3>()

        fun appendPoint(p: Vec3, tick: Int) {
            synchronized(rawPoints) { rawPoints.add(p) }
            tickLastUpdate = tick
        }

        fun snapshotPoints(): List<Vec3> = synchronized(rawPoints) { rawPoints.toList() }

        fun lastPointOrNull(): Vec3? = synchronized(rawPoints) { rawPoints.lastOrNull() }

        fun size(): Int = synchronized(rawPoints) { rawPoints.size }
    }

    /** Newest trail at the tail. ArrayDeque is fine here — we only ever
     *  iterate from the head (cleanup) and append/peek at the tail (new
     *  particles). All access happens on tick / render / network threads;
     *  the [trailsLock] lock keeps the iterator-based purge in TickEvent.End
     *  consistent with packet-thread appends. */
    private val trails = ArrayDeque<Trail>()
    private val trailsLock = Any()
    private var tickCounter = 0

    // -- Tunables not surfaced as settings ---------------------------------

    /** Max gap (in ticks) between two particles before they're considered
     *  separate trails. Empirically ~1 tick of slack covers Hypixel's
     *  particle cadence even under packet jitter. */
    private const val MAX_INTER_PARTICLE_TICKS = 2
    /** Number of trailing points used for the sliding-window direction
     *  estimate. Higher = smoother / less sensitive to packet jitter,
     *  lower = better tracking on tight curves. */
    private const val DIRECTION_WINDOW = 3
    /** Cosine threshold for "same direction" — ~23° cone. The previous
     *  port used 0.95 (~18°) which was tight enough to break trail
     *  continuation on beams arcing through map features. */
    private const val SAME_DIRECTION_COS = 0.92

    init {
        on<WorldEvent.Change> {
            synchronized(trailsLock) { trails.clear() }
            tickCounter = 0
        }

        on<TickEvent.End> {
            tickCounter++
            val deathLine = tickCounter - durationTicks.toInt()
            synchronized(trailsLock) {
                while (trails.isNotEmpty() && trails.peekFirst().tickStarted <= deathLine) {
                    trails.pollFirst()
                }
            }
        }

        on<PacketEvent.Received, ClientboundLevelParticlesPacket> {
            if (packet.particle.type != ParticleTypes.FIREWORK) return@on
            val incoming = Vec3(packet.x, packet.y, packet.z)
            ingestParticle(incoming, tickCounter)
            if (hideParticles) cancel()
        }

        on<RenderEvent.World> {
            // Cheap early-outs before we lock + snapshot.
            val pending = synchronized(trailsLock) { trails.isEmpty() }
            if (pending) return@on
            val threshold = minPoints.toInt()
            val lineColour = if (rainbow) Colour.RAINBOW else beamColour
            val yShift = yOffset
            val lineThickness = thickness.toFloat()
            val depthTest = !throughWalls

            // Hold the deque lock just long enough to copy the list of
            // trails — the inner per-trail point snapshots are taken
            // without holding it.
            val snapshot = synchronized(trailsLock) { trails.toList() }
            for (trail in snapshot) {
                if (trail.size() < threshold) continue
                val pts = trail.snapshotPoints()
                val final = if (yShift == 0.0) pts
                            else pts.map { it.add(0.0, yShift, 0.0) }
                ctx.drawLine(final, lineColour, depth = depthTest, thickness = lineThickness)
            }
        }
    }

    /** Per-packet append logic — extend the most recent trail if the new
     *  point arrived recently AND continues its direction, otherwise start
     *  a new trail. */
    private fun ingestParticle(point: Vec3, tick: Int) {
        synchronized(trailsLock) {
            val tail = trails.peekLast()
            if (tail != null && (tick - tail.tickLastUpdate) <= MAX_INTER_PARTICLE_TICKS &&
                fitsTrailDirection(tail, point)
            ) {
                tail.appendPoint(point, tick)
                return
            }
            trails.addLast(Trail(tick).also { it.appendPoint(point, tick) })
        }
    }

    /** Sliding-window direction match: compare the candidate's direction
     *  against the average of the last [DIRECTION_WINDOW] points. */
    private fun fitsTrailDirection(trail: Trail, candidate: Vec3): Boolean {
        val pts = trail.snapshotPoints()
        if (pts.size <= 1) return true

        val tail = pts.takeLast(DIRECTION_WINDOW)
        val windowStart = tail.first()
        val windowEnd = tail.last()
        val windowDir = windowEnd.subtract(windowStart).normalizeOrZero() ?: return true
        val candidateDir = candidate.subtract(windowEnd).normalizeOrZero() ?: return true

        return windowDir.dot(candidateDir) >= SAME_DIRECTION_COS
    }

    /** Defensive normalize — Vec3.normalize() throws on zero vectors in
     *  some MC versions. */
    private fun Vec3.normalizeOrZero(): Vec3? {
        val l = this.length()
        return if (l < 1e-6) null else this.scale(1.0 / l)
    }
}
