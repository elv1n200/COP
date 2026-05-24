package cop.module.impl.dungeon.cheats
import cop.module.impl.dungeon.huds.CooldownDisplay

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.item.ItemStack
import cop.CopMod.scope
import cop.api.events.MouseEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.ItemUtils.skyblockUuid
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.SwapManager

/**
 * Port of CritsAddons `AutoRCM` (com.github.noamm9.critsaddons.features.impl.critsaddons.AutoRCM).
 *
 * "RCM" = Right-Click Mage. When you right-click while holding the *trigger* item,
 * this swaps to a *swap* item, fires a right-click, then swaps back — useful for
 * chaining a staff cast off of your wand. You configure both items by UUID or
 * SkyBlock ID (easiest via the buttons that read your held item).
 *
 * Requires the sibling `CooldownDisplay` module for the "wait for CD" option to work.
 */
object AutoRCM : Module(
    "Auto RCM",
    area = Island.Dungeon,
    desc = "When you right-click a trigger item, auto-swaps to a chosen item, right-clicks, swaps back."
) {
    private val onlyInDungeons by switch("Only in dungeons", true,
        desc = "Restricts Auto RCM to dungeons only.")

    private val triggerItemKey by textInput("Trigger item", "",
        desc = "Item UUID or SkyBlock ID that triggers the swap-click-swap combo.")
    private val swapItemKey by textInput("Swap item", "",
        desc = "Item UUID or SkyBlock ID to swap to for the auto right-click.")

    private val setTriggerFromHeld by button("Set trigger from held",
        desc = "Copies your held item's UUID/ID into the trigger field.") {
        setKeyFromHeld(isTrigger = true)
    }
    private val setSwapFromHeld by button("Set swap from held",
        desc = "Copies your held item's UUID/ID into the swap field.") {
        setKeyFromHeld(isTrigger = false)
    }

    private val preSwapDelayMs by slider("Pre-swap delay", 35, 0, 500, 5, unit = "ms")
    private val postSwapClickDelayMs by slider("Post-swap delay", 35, 0, 500, 5, unit = "ms")
    private val returnDelayMs by slider("Return delay", 35, 0, 500, 5, unit = "ms")

    private val waitForCooldown by switch("Wait for CD", true,
        desc = "While holding right-click, waits for the swap item's cooldown and fires when ready.")

    private var currentJob: Job? = null
    private var ignoreUntil = 0L
    private var lastMissingItemMessageAt = 0L
    private var rightClickConsumed = false

    init {
        on<MouseEvent.Click> {
            if (button != 1) return@on          // RMB
            if (!state) {                       // release → reset debounce
                rightClickConsumed = false
                return@on
            }

            if (rightClickConsumed) return@on
            if (!tryStart()) return@on

            rightClickConsumed = true
        }

        on<WorldEvent.Change> {
            currentJob?.cancel()
            currentJob = null
            rightClickConsumed = false
            ignoreUntil = 0L
        }
    }

    /** @return true if a swap was started (cancels further re-entry). */
    private fun tryStart(): Boolean {
        if (onlyInDungeons && !inDungeons) return false
        if (mc.screen != null) return false
        if (currentJob?.isActive == true) return false
        if (System.currentTimeMillis() < ignoreUntil) return false

        val triggerKey = triggerItemKey.trim()
        val swapKey = swapItemKey.trim()
        if (triggerKey.isEmpty() || swapKey.isEmpty()) return false

        val player = mc.player ?: return false
        val held = player.mainHandItem
        if (!matchesKey(held, triggerKey)) return false

        val originalSlot = player.inventory.selectedSlot.takeIf { it in 0..8 } ?: return false
        val swapSlot = findHotbarSlotByKey(swapKey)
        if (swapSlot == null) {
            maybeMissingItemMessage("&cAuto RCM: swap item is not on your hotbar.")
            return false
        }
        if (swapSlot == originalSlot) return false

        val swapStack = player.inventory.getItem(swapSlot)
        if (waitForCooldown && CooldownDisplay.isOnCooldown(swapStack)) return false

        currentJob = scope.launch {
            delay(preSwapDelayMs.toLong())
            SwapManager.swapToSlot(swapSlot)
            delay(postSwapClickDelayMs.toLong())
            mc.player?.rightClick()
            CooldownDisplay.startRightClickCooldown(
                mc.player?.inventory?.getItem(swapSlot)
            )
            delay(returnDelayMs.toLong())
            SwapManager.swapToSlot(originalSlot)
            ignoreUntil = System.currentTimeMillis() + 100L
        }
        return true
    }

    private fun setKeyFromHeld(isTrigger: Boolean) {
        val held = mc.player?.mainHandItem
        if (held == null || held.isEmpty) {
            modMessage("&cHold an item first.")
            return
        }
        val key = itemKey(held)
        if (key == null) {
            modMessage("&cCould not read UUID/SkyBlock ID from held item.")
            return
        }

        // COP's textInput delegate is read-only; users paste the key into the text field.
        // We surface it in chat for easy copy.
        modMessage(
            "&a${if (isTrigger) "Trigger" else "Swap"} key for held item: &e$key&a (paste into the '${
                if (isTrigger) "Trigger item" else "Swap item"
            }' field)."
        )
    }

    private fun itemKey(stack: ItemStack): String? =
        stack.skyblockUuid?.takeIf { it.isNotBlank() }
            ?: stack.skyblockId?.takeIf { it.isNotBlank() }

    private fun matchesKey(stack: ItemStack, key: String): Boolean {
        val normalizedKey = normalizeKey(key) ?: return false
        return normalizeKey(stack.skyblockUuid) == normalizedKey
            || normalizeKey(stack.skyblockId) == normalizedKey
    }

    private fun findHotbarSlotByKey(key: String): Int? {
        val player = mc.player ?: return null
        for (i in 0..8) {
            if (matchesKey(player.inventory.getItem(i), key)) return i
        }
        return null
    }

    private fun normalizeKey(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    private fun maybeMissingItemMessage(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastMissingItemMessageAt < 1_000L) return
        lastMissingItemMessageAt = now
        modMessage(message)
    }
}
