package cop.module.impl.player

import net.minecraft.client.KeyMapping
import cop.api.events.MouseEvent
import cop.mixins.accessors.KeyMappingAccessor
import cop.module.Module
import cop.utils.Scheduler
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.rightClick

/**
 * Port of Nebulune `EtherwarpHelper` (xyz.aerii.nebulune.modules.impl.general.EtherwarpHelper).
 *
 * Converts left-click into an etherwarp trigger whenever you're holding an
 * etherwarp-capable item (Wither impact items with the ETHERWARP_CONDUIT component
 * or Hyperion-class swords with the `ethermerge` attribute).
 *
 *   - If you're already sneaking: LMB fires a use-item (the warp).
 *   - If you're NOT sneaking and "Shift automatically" is on: we press SHIFT for a few
 *     ticks, fire the warp, then release SHIFT.
 */
object EtherwarpHelper : Module(
    "Etherwarp Helper",
    desc = "Left-click warps with a Wither Impact / Hyperion-class item."
) {
    private val leftClickWarp by switch("Left click warp", true,
        desc = "Fires an etherwarp when you LMB while holding a compatible item.")
    private val shiftAuto by switch("Shift automatically",
        desc = "If you're not already sneaking, briefly press shift so the warp lands.")
    private val shiftHoldTicks by slider("Shift hold (ticks)", 3, 2, 6, 1,
        desc = "How long to hold shift before firing the warp.", unit = "t")

    init {
        on<MouseEvent.Click> {
            if (!leftClickWarp) return@on
            if (button != 0 || !state) return@on          // LMB press only
            if (mc.screen != null) return@on

            val player = mc.player ?: return@on
            val held = player.mainHandItem

            val isConduit = held.skyblockId == "ETHERWARP_CONDUIT"
            val hasMerge = held.extraAttributes?.getInt("ethermerge")?.orElse(0) == 1
            if (!isConduit && !hasMerge) return@on

            cancel()

            if (player.isShiftKeyDown) {
                player.rightClick()
                return@on
            }

            if (!shiftAuto) return@on

            val shiftKey = (mc.options.keyShift as KeyMappingAccessor).key
            KeyMapping.set(shiftKey, true)

            Scheduler.scheduleTask(shiftHoldTicks) {
                (mc.player ?: return@scheduleTask).rightClick()
                Scheduler.scheduleTask(1) { KeyMapping.set(shiftKey, false) }
            }
        }
    }
}
