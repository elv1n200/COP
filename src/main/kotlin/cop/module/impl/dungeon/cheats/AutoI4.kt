package cop.module.impl.dungeon.cheats

import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import cop.api.events.BlockEvent
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.dungeon.P3Section
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.getDirection
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.LeapManager
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.PlayerUtils.useItem
import cop.utils.skyblock.player.RotationUtils.cancelRotationTask
import cop.utils.skyblock.player.RotationUtils.rotate
import cop.utils.skyblock.player.RotationUtils.rotateSmoothly
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult
import kotlin.math.abs

/**
 * Fully automated fourth Goldor device (I4).
 *
 * The implementation is intentionally stateful: every rotation and support
 * action is tied to the current world/session generation, uses automation
 * leases, validates the target again before clicking, and is cancelled as soon
 * as the player leaves the device or the world changes.
 */
object AutoI4 : Module(
    "Auto I4",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Automates I4 aiming, prediction and optional rod/mask/leap support."
) {
    private const val AIM_OWNER = "dungeon-auto-i4-aim"
    private const val ROD_OWNER = "dungeon-auto-i4-rod"
    private const val STORM_DEATH_MESSAGE = "[BOSS] Storm: I should have known that I stood no chance."

    private val aimingHeader by text("I4 aiming")
    private val rotationTime by slider(
        "Rotation time",
        170,
        0,
        350,
        5,
        unit = "ms",
        desc = "Smooth-rotation duration; 0 snaps immediately."
    ).childOf(::aimingHeader)
    private val predictions by switch(
        "Predict next target",
        true,
        desc = "Pre-fires one likely blue target after a confirmed emerald target."
    ).childOf(::aimingHeader)
    private val retryDelay by slider(
        "Stall retry",
        500,
        200,
        1500,
        50,
        unit = "ms",
        desc = "Retries an emerald target if the server did not accept the shot."
    ).childOf(::aimingHeader)

    private val supportHeader by text("I4 support actions")
    private val autoRod by switch("Auto rod", true).childOf(::supportHeader)
    private val autoMask by switch("Auto mask", true).childOf(::supportHeader)
    private val maskName by selector(
        "Mask",
        "Bonzo's Mask",
        listOf("Bonzo's Mask", "Spirit Mask")
    ).childOf(::autoMask)
    private val autoLeap by switch("Auto leap", true).childOf(::supportHeader)
    private val leapToMelody by switch(
        "Prefer Melody",
        desc = "Leaps to a teammate whose party message contains Melody progress."
    ).childOf(::autoLeap)
    private val leapPriority by selector(
        "Leap priority",
        DungeonClass.Tank,
        listOf(DungeonClass.Tank, DungeonClass.Mage, DungeonClass.Healer, DungeonClass.Archer),
        desc = "Preferred fallback class after I4."
    ).childOf(::autoLeap)
    private val feedback by switch("Feedback", true)

    private data class ShotRequest(val pos: BlockPos, val predicted: Boolean)

    private enum class RodStage { IDLE, CAST, RESTORE }

    private val doneCoords = linkedSetOf<BlockPos>()
    private val predictionAttempts = hashMapOf<BlockPos, Int>()
    private val scheduledTasks = linkedSetOf<Scheduler.Task>()

    private var sessionGeneration = 0
    private var aimToken = 0
    private var runTick = -1
    private var deviceCompleted = false
    private var activeEmerald: BlockPos? = null
    private var activeAim: ShotRequest? = null
    private var pendingShot: ShotRequest? = null
    private var retryAfterMs = 0L
    private var lastShotAttemptAt = 0L
    private var scanTicker = 0

    private var rodDone = false
    private var maskDone = false
    private var leapDone = false
    private var melodyTargetName: String? = null

    private var rodStage = RodStage.IDLE
    private var rodDelayTicks = 0
    private var rodRestoreSlot = -1
    private var rodRestoreRetries = 0

    init {
        on<ChatEvent.PacketClient> {
            val raw = message.noControlCodes.trim()
            parseMelodyTarget(raw)?.let { melodyTargetName = it }

            when {
                raw == STORM_DEATH_MESSAGE -> startSession()
                raw.contains("completed a device!") &&
                    runTick >= 0 &&
                    isI4Environment(requireDevice = false) &&
                    completedBySelf(raw) -> completeSession()
            }
        }

        on<TickEvent.Server> {
            if (runTick < 0) return@on
            runTick++
            if (runTick > 700) {
                runTick = -1
                return@on
            }
            if (!isOnDevice()) return@on

            if (autoRod && !rodDone && runTick >= 174) startRodAction()
            if (autoMask && !maskDone && runTick >= 244) startMaskAction()
            if (autoLeap && !leapDone && runTick >= 307) startLeapAction()
        }

        on<BlockEvent.Update> {
            if (pos !in DEVICE_BLOCKS || !isI4Environment(requireDevice = true)) return@on
            val oldBlock = old.block
            val newBlock = updated.block

            if (oldBlock == Blocks.EMERALD_BLOCK && newBlock == Blocks.BLUE_TERRACOTTA) {
                doneCoords.add(pos)
                if (activeEmerald == pos) activeEmerald = null
                if (activeAim?.pos == pos) cancelAim()
                if (pendingShot?.pos == pos) pendingShot = null
                return@on
            }

            if (newBlock != Blocks.EMERALD_BLOCK) return@on
            activeEmerald = pos

            // A real emerald always wins over a speculative rotation.
            if (activeAim?.predicted == true && activeAim?.pos != pos) cancelAim()
            requestShot(ShotRequest(pos, predicted = false), trustBlockEvent = true)
        }

        on<TickEvent.End> {
            processRodAction()

            if (!isI4Environment(requireDevice = true)) {
                if (activeAim != null) cancelAim()
                pendingShot = null
                return@on
            }
            if (mc.screen != null) return@on

            val now = System.currentTimeMillis()
            pendingShot?.takeIf { now >= retryAfterMs }?.let {
                pendingShot = null
                requestShot(it)
            }

            // Reconcile twice per second in case a block update arrived before
            // the module was enabled or an emerald remained after a lost click.
            if (++scanTicker % 10 != 0) return@on
            val emerald = activeEmerald
                ?.takeIf { blockAt(it) == Blocks.EMERALD_BLOCK && it !in doneCoords }
                ?: DEVICE_BLOCKS.firstOrNull { it !in doneCoords && blockAt(it) == Blocks.EMERALD_BLOCK }
                ?: return@on

            activeEmerald = emerald
            if (activeAim == null && now - lastShotAttemptAt >= retryDelay) {
                requestShot(ShotRequest(emerald, predicted = false))
            }
        }

        on<WorldEvent.Change> { reset(worldChanged = true) }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun startSession() {
        reset()
        deviceCompleted = false
        runTick = 0
    }

    private fun completeSession() {
        deviceCompleted = true
        runTick = -1
        cancelAim()
        pendingShot = null
        if (autoLeap && !leapDone) startLeapAction()
    }

    private fun requestShot(request: ShotRequest, trustBlockEvent: Boolean = false) {
        if (!isI4Environment(requireDevice = true) || mc.screen != null || !isValidWeapon()) return
        if (!trustBlockEvent && !request.isStillValid()) return
        if (activeAim?.pos == request.pos) return
        if (activeAim != null) {
            pendingShot = request
            retryAfterMs = System.currentTimeMillis() + 50L
            return
        }

        val leaseMs = rotationTime.toLong() + 750L
        if (!AutomationCoordinator.acquire(AIM_OWNER, leaseMs, Channel.ROTATION, Channel.INTERACTION)) {
            pendingShot = request
            retryAfterMs = System.currentTimeMillis() + 100L
            return
        }

        val player = mc.player ?: return AutomationCoordinator.release(AIM_OWNER)
        val direction = getDirection(player.eyePosition, targetVector(request.pos))
        val token = ++aimToken
        val generation = sessionGeneration
        activeAim = request
        lastShotAttemptAt = System.currentTimeMillis()

        if (rotationTime <= 0) {
            player.rotate(direction)
            finishShot(request, generation, token)
        } else {
            player.rotateSmoothly(direction, rotationTime.toFloat()) {
                finishShot(request, generation, token)
            }
            val watchdogTicks = ((rotationTime + 300) / 50).coerceAtLeast(2)
            schedule(watchdogTicks, generation) {
                if (token != aimToken || activeAim != request) return@schedule
                cancelAim()
                if (request.isStillValid()) {
                    pendingShot = request
                    retryAfterMs = System.currentTimeMillis() + 100L
                }
            }
        }
    }

    private fun finishShot(request: ShotRequest, generation: Int, token: Int) {
        if (generation != sessionGeneration || token != aimToken || activeAim != request) return

        activeAim = null
        if (!isI4Environment(requireDevice = true) || mc.screen != null || !request.isStillValid() || !isValidWeapon()) {
            AutomationCoordinator.release(AIM_OWNER)
            return
        }

        mc.player?.rightClick()
        AutomationCoordinator.release(AIM_OWNER)
        retryAfterMs = System.currentTimeMillis() + retryDelay

        if (predictions && !request.predicted) {
            selectPrediction(request.pos)?.let { predicted ->
                schedule(1, generation) {
                    requestShot(ShotRequest(predicted, predicted = true))
                }
            }
        }
    }

    private fun selectPrediction(current: BlockPos): BlockPos? {
        val available = DEVICE_BLOCKS.filter {
            it != current && it !in doneCoords && blockAt(it) == Blocks.BLUE_TERRACOTTA
        }
        if (available.isEmpty()) return null

        val underLimit = available.filter { (predictionAttempts[it] ?: 0) < 2 }
        val pool = underLimit.ifEmpty { available }
        val selected = pool.minWithOrNull(
            compareBy<BlockPos>({ predictionAttempts[it] ?: 0 }, { abs(it.y - current.y) }, { abs(it.x - current.x) })
        ) ?: return null
        predictionAttempts[selected] = (predictionAttempts[selected] ?: 0) + 1
        return selected
    }

    private fun ShotRequest.isStillValid(): Boolean {
        if (pos in doneCoords) return false
        return when (blockAt(pos)) {
            Blocks.EMERALD_BLOCK -> true
            Blocks.BLUE_TERRACOTTA -> predicted
            else -> false
        }
    }

    private fun targetVector(pos: BlockPos): Vec3 {
        val index = DEVICE_BLOCKS.indexOf(pos).coerceAtLeast(0)
        val column = index % 3
        val row = index / 3
        val lowerXDone = column < 2 && DEVICE_BLOCKS[index + 1] in doneCoords
        val higherXDone = column > 0 && DEVICE_BLOCKS[index - 1] in doneCoords

        val targetX = when (column) {
            0 -> 67.5
            2 -> 65.5
            else -> when {
                higherXDone && !lowerXDone -> 65.5
                lowerXDone && !higherXDone -> 67.5
                else -> if ((predictionAttempts[pos] ?: 0) % 2 == 0) 65.5 else 67.5
            }
        }
        return Vec3(targetX, 131.0 - 2.0 * row, 50.0)
    }

    private fun startRodAction() {
        if (rodStage != RodStage.IDLE) return
        if (!AutomationCoordinator.acquire(ROD_OWNER, 2_000L, Channel.HOTBAR, Channel.INTERACTION)) return

        val player = mc.player ?: return AutomationCoordinator.release(ROD_OWNER)
        val slot = (0..8).firstOrNull { isSuitableRod(player.inventory.getItem(it)) }
        if (slot == null) {
            rodDone = true
            AutomationCoordinator.release(ROD_OWNER)
            feedback("Auto rod skipped: no suitable fishing rod on the hotbar.")
            return
        }

        rodRestoreSlot = player.inventory.selectedSlot
        when (SwapManager.swapToSlot(slot)) {
            SwapResult.SUCCESS, SwapResult.ALREADY_SELECTED -> {
                rodDone = true
                rodStage = RodStage.CAST
                rodDelayTicks = 1
                rodRestoreRetries = 0
            }
            SwapResult.NOT_FOUND, SwapResult.FAILED -> {
                rodDone = true
                AutomationCoordinator.release(ROD_OWNER)
            }
            SwapResult.TOO_FAST -> AutomationCoordinator.release(ROD_OWNER)
        }
    }

    private fun processRodAction() {
        if (rodStage == RodStage.IDLE) return
        if (!isI4Environment(requireDevice = true) || Dungeon.inTerminal || mc.screen != null) {
            return stopRodAction(restore = mc.player != null && !Dungeon.isDead)
        }
        if (rodDelayTicks-- > 0) return

        when (rodStage) {
            RodStage.CAST -> {
                val player = mc.player ?: return stopRodAction(restore = false)
                if (!AutomationCoordinator.extend(
                        ROD_OWNER,
                        750L,
                        Channel.HOTBAR,
                        Channel.INTERACTION
                    )) return stopRodAction(restore = false)
                if (!isSuitableRod(player.mainHandItem)) return stopRodAction(restore = true)

                player.useItem()
                rodStage = RodStage.RESTORE
                rodDelayTicks = 2
            }
            RodStage.RESTORE -> {
                if (!AutomationCoordinator.extend(
                        ROD_OWNER,
                        750L,
                        Channel.HOTBAR,
                        Channel.INTERACTION
                    )) return stopRodAction(restore = false)
                val result = SwapManager.swapToSlot(rodRestoreSlot)
                if (result.success || result != SwapResult.TOO_FAST || ++rodRestoreRetries >= 10) {
                    stopRodAction(restore = false)
                } else {
                    rodDelayTicks = 1
                    AutomationCoordinator.extend(ROD_OWNER, 750L, Channel.HOTBAR, Channel.INTERACTION)
                }
            }
            RodStage.IDLE -> Unit
        }
    }

    private fun stopRodAction(restore: Boolean) {
        if (restore && rodRestoreSlot in 0..8 && mc.player != null) SwapManager.swapToSlot(rodRestoreSlot)
        rodStage = RodStage.IDLE
        rodDelayTicks = 0
        rodRestoreSlot = -1
        rodRestoreRetries = 0
        AutomationCoordinator.release(ROD_OWNER)
    }

    private fun startMaskAction() {
        if (AutoMask.triggerEquip(maskName.selected)) maskDone = true
    }

    private fun startLeapAction() {
        val teammates = Dungeon.dungeonTeammatesNoSelf.filterNot { it.isDead }
        val melody = melodyTargetName
            ?.takeIf { leapToMelody }
            ?.let { name -> teammates.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val target = melody
            ?: teammates.firstOrNull { it.clazz == leapPriority.selected }
            ?: LEAP_FALLBACK_ORDER.firstNotNullOfOrNull { clazz -> teammates.firstOrNull { it.clazz == clazz } }

        if (target == null) {
            leapDone = true
            feedback("Auto leap skipped: no living teammate found.")
            return
        }
        if (LeapManager.leap(target.name)) {
            leapDone = true
            runTick = -1
        }
    }

    fun handlesAutomaticLeap(): Boolean = enabled && autoLeap

    private fun schedule(delayTicks: Int, generation: Int, action: () -> Unit) {
        lateinit var task: Scheduler.Task
        task = Scheduler.scheduleTaskHandle(delayTicks) {
            scheduledTasks.remove(task)
            if (enabled && generation == sessionGeneration) action()
        }
        scheduledTasks.add(task)
    }

    private fun cancelAim() {
        if (activeAim != null) cancelRotationTask()
        activeAim = null
        aimToken++
        AutomationCoordinator.release(AIM_OWNER)
    }

    private fun resetSessionState(restoreRod: Boolean) {
        cancelAim()
        stopRodAction(restore = restoreRod)
        doneCoords.clear()
        predictionAttempts.clear()
        activeEmerald = null
        pendingShot = null
        retryAfterMs = 0L
        lastShotAttemptAt = 0L
        scanTicker = 0
        rodDone = false
        maskDone = false
        leapDone = false
        melodyTargetName = null
    }

    private fun reset(worldChanged: Boolean = false) {
        val cancelMaskSwap = maskDone
        sessionGeneration++
        runTick = -1
        deviceCompleted = false
        resetSessionState(restoreRod = !worldChanged)
        scheduledTasks.toList().forEach(Scheduler.Task::cancel)
        scheduledTasks.clear()
        if (cancelMaskSwap) AutoMask.cancelPendingEquip()
        AutomationCoordinator.release(AIM_OWNER)
        AutomationCoordinator.release(ROD_OWNER)
    }

    private fun isI4Environment(requireDevice: Boolean): Boolean {
        val player = mc.player ?: return false
        if (!enabled || deviceCompleted || Dungeon.isDead || Dungeon.floor?.floorNumber != 7 || !Dungeon.inBoss) return false
        val section = Dungeon.p3Section.takeIf { it != P3Section.Unknown } ?: Dungeon.getP3Section(player)
        if (section != P3Section.S4) return false
        return !requireDevice || isOnDevice()
    }

    private fun isOnDevice(): Boolean {
        val player = mc.player ?: return false
        return abs(player.y - 127.0) < 0.75 && player.x in 61.5..65.5 && player.z in 33.5..37.5
    }

    private fun isValidWeapon(): Boolean {
        val stack = mc.player?.mainHandItem ?: return false
        return stack.item == Items.BOW || isSuitableRod(stack)
    }

    private fun isSuitableRod(stack: ItemStack): Boolean =
        stack.item == Items.FISHING_ROD && stack.skyblockId !in ROD_SWAP_BLACKLIST

    private fun blockAt(pos: BlockPos) = mc.level?.getBlockState(pos)?.block

    private fun completedBySelf(message: String): Boolean {
        val name = DEVICE_DONE_REGEX.matchEntire(message)?.groupValues?.getOrNull(1) ?: return false
        return name.equals(mc.player?.gameProfile?.name, ignoreCase = true)
    }

    private fun parseMelodyTarget(message: String): String? {
        if (!leapToMelody || !message.startsWith("Party > ")) return null
        if (MELODY_PROGRESS.none(message::contains)) return null
        val name = PARTY_SENDER.find(message)?.groupValues?.getOrNull(1) ?: return null
        return name.takeUnless { it.equals(mc.player?.gameProfile?.name, ignoreCase = true) }
    }

    private fun feedback(message: String) {
        if (feedback) modMessage("&eAuto I4: &f$message")
    }

    private val DEVICE_BLOCKS = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50)
    )
    private val LEAP_FALLBACK_ORDER =
        listOf(DungeonClass.Tank, DungeonClass.Mage, DungeonClass.Healer, DungeonClass.Archer)
    private val ROD_SWAP_BLACKLIST = setOf("SOUL_WHIP", "FLAMING_FLAY", "GRAPPLING_HOOK")
    private val DEVICE_DONE_REGEX = Regex("^(\\w{1,16}) completed a device! \\(\\d/\\d\\)$")
    private val PARTY_SENDER = Regex("^Party > (?:\\[[^]]+]\\s*)?(\\w{1,16}):")
    private val MELODY_PROGRESS = listOf("0/4", "1/4", "2/4", "3/4", "4/4", "25%", "50%", "75%", "100%")
}
