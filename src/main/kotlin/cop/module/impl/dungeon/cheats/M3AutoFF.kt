package cop.module.impl.dungeon.cheats

import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Floor
import cop.module.Module
import cop.module.impl.dungeon.huds.CooldownDisplay
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.getDirection
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.AutomationCoordinator.Channel.HOTBAR
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import cop.utils.skyblock.player.AutomationCoordinator.Channel.ROTATION
import cop.utils.skyblock.player.MovementUtils.cancelMovementTask
import cop.utils.skyblock.player.MovementUtils.moveTo
import cop.utils.skyblock.player.MovementUtils.stop
import cop.utils.skyblock.player.PlayerUtils
import cop.utils.skyblock.player.RotationUtils.cancelRotationTask
import cop.utils.skyblock.player.RotationUtils.rotate
import cop.utils.skyblock.player.RotationUtils.rotateSmoothly
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

object M3AutoFF : Module(
    "M3 Auto FF",
    desc = "Times a Fire Freeze Staff cast for the Professor's Master Floor 3 transitions."
) {
    private val moveToSafePosition by switch(
        "Move to safe position",
        false,
        desc = "Moves toward the nearest fixed arena safe point during the five-second wait."
    )
    private val rotateToSafePosition by switch(
        "Rotate to safe position",
        false,
        desc = "Faces the selected safe point while waiting to cast."
    )
    private val rotationTime by slider(
        "Rotation time",
        250,
        0,
        1_000,
        25,
        unit = "ms",
        desc = "Smooth rotation duration; 0 rotates immediately."
    ).childOf(::rotateToSafePosition)

    private enum class Stage { WAITING, CLICK, RETURN }

    private data class Action(
        val originalSlot: Int,
        var staffSlot: Int,
        val safePosition: Vec3,
        val useMovement: Boolean,
        val useRotation: Boolean,
        var dueAt: Long,
        var stage: Stage = Stage.WAITING,
        var swapped: Boolean = false
    )

    private var action: Action? = null
    private var deferredRestore: Scheduler.Task? = null
    private var ownsMovementTask = false
    private var ownsRotationTask = false
    private var lastTriggerAt = 0L
    private var worldEpoch = 0

    init {
        on<ChatEvent.PacketClient> {
            val plain = message.noControlCodes.trim()
            if (plain != TRIGGER_LINE || action != null || deferredRestore != null) return@on
            if (!validM3Boss() || mc.screen != null) return@on

            val now = System.currentTimeMillis()
            if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MILLIS) return@on

            val localPlayer = mc.player ?: return@on
            val staffSlot = findStaffSlot() ?: return@on
            val move = moveToSafePosition
            val rotate = rotateToSafePosition
            val channels = channels(move, rotate)
            if (!AutomationCoordinator.acquire(
                    OWNER,
                    CAST_WAIT_MILLIS + 2_000L,
                    *channels
                )
            ) return@on

            val pending = Action(
                originalSlot = localPlayer.inventory.selectedSlot,
                staffSlot = staffSlot,
                safePosition = nearestSafePosition(localPlayer.position()),
                useMovement = move,
                useRotation = rotate,
                dueAt = now + CAST_WAIT_MILLIS
            )
            action = pending
            lastTriggerAt = now
            startPositioning(pending)
        }

        on<TickEvent.End> { processAction() }

        on<WorldEvent.Change> {
            worldEpoch++
            lastTriggerAt = 0L
            cancelAction(restore = false)
        }
    }

    override fun onDisable() {
        lastTriggerAt = 0L
        cancelAction(restore = true)
        super.onDisable()
    }

    private fun processAction() {
        val pending = action ?: return
        if (!validM3Boss() || mc.screen != null) {
            cancelAction(restore = pending.swapped)
            return
        }
        if (!AutomationCoordinator.extend(OWNER, 1_000L, *channels(pending.useMovement, pending.useRotation))) {
            cancelAction(restore = false)
            return
        }

        val now = System.currentTimeMillis()
        if (now < pending.dueAt) return

        when (pending.stage) {
            Stage.WAITING -> prepareCast(pending, now)
            Stage.CLICK -> cast(pending, now)
            Stage.RETURN -> restoreAfterCast(pending, now)
        }
    }

    private fun prepareCast(pending: Action, now: Long) {
        stopPositioning()

        val localPlayer = mc.player ?: return cancelAction(restore = false)
        if (!isFireFreezeStaff(localPlayer.inventory.getItem(pending.staffSlot))) {
            pending.staffSlot = findStaffSlot() ?: return cancelAction(restore = false)
        }
        val stack = localPlayer.inventory.getItem(pending.staffSlot)
        if (CooldownDisplay.isOnCooldown(stack)) return cancelAction(restore = false)

        when (SwapManager.swapToSlot(pending.staffSlot)) {
            SwapResult.SUCCESS, SwapResult.ALREADY_SELECTED -> {
                pending.swapped = pending.staffSlot != pending.originalSlot
                pending.stage = Stage.CLICK
                pending.dueAt = now + ONE_TICK_MILLIS
            }
            SwapResult.TOO_FAST -> pending.dueAt = now + ONE_TICK_MILLIS
            SwapResult.NOT_FOUND, SwapResult.FAILED -> cancelAction(restore = false)
        }
    }

    private fun cast(pending: Action, now: Long) {
        val localPlayer = mc.player ?: return cancelAction(restore = pending.swapped)
        val stack = localPlayer.mainHandItem
        if (localPlayer.inventory.selectedSlot != pending.staffSlot || !isFireFreezeStaff(stack)) {
            cancelAction(restore = pending.swapped)
            return
        }
        if (CooldownDisplay.isOnCooldown(stack)) {
            cancelAction(restore = pending.swapped)
            return
        }

        PlayerUtils.interact()
        CooldownDisplay.startRightClickCooldown(stack)
        pending.stage = Stage.RETURN
        pending.dueAt = now + ONE_TICK_MILLIS
    }

    private fun restoreAfterCast(pending: Action, now: Long) {
        when (SwapManager.swapToSlot(pending.originalSlot)) {
            SwapResult.SUCCESS, SwapResult.ALREADY_SELECTED -> completeAction()
            SwapResult.TOO_FAST -> pending.dueAt = now + ONE_TICK_MILLIS
            SwapResult.NOT_FOUND, SwapResult.FAILED -> completeAction()
        }
    }

    private fun startPositioning(pending: Action) {
        val localPlayer = mc.player ?: return

        if (pending.useMovement) {
            localPlayer.moveTo(pending.safePosition)
            ownsMovementTask = true
        }

        if (pending.useRotation) {
            val direction = getDirection(localPlayer.position(), pending.safePosition)
            if (rotationTime <= 0) {
                localPlayer.rotate(direction.yaw, localPlayer.xRot)
            } else {
                localPlayer.rotateSmoothly(direction.yaw, localPlayer.xRot, rotationTime.toFloat())
            }
            ownsRotationTask = true
        }
    }

    private fun stopPositioning() {
        val movementOwner = AutomationCoordinator.owner(MOVEMENT)
        if (ownsMovementTask && (movementOwner == null || movementOwner == OWNER)) {
            cancelMovementTask()
            mc.player?.stop()
        }
        val rotationOwner = AutomationCoordinator.owner(ROTATION)
        if (ownsRotationTask && (rotationOwner == null || rotationOwner == OWNER)) {
            cancelRotationTask()
        }
        ownsMovementTask = false
        ownsRotationTask = false
    }

    private fun validM3Boss(): Boolean =
        enabled &&
            mc.level != null &&
            mc.player != null &&
            Dungeon.inDungeons &&
            Dungeon.floor == Floor.M3 &&
            Dungeon.inBoss &&
            !Dungeon.isDead

    private fun findStaffSlot(): Int? {
        val inventory = mc.player?.inventory ?: return null
        return (0..8).firstOrNull { isFireFreezeStaff(inventory.getItem(it)) }
    }

    private fun isFireFreezeStaff(stack: ItemStack): Boolean =
        !stack.isEmpty && (
            stack.skyblockId.equals(FIRE_FREEZE_ID, ignoreCase = true) ||
                stack.hoverName.string.noControlCodes.contains(FIRE_FREEZE_NAME, ignoreCase = true)
            )

    private fun nearestSafePosition(playerPosition: Vec3): Vec3 {
        val anchor = SAFE_POSITION_ANCHORS.minByOrNull { anchor ->
            val dx = playerPosition.x - anchor.x
            val dz = playerPosition.z - anchor.z
            dx * dx + dz * dz
        } ?: SAFE_POSITION_ANCHORS.first()
        return Vec3(anchor.x, playerPosition.y, anchor.z)
    }

    private fun channels(move: Boolean, rotate: Boolean): Array<Channel> = buildList {
        add(HOTBAR)
        add(INTERACTION)
        if (move) add(MOVEMENT)
        if (rotate) add(ROTATION)
    }.toTypedArray()

    private fun completeAction() {
        stopPositioning()
        action = null
        AutomationCoordinator.release(OWNER)
    }

    private fun cancelAction(restore: Boolean) {
        deferredRestore?.cancel()
        deferredRestore = null
        stopPositioning()

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

    private const val TRIGGER_LINE =
        "[BOSS] The Professor: Even if you took my barrier down, I can still fight."
    private val SAFE_POSITION_ANCHORS = listOf(
        Vec3(-9.5, 0.0, -9.5),
        Vec3(-9.5, 0.0, 9.5),
        Vec3(9.5, 0.0, -9.5),
        Vec3(9.5, 0.0, 9.5)
    )

    private const val OWNER = "dungeon-m3-auto-fire-freeze"
    private const val FIRE_FREEZE_ID = "FIRE_FREEZE_STAFF"
    private const val FIRE_FREEZE_NAME = "Fire Freeze Staff"
    private const val CAST_WAIT_MILLIS = 5_000L
    private const val TRIGGER_DEBOUNCE_MILLIS = 2_000L
    private const val ONE_TICK_MILLIS = 50L
}
