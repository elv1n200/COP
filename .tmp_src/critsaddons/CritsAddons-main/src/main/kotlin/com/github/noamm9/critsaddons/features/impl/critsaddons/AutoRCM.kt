package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.TextInputSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.itemUUID
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.item.ItemStack

object AutoRCM : Feature(
    name = "Auto RCM",
    description = "When right-clicking a selected trigger item, swaps to another selected item, right-clicks, then swaps back."
) {
    private val selectionSection = "selection"
    private val timingSection = "timing"

    private val onlyInDungeons by ToggleSetting("Only In Dungeons", true)
        .section(selectionSection)
        .withDescription("Only run Auto RCM while in dungeons.")

    private val triggerItemKey by TextInputSetting("Trigger Item", "")
        .section(selectionSection)
        .withDescription("Item UUID/SkyBlock ID that triggers Auto RCM.")

    private val swapItemKey by TextInputSetting("Swap Item", "")
        .section(selectionSection)
        .withDescription("Item UUID/SkyBlock ID to swap to for the extra right-click.")

    private val setTriggerFromHeld by ButtonSetting("Set Trigger From Held") {
        setKeyFromHeld(isTrigger = true)
    }.section(selectionSection)
        .withDescription("Uses the currently held item (UUID/SkyBlock ID) as Trigger Item.")

    private val setSwapFromHeld by ButtonSetting("Set Swap From Held") {
        setKeyFromHeld(isTrigger = false)
    }.section(selectionSection)
        .withDescription("Uses the currently held item (UUID/SkyBlock ID) as Swap Item.")

    private val preSwapDelayMs by SliderSetting("Pre Swap Delay (ms)", 35, 0, 500, 5)
        .section(timingSection)
        .withDescription("Delay after your trigger right-click before swapping.")

    private val postSwapClickDelayMs by SliderSetting("Post Swap Click Delay (ms)", 35, 0, 500, 5)
        .section(timingSection)
        .withDescription("Delay between swapping to swap item and sending right-click.")

    private val returnDelayMs by SliderSetting("Return Delay (ms)", 35, 0, 500, 5)
        .section(timingSection)
        .withDescription("Delay before swapping back to your original slot.")

    private val waitForCooldown by ToggleSetting("Wait For CD", true)
        .section(timingSection)
        .withDescription("While holding right-click, waits for the swap item's cooldown and casts when ready.")

    private var isExecuting = false
    private var ignoreUntil = 0L
    private var lastMissingItemMessageAt = 0L
    private var rightClickConsumed = false

    private enum class TriggerResult {
        NOT_MATCHED,
        STARTED
    }

    override fun init() {
        register<TickEvent.Start> {
            if (!mc.options.keyUse.isDown) {
                rightClickConsumed = false
            }
        }

        register<PlayerInteractEvent.RIGHT_CLICK.AIR> {
            handleRightClick(event.item)
        }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            handleRightClick(event.item)
        }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY> {
            handleRightClick(event.item)
        }
    }

    override fun onDisable() {
        rightClickConsumed = false
        super.onDisable()
    }

    private fun handleRightClick(item: ItemStack?) {
        if (rightClickConsumed) return

        when (handleTrigger(item)) {
            TriggerResult.STARTED -> rightClickConsumed = true
            TriggerResult.NOT_MATCHED -> Unit
        }
    }

    private fun handleTrigger(item: ItemStack?): TriggerResult {
        if (!enabled) return TriggerResult.NOT_MATCHED
        if (onlyInDungeons.value && !LocationUtils.inDungeon) return TriggerResult.NOT_MATCHED
        if (mc.screen != null) return TriggerResult.NOT_MATCHED
        if (isExecuting) return TriggerResult.NOT_MATCHED
        if (System.currentTimeMillis() < ignoreUntil) return TriggerResult.NOT_MATCHED

        val triggerKeyValue = triggerItemKey.value.trim()
        val swapKeyValue = swapItemKey.value.trim()
        if (triggerKeyValue.isEmpty() || swapKeyValue.isEmpty()) return TriggerResult.NOT_MATCHED

        val held = item ?: mc.player?.mainHandItem ?: return TriggerResult.NOT_MATCHED
        if (!matchesKey(held, triggerKeyValue)) return TriggerResult.NOT_MATCHED
        val originalSlot = currentHeldHotbarSlot() ?: return TriggerResult.NOT_MATCHED
        val swapSlot = findHotbarSlotByKey(swapKeyValue)
        if (swapSlot == null) {
            maybeMissingItemMessage("&cAuto RCM: swap item is not on your hotbar.")
            return TriggerResult.NOT_MATCHED
        }
        if (swapSlot == originalSlot) return TriggerResult.NOT_MATCHED

        val swapStack = PlayerUtils.getHotbarSlot(swapSlot) ?: return TriggerResult.NOT_MATCHED
        if (waitForCooldown.value && CooldownDisplay.isOnCooldown(swapStack)) {
            return TriggerResult.NOT_MATCHED
        }

        isExecuting = true
        scope.launch {
            try {
                delay(preSwapDelayMs.value.toLong())
                PlayerUtils.swapToSlot(swapSlot)
                delay(postSwapClickDelayMs.value.toLong())
                PlayerUtils.rightClick()
                CooldownDisplay.startRightClickCooldown(swapStack)
                delay(returnDelayMs.value.toLong())
                PlayerUtils.swapToSlot(originalSlot)
                ignoreUntil = System.currentTimeMillis() + 100L
            } finally {
                isExecuting = false
            }
        }

        return TriggerResult.STARTED
    }

    private fun setKeyFromHeld(isTrigger: Boolean) {
        val held = mc.player?.mainHandItem ?: return ChatUtils.modMessage("&cHold an item first.")
        val key = itemKey(held) ?: return ChatUtils.modMessage("&cCould not read item UUID/SkyBlock ID from held item.")

        if (isTrigger) {
            triggerItemKey.value = key
            ChatUtils.modMessage("&aAuto RCM trigger set to &e$key&a.")
        } else {
            swapItemKey.value = key
            ChatUtils.modMessage("&aAuto RCM swap item set to &e$key&a.")
        }
    }

    private fun itemKey(stack: ItemStack): String? {
        return stack.itemUUID.ifBlank { stack.skyblockId }.takeIf { it.isNotBlank() }
    }

    private fun matchesKey(stack: ItemStack, key: String): Boolean {
        val normalizedKey = normalizeKey(key) ?: return false
        return normalizeKey(stack.itemUUID) == normalizedKey || normalizeKey(stack.skyblockId) == normalizedKey
    }

    private fun findHotbarSlotByKey(key: String): Int? {
        return PlayerUtils.findHotbarSlot { stack -> matchesKey(stack, key) }
    }

    private fun currentHeldHotbarSlot(): Int? {
        val held = mc.player?.mainHandItem ?: return null
        for (slot in 0..8) {
            if (PlayerUtils.getHotbarSlot(slot) === held) return slot
        }

        val heldUuid = normalizeKey(held.itemUUID)
        if (!heldUuid.isNullOrBlank()) {
            for (slot in 0..8) {
                val candidate = normalizeKey(PlayerUtils.getHotbarSlot(slot)?.itemUUID)
                if (!candidate.isNullOrBlank() && candidate == heldUuid) return slot
            }
        }

        val heldId = normalizeKey(held.skyblockId)
        if (!heldId.isNullOrBlank()) {
            for (slot in 0..8) {
                val candidate = normalizeKey(PlayerUtils.getHotbarSlot(slot)?.skyblockId)
                if (!candidate.isNullOrBlank() && candidate == heldId) return slot
            }
        }

        return null
    }

    private fun normalizeKey(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    }

    private fun maybeMissingItemMessage(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastMissingItemMessageAt < 1_000L) return
        lastMissingItemMessageAt = now
        ChatUtils.modMessage(message)
    }
}
