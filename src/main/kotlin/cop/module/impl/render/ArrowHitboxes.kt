package cop.module.impl.render

import net.minecraft.world.entity.projectile.AbstractArrow
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.RenderEvent
import cop.module.Module
import cop.utils.EntityUtils.interpolatedBox
import cop.utils.render.drawWireFrameBox

/**
 * Port of Athen `ArrowHitboxes` (xyz.aerii.athen.modules.impl.render.ArrowHitboxes).
 * Draws a 3D wireframe box around every in-flight arrow (and tipped/spectral arrows).
 */
object ArrowHitboxes : Module(
    "Arrow Hitboxes",
    desc = "Draws a hitbox around every arrow in flight."
) {
    private val colour by colourPicker("Colour", Colour.GREEN.withAlpha(255), true)
    private val thickness by slider("Thickness", 2f, 1f, 10f, 0.5f)
    private val depth by switch("Depth check", true)

    init {
        on<RenderEvent.World> {
            val level = mc.level ?: return@on
            for (entity in level.entitiesForRendering()) {
                if (entity !is AbstractArrow) continue
                if (!entity.isAlive || entity.isRemoved) continue
                ctx.drawWireFrameBox(entity.interpolatedBox, colour, thickness, depth)
            }
        }
    }
}
