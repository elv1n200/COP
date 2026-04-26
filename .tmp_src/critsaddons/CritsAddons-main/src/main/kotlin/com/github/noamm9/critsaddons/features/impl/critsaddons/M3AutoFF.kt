package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.PlayerUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.item.ItemStack

object M3AutoFF : Feature(
    name = "M3 Auto FF",
    description = "Swaps to Fire Freeze Staff on the Professor trigger, right-clicks when the timer ends, then swaps back."
) {
    private const val PROFESSOR_FIRE_FREEZE_LINE =
        "[BOSS] The Professor: Even if you took my barrier down, I can still fight."
    private const val FIRE_FREEZE_DELAY_MS = 5000L
    private const val RETURN_DELAY_MS = 75L

    private var isExecuting = false
    private var lastTriggerAtMs = 0L
    private var runId = 0
    private var lastMissingStaffMessageAt = 0L

    override fun init() {
        register<ChatMessageEvent> {
            handleChat(event.unformattedText.removeFormatting())
        }

        register<WorldChangeEvent> {
            reset()
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun handleChat(message: String) {
        if (!enabled) return
        if (message != PROFESSOR_FIRE_FREEZE_LINE) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < 10_000L) return
        if (isExecuting) return

        val originalSlot = currentHeldHotbarSlot() ?: return
        val fireFreezeSlot = PlayerUtils.findHotbarSlot { it.isFireFreezeStaff() }
        if (fireFreezeSlot == null) {
            maybeMissingStaffMessage()
            return
        }

        lastTriggerAtMs = now
        startAutoFireFreeze(originalSlot, fireFreezeSlot)
    }

    private fun startAutoFireFreeze(originalSlot: Int, fireFreezeSlot: Int) {
        val currentRun = ++runId
        isExecuting = true

        scope.launch {
            try {
                PlayerUtils.swapToSlot(fireFreezeSlot)
                delay(FIRE_FREEZE_DELAY_MS)

                if (!enabled || currentRun != runId) return@launch
                PlayerUtils.swapToSlot(fireFreezeSlot)
                PlayerUtils.rightClick()
                CooldownDisplay.startRightClickCooldown(PlayerUtils.getHotbarSlot(fireFreezeSlot))

                delay(RETURN_DELAY_MS)
                if (enabled && currentRun == runId) {
                    PlayerUtils.swapToSlot(originalSlot)
                }
            } finally {
                if (currentRun == runId) isExecuting = false
            }
        }
    }

    private fun currentHeldHotbarSlot(): Int? {
        val held = mc.player?.mainHandItem ?: return null
        for (slot in 0..8) {
            if (PlayerUtils.getHotbarSlot(slot) === held) return slot
        }
        return mc.player?.inventory?.selectedSlot?.takeIf { it in 0..8 }
    }

    private fun ItemStack.isFireFreezeStaff(): Boolean {
        if (isEmpty) return false
        return hoverName.string.removeFormatting().contains("Fire Freeze Staff", ignoreCase = true)
    }

    private fun maybeMissingStaffMessage() {
        val now = System.currentTimeMillis()
        if (now - lastMissingStaffMessageAt < 1_000L) return
        lastMissingStaffMessageAt = now
        ChatUtils.modMessage("&cM3 Auto FF: Fire Freeze Staff is not on your hotbar.")
    }

    private fun reset() {
        runId++
        isExecuting = false
        lastTriggerAtMs = 0L
    }
}
