package cop.module.impl.player

import cop.api.events.TickEvent
import cop.api.skyblock.Island
import cop.api.skyblock.SkyblockPlayer
import cop.module.Module
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.rightClick
import net.minecraft.world.item.Items
import kotlin.random.Random

/**
 * Auto-uses the soulcry ability of any Voidgloom-Seraph katana (Voidedge,
 * Vorpal or Atomsplit) while it's the active hotbar item. Costs 200 mana
 * (or 100 with Ultimate Wise V — checked at click time via the live mana
 * counter, so we don't need to inspect the enchant ourselves).
 *
 * Lighter than Nebulune's port — drops the `SlayerAPI` dependency since
 * any time the player is holding one of these katanas on The End it's
 * effectively a slayer scenario.
 *
 * @author elvin
 */
object AutoSoulcry : Module(
    "Auto Soulcry",
    area = Island.TheEnd,
    desc = "Auto-fires the soulcry ability of voidgloom katanas while held.",
) {
    private val checkMana by switch(
        "Check mana", true,
        desc = "Wait until you have enough mana before firing. Disable to spam-click regardless.",
    )
    private val manaThreshold by slider(
        "Mana cost", 200, 50, 400, 10, unit = "mp",
        desc = "Target mana cost — 200 default; lower it to ~100 if you have Ultimate Wise V.",
    )
    private val minTickDelay by slider("Min delay", 1, 0, 10, 1, unit = "t")
    private val maxTickDelay by slider("Max delay", 3, 0, 10, 1, unit = "t")

    private val katanaIds = setOf("VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA")
    private var tickCounter = -1

    init {
        on<TickEvent.Start> {
            val player = mc.player ?: return@on reset()
            if (mc.screen != null) return@on reset()

            val held = player.mainHandItem
            if (held.isEmpty || held.item != Items.DIAMOND_SWORD) return@on reset()
            if (held.skyblockId !in katanaIds) return@on reset()

            if (checkMana && (SkyblockPlayer.mana + SkyblockPlayer.overflowMana) < manaThreshold) {
                return@on reset()
            }

            // First eligible tick — pick a random delay in [min, max].
            if (tickCounter == -1) {
                val lo = minTickDelay
                val hi = maxTickDelay.coerceAtLeast(lo)
                tickCounter = if (lo == hi) lo else Random.nextInt(lo, hi + 1)
                return@on
            }

            if (tickCounter-- > 0) return@on

            player.rightClick()
            reset()
        }
    }

    private fun reset() { tickCounter = -1 }
}
