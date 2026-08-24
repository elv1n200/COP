package cop.module.impl.dungeon.cheats

import cop.api.events.MouseEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.module.impl.dungeon.huds.CooldownDisplay
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.HOTBAR
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.PlayerUtils
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult
import net.minecraft.world.item.ItemStack

object AutoRCM : Module(
    "Auto RCM",
    desc = "Uses a configured hotbar ability after a right click with a configured trigger item."
) {
    private val triggerItem by textInput(
        "Trigger item",
        "Terminator",
        length = 80,
        desc = "Display-name fragment or SkyBlock key. Prefix with name:, id:, or key: to force one form."
    )
    private val abilityItem by textInput(
        "Ability item",
        "Hyperion",
        length = 80,
        desc = "Hotbar item to swap to and right click. Accepts the same name/key syntax as Trigger item."
    )
    private val onlyInDungeons by switch(
        "Only in dungeons",
        true,
        desc = "Requires an active Catacombs instance before accepting a trigger."
    )
    private val respectCooldown by switch(
        "Respect cooldown",
        true,
        desc = "Skips the ability while its client-side cooldown is active."
    )
    private val preDelay by slider(
        "Pre delay",
        0,
        0,
        20,
        1,
        unit = "t",
        desc = "Ticks to wait after the user's right click before swapping."
    )
    private val clickDelay by slider(
        "Click delay",
        1,
        0,
        20,
        1,
        unit = "t",
        desc = "Ticks to wait between the hotbar swap and the ability click."
    )
    private val returnDelay by slider(
        "Return delay",
        1,
        0,
        20,
        1,
        unit = "t",
        desc = "Ticks to wait after the ability click before restoring the original slot."
    )

    private enum class Stage { PRE_SWAP, CLICK, RETURN }

    private data class Action(
        val originalSlot: Int,
        var abilitySlot: Int,
        var dueAt: Long,
        var stage: Stage = Stage.PRE_SWAP,
        var swapped: Boolean = false
    )

    private var action: Action? = null
    private var deferredRestore: Scheduler.Task? = null
    private var worldEpoch = 0

    init {
        on<MouseEvent.Click> {
            if (button != 1 || !state || action != null || deferredRestore != null) return@on
            if (!validEnvironment() || mc.screen != null) return@on

            val localPlayer = mc.player ?: return@on
            if (!matches(localPlayer.mainHandItem, triggerItem)) return@on

            val slot = findAbilitySlot() ?: return@on
            val stack = localPlayer.inventory.getItem(slot)
            if (respectCooldown && CooldownDisplay.isOnCooldown(stack)) return@on

            val original = localPlayer.inventory.selectedSlot.takeIf { it in 0..8 } ?: return@on
            val leaseMillis = ((preDelay + clickDelay + returnDelay + 10L) * 50L).coerceAtLeast(1_000L)
            if (!AutomationCoordinator.acquire(OWNER, leaseMillis, HOTBAR, INTERACTION)) return@on

            action = Action(
                originalSlot = original,
                abilitySlot = slot,
                dueAt = System.currentTimeMillis() + preDelay * 50L
            )
        }

        on<TickEvent.End> { processAction() }

        on<WorldEvent.Change> {
            worldEpoch++
            cancelAction(restore = false)
        }
    }

    override fun onDisable() {
        cancelAction(restore = true)
        super.onDisable()
    }

    private fun processAction() {
        val pending = action ?: return
        if (!validEnvironment() || mc.screen != null) {
            cancelAction(restore = pending.swapped)
            return
        }
        if (!AutomationCoordinator.extend(OWNER, 1_000L, HOTBAR, INTERACTION)) {
            cancelAction(restore = false)
            return
        }

        val now = System.currentTimeMillis()
        if (now < pending.dueAt) return

        when (pending.stage) {
            Stage.PRE_SWAP -> swapForAbility(pending, now)
            Stage.CLICK -> clickAbility(pending, now)
            Stage.RETURN -> restoreAfterAbility(pending, now)
        }
    }

    private fun swapForAbility(pending: Action, now: Long) {
        val localPlayer = mc.player ?: return cancelAction(restore = false)
        if (localPlayer.inventory.selectedSlot != pending.originalSlot ||
            !matches(localPlayer.mainHandItem, triggerItem)
        ) {
            cancelAction(restore = false)
            return
        }

        if (!matches(localPlayer.inventory.getItem(pending.abilitySlot), abilityItem)) {
            pending.abilitySlot = findAbilitySlot() ?: return cancelAction(restore = false)
        }

        val abilityStack = localPlayer.inventory.getItem(pending.abilitySlot)
        if (respectCooldown && CooldownDisplay.isOnCooldown(abilityStack)) {
            cancelAction(restore = false)
            return
        }

        when (SwapManager.swapToSlot(pending.abilitySlot)) {
            SwapResult.SUCCESS, SwapResult.ALREADY_SELECTED -> {
                pending.swapped = pending.abilitySlot != pending.originalSlot
                pending.stage = Stage.CLICK
                pending.dueAt = now + clickDelay * 50L
            }
            SwapResult.TOO_FAST -> pending.dueAt = now + 50L
            SwapResult.NOT_FOUND, SwapResult.FAILED -> cancelAction(restore = false)
        }
    }

    private fun clickAbility(pending: Action, now: Long) {
        val localPlayer = mc.player ?: return cancelAction(restore = pending.swapped)
        val stack = localPlayer.mainHandItem
        if (localPlayer.inventory.selectedSlot != pending.abilitySlot || !matches(stack, abilityItem)) {
            cancelAction(restore = pending.swapped)
            return
        }
        if (respectCooldown && CooldownDisplay.isOnCooldown(stack)) {
            cancelAction(restore = pending.swapped)
            return
        }

        PlayerUtils.interact()
        CooldownDisplay.startRightClickCooldown(stack)
        pending.stage = Stage.RETURN
        pending.dueAt = now + returnDelay * 50L
    }

    private fun restoreAfterAbility(pending: Action, now: Long) {
        when (SwapManager.swapToSlot(pending.originalSlot)) {
            SwapResult.SUCCESS, SwapResult.ALREADY_SELECTED -> completeAction()
            SwapResult.TOO_FAST -> pending.dueAt = now + 50L
            SwapResult.NOT_FOUND, SwapResult.FAILED -> completeAction()
        }
    }

    private fun validEnvironment(): Boolean =
        enabled && mc.level != null && mc.player != null && (!onlyInDungeons || Dungeon.inDungeons)

    private fun findAbilitySlot(): Int? {
        val inventory = mc.player?.inventory ?: return null
        return (0..8).firstOrNull { matches(inventory.getItem(it), abilityItem) }
    }

    private fun matches(stack: ItemStack, configured: String): Boolean {
        if (stack.isEmpty) return false
        val raw = configured.trim()
        if (raw.isEmpty()) return false

        val separator = raw.indexOf(':')
        val mode = if (separator > 0) raw.substring(0, separator).trim().lowercase() else ""
        val value = if (mode in MATCH_MODES) raw.substring(separator + 1).trim() else raw
        if (value.isEmpty()) return false

        val displayMatches = stack.hoverName.string.noControlCodes.contains(value, ignoreCase = true)
        val normalizedKey = value.replace(' ', '_').replace('-', '_')
        val keyMatches = stack.skyblockId?.equals(normalizedKey, ignoreCase = true) == true

        return when (mode) {
            "name" -> displayMatches
            "id", "key" -> keyMatches
            else -> displayMatches || keyMatches
        }
    }

    private fun completeAction() {
        action = null
        AutomationCoordinator.release(OWNER)
    }

    private fun cancelAction(restore: Boolean) {
        deferredRestore?.cancel()
        deferredRestore = null

        val pending = action
        action = null
        val hotbarOwner = AutomationCoordinator.owner(HOTBAR)
        val restoreIsSafe = hotbarOwner == null || hotbarOwner == OWNER
        if (!restore || !restoreIsSafe || pending == null || !pending.swapped || mc.player == null) {
            AutomationCoordinator.release(OWNER)
            return
        }

        when (SwapManager.swapToSlot(pending.originalSlot)) {
            SwapResult.TOO_FAST -> scheduleRestore(pending.originalSlot)
            else -> AutomationCoordinator.release(OWNER)
        }
    }

    private fun scheduleRestore(slot: Int) {
        val expectedWorld = mc.level
        val expectedEpoch = worldEpoch
        if (!AutomationCoordinator.extend(OWNER, 500L, HOTBAR, INTERACTION)) {
            AutomationCoordinator.release(OWNER)
            return
        }
        deferredRestore = Scheduler.scheduleTaskHandle(1) {
            deferredRestore = null
            if (worldEpoch == expectedEpoch && mc.level === expectedWorld && mc.player != null) {
                SwapManager.swapToSlot(slot)
            }
            AutomationCoordinator.release(OWNER)
        }
    }

    private val MATCH_MODES = setOf("name", "id", "key")
    private const val OWNER = "dungeon-auto-rcm"
}
