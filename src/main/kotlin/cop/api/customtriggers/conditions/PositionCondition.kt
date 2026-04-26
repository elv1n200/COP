package cop.api.customtriggers.conditions

import cop.CopMod.mc
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.customtriggers.TriggerContext
import cop.config.TypeName
import cop.utils.ThemeManager.theme
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

@TypeName("player_position")
class PositionCondition(var aabb: AABB = AABB(BlockPos(0, 0, 0))) : TriggerCondition {

    override fun matches(ctx: TriggerContext): Boolean {
        val player = mc.player ?: return false
        return aabb.intersects(player.boundingBox)
    }

    override fun displayString(): String {
        val x = (aabb.minX + aabb.maxX) / 2.0
        val y = (aabb.minY + aabb.maxY) / 2.0
        val z = (aabb.minZ + aabb.maxZ) / 2.0
        val size = "${aabb.maxX - aabb.minX}x${aabb.maxY - aabb.minY}x${aabb.maxZ - aabb.minZ}"
        return "Player at $x,$y,$z [$size]"
    }

    override fun ElementScope<*>.draw() = column(size(w = Copying)) {
        text(
            string = "Position",
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
        )
    }
}