package cop.module.impl.dungeon.cheats
import cop.module.impl.dungeon.huds.CooldownDisplay

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cop.CopMod.scope
import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.SwapManager
import cop.utils.StringUtils.noControlCodes

/**
 * Port of CritsAddons `M3AutoFF` (com.github.noamm9.critsaddons.features.impl.critsaddons.M3AutoFF).
 *
 * On the Professor's Fire Freeze trigger line, this swaps to your Fire Freeze Staff,
 * waits ~5 seconds, right-clicks to freeze, then swaps back. If the staff isn't on
 * your hotbar it prints a chat warning (throttled to once per second).
 */
object M3AutoFF : Module(
    "M3 Auto FF",
    area = Island.Dungeon(3, inBoss = true),
    desc = "Auto-casts Fire Freeze Staff on the Professor's trigger line."
) {
    private const val PROFESSOR_FIRE_FREEZE_LINE =
        "[BOSS] The Professor: Even if you took my barrier down, I can still fight."
    private const val FIRE_FREEZE_DELAY_MS = 5000L
    private const val RETURN_DELAY_MS = 75L

    private var lastTriggerAtMs = 0L
    private var lastMissingStaffMessageAt = 0L
    private var currentJob: Job? = null

    init {
        on<ChatEvent.Receive> {
            if (message != PROFESSOR_FIRE_FREEZE_LINE) return@on

            val now = System.currentTimeMillis()
            if (now - lastTriggerAtMs < 10_000L) return@on
            if (currentJob?.isActive == true) return@on

            val player = mc.player ?: return@on
            val originalSlot = player.inventory.selectedSlot.takeIf { it in 0..8 } ?: return@on
            val fireFreezeSlot = findFireFreezeSlot()
            if (fireFreezeSlot == null) {
                maybeMissingStaffMessage()
                return@on
            }

            lastTriggerAtMs = now
            currentJob = scope.launch {
                SwapManager.swapToSlot(fireFreezeSlot)
                delay(FIRE_FREEZE_DELAY_MS)
                if (!enabled) return@launch

                SwapManager.swapToSlot(fireFreezeSlot)
                mc.player?.rightClick()
                CooldownDisplay.startRightClickCooldown(
                    mc.player?.inventory?.getItem(fireFreezeSlot)
                )

                delay(RETURN_DELAY_MS)
                if (enabled) SwapManager.swapToSlot(originalSlot)
            }
        }

        on<WorldEvent.Change> {
            currentJob?.cancel()
            currentJob = null
            lastTriggerAtMs = 0L
        }
    }

    private fun findFireFreezeSlot(): Int? {
        val player = mc.player ?: return null
        for (i in 0..8) {
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) continue
            if (stack.hoverName.string.noControlCodes.contains("Fire Freeze Staff", ignoreCase = true)) {
                return i
            }
        }
        return null
    }

    private fun maybeMissingStaffMessage() {
        val now = System.currentTimeMillis()
        if (now - lastMissingStaffMessageAt < 1_000L) return
        lastMissingStaffMessageAt = now
        modMessage("&cM3 Auto FF: Fire Freeze Staff is not on your hotbar.")
    }
}
