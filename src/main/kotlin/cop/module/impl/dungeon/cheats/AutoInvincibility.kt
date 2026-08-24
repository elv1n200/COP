package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.item.Items
import cop.CopMod.scope
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.SkyblockPlayer
import cop.api.skyblock.SkyblockPlayer.InvincibilityType
import cop.api.skyblock.SkyblockPlayer.Mask
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.ContainerUtils
import cop.utils.skyblock.player.MovementUtils.stop
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult

/**
 * Complete Spirit -> Phoenix -> Bonzo survival chain. Mask and pet actions are
 * independent COP implementations and do not modify the legacy Auto Mask module.
 */
object AutoInvincibility : Module(
    "Auto Invincibility",
    area = Island.Dungeon,
    desc = "Chains available Spirit Mask, Phoenix Pet and Bonzo's Mask invincibility."
) {
    private val useSpiritMask by switch("Spirit Mask", true, desc = "Equips Spirit Mask when it is the next available save.")
    private val usePhoenixPet by switch("Phoenix Pet", true, desc = "Swaps to Phoenix when both its cooldown and setup allow it.")
    private val useBonzoMask by switch("Bonzo's Mask", true, desc = "Equips Bonzo's Mask when it is the next available save.")
    private val phoenixMethod by selector(
        "Phoenix method",
        "Pet Menu",
        listOf("Pet Menu", "Rod Swap"),
        desc = "Pet Menu is reliable; Rod Swap requires a matching Autopet rule."
    ).childOf(::usePhoenixPet)
    private val swapDelay by slider("Swap delay", 0, 0, 40, 1, unit = "t", desc = "Delay after an invincibility proc.")
    private val bossOnly by switch("Boss only", desc = "Only chains saves in dungeon boss rooms.")
    private val p3Only by switch("Phase 3 only", desc = "Only chains saves during Goldor's terminal phase.")
    private val preventMoving by switch("Prevent moving", true, desc = "Stops movement during hidden equipment and pet-menu actions.")
    private val restorePet by switch("Restore previous pet", true, desc = "Restores the previous pet after the chain ends or Phoenix does not proc.")
    private val phoenixTimeout by slider("Phoenix timeout", 5.0f, 2.0f, 10.0f, 0.5f, unit = "s", desc = "Restores the old pet if Phoenix does not proc in time.")
        .childOf(::restorePet)
    private val statusMessages by switch("Status messages", true, desc = "Reports chain actions and failures in chat.")

    private var actionJob: Job? = null
    private var pendingProc: InvincibilityType? = null
    private var previousPet: String? = null
    private var phoenixRestorePending = false
    private var phoenixWatchId = 0
    private var worldEpoch = 0
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    init {
        on<WorldEvent.Change> {
            worldEpoch++
            resetAll()
        }

        on<TickEvent.Start> {
            if (preventMoving && actionJob?.isActive == true) mc.player?.stop()
        }

        on<ChatEvent.PacketClient> {
            if (bossOnly && !Dungeon.inBoss) return@on
            if (p3Only && !Dungeon.inP3) return@on
            if (Dungeon.isDead) return@on

            val plain = message.noControlCodes
            val proc = InvincibilityType.entries.firstOrNull { plain.matches(it.regex) } ?: return@on
            if (proc == InvincibilityType.PHOENIX) {
                phoenixWatchId++
                phoenixRestorePending = restorePet && !previousPet.isNullOrBlank()
            }
            if (actionJob?.isActive == true) {
                // A save can proc while the previous hidden menu action is
                // finishing. Queue it instead of silently breaking the chain.
                pendingProc = proc
                return@on
            }

            startChain(proc, worldEpoch)
        }
    }

    override fun onDisable() {
        resetAll()
        super.onDisable()
    }

    private fun startChain(proc: InvincibilityType, epoch: Int) {
        val candidates = availableCandidates(proc)
        if (candidates.isEmpty() && !(restorePet && isPhoenixEquipped() && previousPet != null)) {
            status("&cNo unused invincibility save is available.")
            return
        }

        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                delay(swapDelay * 50L)
                if (!waitUntilSafe(epoch)) return@launch

                if (!AutomationCoordinator.acquire(
                        OWNER,
                        12_000L,
                        Channel.INVENTORY,
                        Channel.MOVEMENT,
                        Channel.HOTBAR,
                        Channel.INTERACTION
                    )) {
                    status("&cInvincibility swap skipped: another automation is active.")
                    return@launch
                }

                for (candidate in candidates) {
                    if (!stillAvailable(candidate, proc)) continue
                    val success = when (candidate) {
                        InvincibilityType.SPIRIT -> equipMask("Spirit Mask")
                        InvincibilityType.BONZO -> equipMask("Bonzo's Mask")
                        InvincibilityType.PHOENIX -> equipPhoenix()
                    }
                    if (success) {
                        if (candidate == InvincibilityType.PHOENIX) {
                            startPhoenixWatch(epoch)
                        } else if (proc == InvincibilityType.PHOENIX && restorePet && isPhoenixEquipped()) {
                            if (restorePreviousPet()) phoenixRestorePending = false
                        }
                        return@launch
                    }
                }

                if (restorePet && isPhoenixEquipped()) {
                    if (restorePreviousPet()) phoenixRestorePending = false
                }
                else status("&cNo configured invincibility swap succeeded.")
            } finally {
                AutomationCoordinator.release(OWNER)
            }
        }

        actionJob = job
        job.invokeOnCompletion { mc.execute { finishAction(job, epoch) } }
        job.start()
    }

    private fun availableCandidates(proc: InvincibilityType): List<InvincibilityType> = PRIORITY.filter {
        stillAvailable(it, proc)
    }

    private fun stillAvailable(type: InvincibilityType, proc: InvincibilityType): Boolean {
        if (type == proc || type.currentCooldown > 0) return false
        return when (type) {
            InvincibilityType.SPIRIT -> useSpiritMask && SkyblockPlayer.currentMask != Mask.SPIRIT
            InvincibilityType.BONZO -> useBonzoMask && SkyblockPlayer.currentMask != Mask.BONZO
            InvincibilityType.PHOENIX -> usePhoenixPet && !isPhoenixEquipped()
        }
    }

    /** True only when this module will perform an action for this exact proc in
     * the current dungeon state. Legacy Auto Mask uses this to remain a valid
     * fallback without racing the full invincibility chain. */
    internal fun willHandleProc(proc: InvincibilityType): Boolean {
        if (!enabled || !Dungeon.inDungeons || Dungeon.isDead) return false
        if (bossOnly && !Dungeon.inBoss) return false
        if (p3Only && !Dungeon.inP3) return false

        return availableCandidates(proc).isNotEmpty() ||
            (restorePet && isPhoenixEquipped() && !previousPet.isNullOrBlank())
    }

    private suspend fun equipMask(maskName: String): Boolean {
        status("&eEquipping $maskName.")
        val success = ContainerUtils.getContainerItemsClick(
            command = "eq",
            container = "Your Equipment and Stats",
            name = maskName,
            inContainer = false,
            shift = true,
            timeout = 40,
            cancelReopen = true,
            closeAfterClick = true,
        )
        delay(100L)
        if (!success) status("&cFailed to equip $maskName.")
        return success
    }

    private suspend fun equipPhoenix(): Boolean {
        val current = SkyblockPlayer.currentPet.trim()
        if (current.isNotEmpty() && !current.contains("Phoenix", ignoreCase = true)) {
            previousPet = current
        }

        val success = when (phoenixMethod.selected) {
            "Rod Swap" -> rodSwap()
            else -> switchPet("Phoenix")
        }
        if (success) status("&eSwapped to Phoenix Pet.")
        else status("&cFailed to swap to Phoenix Pet.")
        return success
    }

    private suspend fun switchPet(name: String): Boolean {
        val success = ContainerUtils.getContainerItemsClick(
            command = "petsmenu",
            container = "Pets",
            name = name,
            lore = "Left-click to summon!",
            inContainer = true,
            timeout = 40,
            closeAfterClick = true,
        )
        delay(100L)
        return success
    }

    private suspend fun rodSwap(): Boolean {
        val inventory = mc.player?.inventory ?: return false
        val rodSlot = (0..8).firstOrNull { slot ->
            val stack = inventory.getItem(slot)
            stack.item == Items.FISHING_ROD && stack.skyblockId !in ROD_BLACKLIST
        } ?: return false

        val originalSlot = inventory.selectedSlot
        val swap = SwapManager.swapToSlot(rodSlot)
        if (swap != SwapResult.SUCCESS && swap != SwapResult.ALREADY_SELECTED) return false
        if (swap == SwapResult.SUCCESS) delay(50L)

        val wasCast = mc.player?.fishing != null
        mc.player?.rightClick()
        if (wasCast) {
            delay(200L)
            mc.player?.rightClick()
        }
        delay(50L)
        SwapManager.swapToSlot(originalSlot)
        return true
    }

    private fun startPhoenixWatch(epoch: Int) {
        val watchId = ++phoenixWatchId
        val timeoutTicks = (phoenixTimeout * 20f).toInt().coerceAtLeast(1)
        scheduleTask(timeoutTicks) {
            if (!enabled || epoch != worldEpoch || watchId != phoenixWatchId || Dungeon.isDead) return@scheduleTask
            if (!restorePet || !isPhoenixEquipped() || InvincibilityType.PHOENIX.currentCooldown > 0) return@scheduleTask
            if (actionJob?.isActive == true) return@scheduleTask
            startRestore(epoch, "&cPhoenix did not proc; restoring your previous pet.")
        }
    }

    private fun startRestore(epoch: Int, reason: String) {
        val pet = previousPet?.takeIf { it.isNotBlank() } ?: return
        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            if (!AutomationCoordinator.acquire(OWNER, 8_000L, Channel.INVENTORY, Channel.MOVEMENT)) {
                return@launch
            }
            try {
                if (!waitUntilSafe(epoch) || !isPhoenixEquipped()) return@launch
                status(reason)
                if (switchPet(pet)) {
                    previousPet = null
                    phoenixRestorePending = false
                }
                else status("&cFailed to restore $pet.")
            } finally {
                AutomationCoordinator.release(OWNER)
            }
        }
        actionJob = job
        job.invokeOnCompletion { mc.execute { finishAction(job, epoch) } }
        job.start()
    }

    private suspend fun restorePreviousPet(): Boolean {
        val pet = previousPet?.takeIf { it.isNotBlank() } ?: return false
        status("&eRestoring $pet.")
        val success = switchPet(pet)
        if (success) {
            previousPet = null
            phoenixRestorePending = false
        }
        return success
    }

    private suspend fun waitUntilSafe(epoch: Int): Boolean {
        var waited = 0
        while ((Dungeon.inTerminal || mc.screen != null) && waited++ < 600) {
            if (!enabled || epoch != worldEpoch || Dungeon.isDead) return false
            delay(50L)
        }
        return enabled && epoch == worldEpoch && !Dungeon.isDead && !Dungeon.inTerminal && mc.screen == null
    }

    private fun isPhoenixEquipped() = SkyblockPlayer.currentPet.contains("Phoenix", ignoreCase = true)

    private fun status(message: String) {
        if (statusMessages) modMessage(message)
    }

    private fun finishAction(job: Job, epoch: Int) {
        if (actionJob !== job) return
        actionJob = null

        val queued = pendingProc
        pendingProc = null
        if (queued != null && enabled && epoch == worldEpoch && !Dungeon.isDead) {
            startChain(queued, epoch)
            return
        }

        // A confirmed Phoenix proc invalidates the no-proc watchdog. If the
        // following mask action exited before it could restore the old pet,
        // make one independent fallback attempt after that action has ended.
        if (phoenixRestorePending && restorePet && enabled && epoch == worldEpoch &&
            !Dungeon.isDead && isPhoenixEquipped() && !previousPet.isNullOrBlank()
        ) {
            phoenixRestorePending = false
            startRestore(epoch, "&ePhoenix proc completed; restoring your previous pet.")
        }
    }

    private fun resetAll() {
        phoenixWatchId++
        pendingProc = null
        phoenixRestorePending = false
        actionJob?.cancel()
        actionJob = null
        previousPet = null
        AutomationCoordinator.release(OWNER)
    }

    private const val OWNER = "AutoInvincibility"
    private val PRIORITY = listOf(InvincibilityType.SPIRIT, InvincibilityType.PHOENIX, InvincibilityType.BONZO)
    private val ROD_BLACKLIST = setOf("SOUL_WHIP", "FLAMING_FLAY", "GRAPPLING_HOOK")
}
