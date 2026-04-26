package cop.module.impl.dungeon

import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.module.Module

// Kyleen
object FullBlockHitboxes : Module(
    "Full Block Hitboxes",
    desc = "Increases the hitboxes of buttons, chests, levers, and skulls to be 1x1 blocks."
) {
    @JvmStatic val shouldExpandHitboxes: Boolean get() = enabled && inDungeons
}