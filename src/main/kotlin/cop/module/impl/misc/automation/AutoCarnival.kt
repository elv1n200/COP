package cop.module.impl.misc.automation

import cop.api.events.BlockEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.module.Module
import cop.utils.EntityUtils.getEntities
import cop.utils.getDirection
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.ROTATION
import cop.utils.skyblock.player.PlayerUtils.useItem
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Automatically leads and shoots valid targets in the Carnival Zombie Shootout. */
object AutoCarnival : Module(
    "Auto Carnival",
    area = Island.Hub,
    subarea = "carnival",
    desc = "Automatically shoots valid Zombie Shootout targets with movement prediction.",
) {
    private val clickDelay by slider(
        "Shot delay",
        120L,
        50L,
        500L,
        10L,
        unit = "ms",
        desc = "Minimum delay between dart shots.",
    )
    private val leadTicks by slider(
        "Target lead",
        8.0,
        0.0,
        14.0,
        0.5,
        unit = "t",
        desc = "How far ahead of moving zombies to aim.",
    )
    private val lampPriority by switch(
        "Lamp priority",
        true,
        desc = "Shoots an active bonus lamp before zombies.",
    )

    private var lastShotAt = 0L
    private var activeLamp: Vec3? = null

    init {
        on<TickEvent.End> {
            if (mc.screen != null || player.mainHandItem.skyblockId != "CARNIVAL_DART_TUBE") return@on
            val now = System.currentTimeMillis()
            if (now - lastShotAt < clickDelay) return@on

            val target = targets().firstOrNull() ?: return@on
            if (!AutomationCoordinator.acquire(OWNER, clickDelay.coerceAtLeast(75L), ROTATION, INTERACTION)) return@on

            player.useItem(getDirection(player.eyePosition, target))
            lastShotAt = now
        }

        on<BlockEvent.Update> {
            if (old.block !is RedstoneLampBlock || updated.block !is RedstoneLampBlock) return@on
            if (player.blockPosition().distSqr(pos) > TARGET_RANGE_SQUARED) return@on
            val wasLit = old.getValue(BlockStateProperties.LIT)
            val isLit = updated.getValue(BlockStateProperties.LIT)
            activeLamp = when {
                !wasLit && isLit -> pos.center
                wasLit && !isLit && (activeLamp?.distanceToSqr(pos.center) ?: Double.MAX_VALUE) < 1.0 -> null
                else -> activeLamp
            }
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun targets(): List<Vec3> {
        val helmetOrder = listOf(Items.DIAMOND_HELMET, Items.GOLDEN_HELMET, Items.IRON_HELMET, Items.LEATHER_HELMET)
        val zombies = getEntities<Zombie>(TARGET_RANGE) { !it.isDeadOrDying && player.hasLineOfSight(it) }
            .groupBy { it.getItemBySlot(EquipmentSlot.HEAD).item }

        return buildList {
            if (lampPriority) activeLamp?.takeIf(::hasClearShot)?.let(::add)
            helmetOrder.forEach { helmet ->
                zombies[helmet].orEmpty().forEach { zombie ->
                    val movement = zombie.deltaMovement
                    val predicted = Vec3(
                        zombie.x + movement.x * leadTicks,
                        zombie.y + zombie.eyeHeight + movement.y.coerceAtLeast(0.0) * leadTicks,
                        zombie.z + movement.z * leadTicks,
                    )
                    if (hasClearShot(predicted)) add(predicted)
                }
            }
            if (!lampPriority) activeLamp?.takeIf(::hasClearShot)?.let(::add)
        }
    }

    private fun hasClearShot(target: Vec3): Boolean {
        if (player.eyePosition.distanceToSqr(target) > TARGET_RANGE_SQUARED) return false
        val hit = level.clip(
            ClipContext(player.eyePosition, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player),
        )
        return hit.type == HitResult.Type.MISS || hit.location.distanceToSqr(target) <= 1.0
    }

    private fun reset() {
        activeLamp = null
        lastShotAt = 0L
        AutomationCoordinator.release(OWNER)
    }

    private const val OWNER = "Auto Carnival"
    private const val TARGET_RANGE = 50.0
    private const val TARGET_RANGE_SQUARED = TARGET_RANGE * TARGET_RANGE
}
