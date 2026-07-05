package cop.module.impl.dungeon.cheats.autoclear

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity

/**
 * A single Hyperion kill zone: one wither-blade cast lands at [pos] and hits
 * every mob in [mobs] (all within [HYPE_AOE] of the cast centre).
 *
 * Ported from quoi (`quoi.module.impl.dungeon.autoclear.MobCluster`,
 * pigeonlover1998 — see CREDITS), adapted to COP's entity utils.
 */
data class MobCluster(
    var pos: BlockPos,
    val mobs: MutableList<LivingEntity>,
)
