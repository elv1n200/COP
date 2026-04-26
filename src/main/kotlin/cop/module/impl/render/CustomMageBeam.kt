package cop.module.impl.render

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import cop.api.colour.Colour
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.render.drawLine

/**
 * Port of Hunchclient CustomMageBeam (dev.hunchclient.module.impl.render.CustomMageBeamModule) —
 * simplified to match COP's world render helpers.
 *
 * Intercepts firework particle packets (the ones that draw the mage beam trail) and renders a
 * custom polyline through the captured points. Vanilla particles can be hidden to avoid the
 * double-trail. Advanced Hunch styles (helix / wave / dashed) are dropped because they require
 * a dedicated batched-line renderer that COP does not expose.
 */
object CustomMageBeam : Module(
    "Custom Mage Beam",
    desc = "Replaces the firework particle trail of mage beams with a smooth custom line."
) {
    private val beamColour by colourPicker("Beam colour", Colour.PINK, allowAlpha = true,
        desc = "Line colour (ignored when Rainbow is on).")
    private val rainbow by switch("Rainbow", false,
        desc = "Cycle beam colour through the rainbow.")
    private val thickness by slider("Thickness", 2.5, 0.5, 10.0, 0.1,
        desc = "Line thickness in pixels.")
    private val durationTicks by slider("Duration", 40.0, 10.0, 100.0, 1.0,
        desc = "How long beams stay visible after the last particle (ticks).")
    private val minPoints by slider("Min points", 8.0, 2.0, 20.0, 1.0,
        desc = "Skip rendering until we have at least this many collected points.")
    private val throughWalls by switch("Through walls", true,
        desc = "Render the beam without depth testing.")
    private val hideParticles by switch("Hide particles", true,
        desc = "Cancel the vanilla firework particle packet so only the custom line shows.")
    private val yOffset by slider("Y Offset", 0.0, -1.0, 1.0, 0.05,
        desc = "Vertical tweak in blocks, handy if the line sits above/below eye level.")

    private class Beam(var lastUpdateTick: Int) {
        val points = ArrayList<Vec3>()
        val creationTick: Int = lastUpdateTick
    }

    private val activeBeams = ArrayList<Beam>()
    private var currentTick = 0

    init {
        on<WorldEvent.Change> {
            activeBeams.clear()
            currentTick = 0
        }

        on<TickEvent.End> {
            currentTick++
            activeBeams.removeAll { (currentTick - it.creationTick) > durationTicks.toInt() }
        }

        on<PacketEvent.Received, ClientboundLevelParticlesPacket> {
            if (packet.particle.type != ParticleTypes.FIREWORK) return@on
            val newPoint = Vec3(packet.x, packet.y, packet.z)

            val recent = activeBeams.lastOrNull()
            if (recent != null &&
                (currentTick - recent.lastUpdateTick) < 2 &&
                isPointInBeamDirection(recent.points, newPoint)
            ) {
                recent.points.add(newPoint)
                recent.lastUpdateTick = currentTick
            } else {
                val beam = Beam(currentTick)
                beam.points.add(newPoint)
                activeBeams.add(beam)
            }

            if (hideParticles) cancel()
        }

        on<RenderEvent.World> {
            if (activeBeams.isEmpty()) return@on
            val minCount = minPoints.toInt()
            val colour = if (rainbow) Colour.RAINBOW else beamColour
            val yShift = yOffset

            // Snapshot to avoid CME if packet handler fires while we iterate.
            val snapshot = activeBeams.toList()
            for (beam in snapshot) {
                if (beam.points.size < minCount) continue
                val points = if (yShift == 0.0) beam.points
                             else beam.points.map { it.add(0.0, yShift, 0.0) }
                ctx.drawLine(points, colour, depth = !throughWalls, thickness = thickness.toFloat())
            }
        }
    }

    private fun isPointInBeamDirection(points: List<Vec3>, newPoint: Vec3): Boolean {
        if (points.size <= 1) return true
        val first = points.first()
        val last = points.last()
        val direction = last.subtract(first).normalize()
        val toNew = newPoint.subtract(last).normalize()
        return direction.dot(toNew) > 0.95
    }
}
