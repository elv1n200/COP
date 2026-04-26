package cop.module.impl.dungeon

import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.Giant
import cop.api.events.TickEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.EntityUtils.getEntities

/**
 * Port of NoammAddons HiddenMobs (com.github.noamm9.features.impl.dungeon.HiddenMobs).
 * Reveals invisible Fels / Shadow Assassins / Stealthy Watcher mobs / armored Giants
 * by force-clearing their client-side invisibility flag each tick.
 */
object HiddenMobs : Module(
    "Hidden Mobs",
    area = Island.Dungeon,
    desc = "Reveals invisible Fels, Shadow Assassins, stealthy watcher mobs, and giants in dungeons."
) {
    private val showFels by switch("Show Fels", true, desc = "Reveals invisible Fels (Dinnerbone endermen).")
    private val showSa by switch("Show Shadow Assassins", true, desc = "Reveals invisible Shadow Assassins.")
    private val showStealthy by switch("Show Stealthy", true, desc = "Reveals invisible watcher mobs / armored giants.")

    // A pragmatic watcher mob-name allowlist (subset of Noamm's watcherMobsNames.json).
    // When Hypixel renames them the list just needs to be extended.
    private val watcherNames = setOf(
        "Shadow Assassin",
        "Diamond Guy",
        "Lost Adventurer",
        "Team Treasurite",
        "Dreadlord",
        "Withermancer",
        "Zombie Commander",
        "Skeleton Grunt",
        "Skeleton Soldier",
        "Skeleton Master",
        "Super Archer",
        "Super Tank",
        "Sniper",
        "King Midas",
        "Scared Skeleton"
    )

    init {
        on<TickEvent.End> {
            if (!showFels && !showSa && !showStealthy) return@on
            if (!Dungeon.inDungeons) return@on

            getEntities<LivingEntity>().forEach { entity ->
                if (!entity.isInvisible) return@forEach
                val name = entity.name.string.trim()

                val isFel = showFels && entity is EnderMan && name == "Dinnerbone"
                val isSA = showSa && entity is AbstractClientPlayer && name.contains("Shadow Assassin")
                val isWatcherMob = showStealthy && entity is AbstractClientPlayer && watcherNames.any { name == it }
                val isGiant = showStealthy && entity is Giant && !entity.getItemBySlot(EquipmentSlot.FEET).isEmpty

                if (isFel || isSA || isWatcherMob || isGiant) entity.isInvisible = false
            }
        }
    }
}
