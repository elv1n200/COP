package cop.module.impl.player

import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.EntityUtils.getEntities
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.PlayerUtils.rightClick

/**
 * Port of Nebulune `FishingHelper` (xyz.aerii.nebulune.modules.impl.general.FishingHelper).
 *
 * Two behaviours — both optional:
 *   - Auto-pull when the Hypixel "!!!" bite indicator spawns near your bobber.
 *   - Auto-recast: re-cast whenever the player has a rod but no bobber in the world
 *     (with an optional check that only fires if the rod is currently idle).
 *
 * The original uses an entity-nametag-update event. COP doesn't have that, so instead
 * we poll every tick for ArmorStands named "!!!" within 2 blocks of the bobber — cheap,
 * and functionally identical in practice.
 */
object FishingHelper : Module(
    "Fishing Helper",
    desc = "Auto-pull on bite + auto-recast fishing rod."
) {
    private val autoPull by switch("Auto pull", true,
        desc = "Auto-right-clicks when the '!!!' indicator spawns near your bobber.")
    private val pullDelay by slider("Pull delay", 1, 0, 5, 1, unit = "t",
        desc = "Base delay before pulling.")
    private val pullVariance by slider("Pull variance", 0, 0, 3, 1, unit = "t",
        desc = "Random extra delay (0..n) for humanisation.")

    private val autoRecast by switch("Auto recast",
        desc = "Re-cast the rod after pulling.")
    private val recastCheck by switch("Recast check", true,
        desc = "Also periodically recasts if the rod is sitting idle in-hand with no bobber.")
    private val recastDelay by slider("Recast delay", 1, 0, 10, 1, unit = "t",
        desc = "Delay after pulling before re-casting.")
    private val recastVariance by slider("Recast variance", 0, 0, 5, 1, unit = "t")

    private var pullQueued = false

    init {
        on<WorldEvent.Change> { pullQueued = false }

        on<TickEvent.End> {
            if (!autoPull) return@on
            val player = mc.player ?: return@on
            val hook = player.fishing ?: return@on
            if (pullQueued) return@on

            // Hypixel spawns an armor stand with the '!!!' component stacked on the bobber
            val hit = getEntities<ArmorStand>(hook.position(), 2.0) { stand ->
                val name = stand.customName?.string?.noControlCodes ?: return@getEntities false
                name.trim() == "!!!"
            }.firstOrNull() ?: return@on
            hit // referenced to satisfy kotlin's unused check

            pullQueued = true
            val delay = (pullDelay + if (pullVariance > 0) (0..pullVariance).random() else 0).coerceAtLeast(0)

            Scheduler.scheduleTask(delay) {
                (mc.player ?: return@scheduleTask).rightClick()
                if (!autoRecast) {
                    pullQueued = false
                    return@scheduleTask
                }
                val rec = 2 + recastDelay + if (recastVariance > 0) (0..recastVariance).random() else 0
                Scheduler.scheduleTask(rec) {
                    (mc.player ?: return@scheduleTask).rightClick()
                    pullQueued = false
                }
            }
        }

        // Periodic sanity-recast every 15 seconds (300 ticks) if the rod is idle
        Scheduler.scheduleLoop(15 * 20) {
            if (!enabled) return@scheduleLoop
            if (!autoRecast || !recastCheck) return@scheduleLoop
            val player = mc.player ?: return@scheduleLoop
            if (player.fishing != null) return@scheduleLoop
            if (player.mainHandItem.item != Items.FISHING_ROD) return@scheduleLoop
            player.rightClick()
        }
    }
}
