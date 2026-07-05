package cop.module.impl.dungeon.cheats.autoclear

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/** Radius (blocks) a Wither-Impact / Hyperion cast covers. A cluster is valid
 *  only while every member stays within this of the cluster centroid. */
const val HYPE_AOE = 6.0

/**
 * Groups starred dungeon mobs into the fewest Hyperion casts and orders those
 * casts into a short walk.
 *
 * Ported from quoi (`quoi.module.impl.dungeon.autoclear.MobClusterer`,
 * pigeonlover1998 — see CREDITS), adapted to COP's vanilla vec/pos utils. The
 * clustering uses the *centroid-recompute* variant: a mob only joins a cluster
 * if the recomputed centroid still keeps the whole group inside [HYPE_AOE], so
 * clusters stay genuinely coverable by one cast instead of drifting.
 */
object MobClusterer {

    /** Group [mobs] into [MobCluster]s each coverable by a single cast. */
    fun cluster(mobs: List<LivingEntity>): List<MobCluster> {
        val clusters = mutableListOf<MobCluster>()

        for (mob in mobs) {
            var added = false

            for (cluster in clusters) {
                val candidates = cluster.mobs + mob
                val centre = getMiddle(candidates)

                val fits = candidates.all { it.position().distanceTo(Vec3.atCenterOf(centre)) <= HYPE_AOE }
                if (fits) {
                    cluster.mobs.add(mob)
                    cluster.pos = centre.below()
                    added = true
                    break
                }
            }

            if (!added) {
                clusters.add(MobCluster(getMiddle(listOf(mob)).below(), mutableListOf(mob)))
            }
        }

        return clusters
    }

    /** Nearest-neighbour ordering of [clusters] starting from [from]. */
    fun greedyOrder(from: Vec3, clusters: List<MobCluster>): List<MobCluster> {
        if (clusters.isEmpty()) return emptyList()

        val remaining = clusters.toMutableList()
        val sorted = mutableListOf<MobCluster>()
        var curr = from

        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull { Vec3.atCenterOf(it.pos.below()).distanceTo(curr) }!!
            sorted.add(nearest)
            remaining.remove(nearest)
            curr = Vec3.atCenterOf(nearest.pos)
        }

        return sorted
    }

    /** [cluster] then [greedyOrder] in one call. */
    fun getOrderedClusters(from: Vec3, mobs: List<LivingEntity>): List<MobCluster> =
        greedyOrder(from, cluster(mobs))

    private fun getMiddle(mobs: List<LivingEntity>): BlockPos {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (mob in mobs) {
            val p = mob.position()
            x += p.x; y += p.y; z += p.z
        }
        val n = mobs.size
        return BlockPos.containing(x / n, y / n, z / n)
    }
}
