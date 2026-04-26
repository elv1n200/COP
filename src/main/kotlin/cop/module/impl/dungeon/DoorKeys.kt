package cop.module.impl.dungeon

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.decoration.ArmorStand
import cop.api.colour.Colour
import cop.api.colour.alpha
import cop.api.colour.withAlpha
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.EntityUtils.interpolatedBox
import cop.utils.StringUtils.noControlCodes
import cop.utils.render.drawFilledBox
import cop.utils.render.drawTracer
import cop.utils.render.drawWireFrameBox

/**
 * Port of NoammAddons DoorKeys (com.github.noamm9.features.impl.dungeon.DoorKeys).
 * Tracks wither/blood key armor stands in dungeon clears and draws a box + tracer.
 */
object DoorKeys : Module(
    "Door Keys",
    area = Island.Dungeon(inClear = true),
    desc = "ESP box & tracer for wither doors and blood door keys."
) {
    private val witherKey by switch("Wither Key", true)
    private val witherColour by colourPicker("Wither Key Colour", Colour.BLACK.withAlpha(60), true).childOf(::witherKey)

    private val bloodKey by switch("Blood Key", true)
    private val bloodColour by colourPicker("Blood Key Colour", Colour.RED.withAlpha(60), true).childOf(::bloodKey)

    private val outline by switch("Outline", true)
    private val thickness by slider("Outline thickness", 2.0, 0.5, 6.0, 0.5).childOf(::outline)
    private val depth by switch("Depth check")
    private val tracer by switch("Tracer", true)

    private data class TrackedKey(val entity: ArmorStand, val colour: Colour)

    private var tracked: TrackedKey? = null

    init {
        on<WorldEvent.Change> { tracked = null }

        on<PacketEvent.Received, ClientboundSetEntityDataPacket> {
            if (Dungeon.inBoss) return@on
            val entity = mc.level?.getEntity(packet.id) as? ArmorStand ?: return@on
            val name = entity.customName?.string?.noControlCodes ?: return@on

            tracked = when {
                witherKey && name == "Wither Key" -> TrackedKey(entity, witherColour)
                bloodKey && name == "Blood Key" -> TrackedKey(entity, bloodColour)
                else -> return@on
            }
        }

        on<RenderEvent.World> {
            val t = tracked ?: return@on
            if (!t.entity.isAlive || t.entity.isRemoved) {
                tracked = null
                return@on
            }

            val box = t.entity.interpolatedBox
                .inflate(-0.1, 0.2, -0.1)
                .move(0.0, 1.2, 0.0)

            if (t.colour.alpha > 0) ctx.drawFilledBox(box, t.colour, depth)
            if (outline) ctx.drawWireFrameBox(box, t.colour.withAlpha(255), thickness.toFloat(), depth)

            if (tracer) {
                val eye = box.center
                ctx.drawTracer(eye, t.colour.withAlpha(255), 2f, depth)
            }
        }
    }
}
