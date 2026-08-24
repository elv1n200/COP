package cop.module.impl.misc.dojo

import cop.api.events.BlockEvent
import cop.api.events.ChatEvent
import cop.api.events.KeyEvent
import cop.api.events.MouseEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.module.Module
import cop.module.settings.Setting.Companion.json
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.EntityUtils.getEntities
import cop.utils.Scheduler
import cop.utils.getArrowDirection
import cop.utils.getDirection
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.HOTBAR
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import cop.utils.skyblock.player.AutomationCoordinator.Channel.ROTATION
import cop.utils.skyblock.player.MovementUtils.cancelMovementTask
import cop.utils.skyblock.player.MovementUtils.moveTo
import cop.utils.skyblock.player.PlayerUtils.leftClick
import cop.utils.skyblock.player.RotationUtils.cancelRotationTask
import cop.utils.skyblock.player.RotationUtils.rotateSmoothly
import cop.utils.skyblock.player.SwapManager
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/** Automation for the mechanically useful parts of the Crimson Isle Dojo tests. */
object DojoAutomation : Module(
    "Dojo Automation",
    area = Island.CrimsonIsle,
    subarea = "dojo",
    desc = "Automates target protection, weapon swaps, Mastery shots, Control aim and movement tests.",
) {
    private val forceHeader by text("Force")
    private val blockNegative by switch(
        "Block negative targets",
        true,
        desc = "Cancels attacks on leather-helmet zombies worth negative points.",
    ).childOf(::forceHeader)

    private val disciplineHeader by text("Discipline")
    private val disciplineSwap by switch("Auto sword swap", true).childOf(::disciplineHeader)
    private val blockWrongSword by switch("Block wrong sword", true).childOf(::disciplineHeader)
    private val retryAttack by switch(
        "Retry attack after swap",
        true,
        desc = "Replays the click one tick after selecting the correct sword.",
    ).childOf(::disciplineHeader)

    private val masteryHeader by text("Mastery")
    private val autoMastery by switch("Auto aim and shoot", true).childOf(::masteryHeader)
    private val releaseOffset by slider(
        "Release offset",
        500L,
        0L,
        1_250L,
        25L,
        unit = "ms",
        desc = "Releases shortly before the wool target expires.",
    ).childOf(::masteryHeader)
    private val masteryRotation by slider("Aim duration", 125, 0, 350, 25, unit = "ms")
        .json("Mastery aim duration").childOf(::masteryHeader)

    private val movementHeader by text("Movement tests")
    private val autoStamina by switch("Auto jump in Stamina", true).childOf(::movementHeader)
    private val autoSwiftness by switch("Auto move in Swiftness", true).childOf(::movementHeader)

    private val controlHeader by text("Control")
    private val controlAim by switch("Auto aim", true).childOf(::controlHeader)
    private val predictionTicks by slider("Prediction", 5.0, 1.0, 10.0, 0.5, unit = "t").childOf(::controlHeader)
    private val controlRotation by slider("Aim duration", 125, 0, 350, 25, unit = "ms").childOf(::controlHeader)

    private var dojoType = DojoType.NONE
    private val masteryTargets = linkedMapOf<BlockPos, Long>()
    private val swiftnessTargets = ArrayDeque<MovementTarget>()
    private var bowState = BowState.IDLE
    private var bowTarget: BlockPos? = null
    private var lastControlAimAt = 0L
    private var retryAttackTask: Scheduler.Task? = null
    private var ownsUseKey = false
    private var ownsMovementTask = false
    private var ownsRotationTask = false

    init {
        on<ChatEvent.PacketClient> {
            val upper = message.uppercase()
            if (upper.contains("YOUR RANK:")) {
                resetTest()
                return@on
            }
            if (!upper.contains("OBJECTIVES") && !upper.contains("TEST OF")) return@on
            DojoType.entries.firstOrNull { it != DojoType.NONE && upper.contains(it.name) }?.let {
                resetTest()
                dojoType = it
            }
        }

        on<MouseEvent.Click> {
            if (button != 0 || !state || mc.screen != null) return@on
            val zombie = ((mc.hitResult as? EntityHitResult)?.entity as? Zombie) ?: return@on

            when (dojoType) {
                DojoType.FORCE -> {
                    if (blockNegative && zombie.helmetItem == Items.LEATHER_HELMET) cancel()
                }

                DojoType.DISCIPLINE -> if (handleDiscipline(zombie)) cancel()
                else -> Unit
            }
        }

        on<BlockEvent.Update> {
            when (dojoType) {
                DojoType.MASTERY -> {
                    if (updated.block == Blocks.LIME_WOOL && player.blockPosition().distSqr(pos) <= 400.0) {
                        masteryTargets[pos.immutable()] = System.currentTimeMillis() + 6_500L
                    } else if (old.block == Blocks.LIME_WOOL) masteryTargets.remove(pos)
                }

                DojoType.SWIFTNESS -> {
                    if (updated.block == Blocks.LIME_WOOL && pos != DOJO_CENTRE_BLOCK) {
                        val target = pos.center.add(0.0, 0.5, 0.0)
                        if (swiftnessTargets.none { it.position == target }) {
                            swiftnessTargets += MovementTarget(target, System.currentTimeMillis() + 6_000L)
                        }
                    }
                }

                else -> Unit
            }
        }

        on<TickEvent.End> {
            when (dojoType) {
                DojoType.MASTERY -> tickMastery()
                DojoType.CONTROL -> tickControl()
                DojoType.SWIFTNESS -> tickSwiftness()
                else -> Unit
            }
        }

        on<KeyEvent.Input> {
            if (dojoType == DojoType.STAMINA && autoStamina && player.onGround()) input.jump = true
        }

        on<WorldEvent.Change> {
            resetTest()
        }
    }

    override fun onDisable() {
        resetTest()
        super.onDisable()
    }

    private fun handleDiscipline(zombie: Zombie): Boolean {
        if (!disciplineSwap && !blockWrongSword) return false
        val needed = SWORD_FOR_HELMET[zombie.helmetItem] ?: return false
        if (player.mainHandItem.item == needed) return false

        // The current click must never reach Hypixel with the wrong sword.
        // Auto-swap can optionally replay it after the server slot update.
        if (!disciplineSwap) return blockWrongSword
        if (!AutomationCoordinator.acquire(OWNER, 300L, HOTBAR, INTERACTION)) return true

        val slot = (0..8).firstOrNull { player.inventory.getItem(it).item == needed }
        if (slot == null || !SwapManager.swapToSlot(slot).success) {
            AutomationCoordinator.release(OWNER, HOTBAR, INTERACTION)
            return true
        }

        retryAttackTask?.cancel()
        if (retryAttack) {
            val targetId = zombie.id
            retryAttackTask = Scheduler.scheduleTaskHandle(1) { task ->
                if (retryAttackTask !== task) return@scheduleTaskHandle
                retryAttackTask = null
                try {
                    val target = (mc.hitResult as? EntityHitResult)?.entity as? Zombie
                    if (!enabled || !inEnvironment() || dojoType != DojoType.DISCIPLINE || mc.screen != null) return@scheduleTaskHandle
                    if (target?.id != targetId || mc.player?.mainHandItem?.item != needed) return@scheduleTaskHandle
                    mc.player?.leftClick()
                } finally {
                    AutomationCoordinator.release(OWNER, HOTBAR, INTERACTION)
                }
            }
        } else {
            AutomationCoordinator.release(OWNER, HOTBAR, INTERACTION)
        }
        return true
    }

    private fun tickMastery() {
        val now = System.currentTimeMillis()
        masteryTargets.entries.removeIf { it.value <= now }
        if (!autoMastery || mc.screen != null) {
            if (bowState != BowState.IDLE || ownsUseKey) finishShot()
            return
        }

        when (bowState) {
            BowState.IDLE -> {
                val target = masteryTargets.minByOrNull { it.value }?.key ?: return
                if (mc.options.keyUse.isDown) return
                if (!AutomationCoordinator.acquire(OWNER, 8_000L, HOTBAR, ROTATION, INTERACTION)) return

                val bowSlot = (0..8).firstOrNull { player.inventory.getItem(it).item == Items.BOW }
                if (bowSlot == null || !SwapManager.swapToSlot(bowSlot).success) {
                    AutomationCoordinator.release(OWNER, HOTBAR, ROTATION, INTERACTION)
                    return
                }

                bowTarget = target
                player.rotateSmoothly(getArrowDirection(target.center), masteryRotation.toFloat())
                ownsRotationTask = true
                mc.options.keyUse.isDown = true
                ownsUseKey = true
                bowState = BowState.DRAWING
            }

            BowState.DRAWING -> {
                val target = bowTarget
                val expiry = target?.let(masteryTargets::get)
                if (target == null || expiry == null) {
                    finishShot()
                    return
                }
                if (expiry - now <= releaseOffset) {
                    releaseUseKey()
                    masteryTargets.remove(target)
                    bowTarget = null
                    bowState = BowState.RELEASED
                    cancelOwnRotation()
                    AutomationCoordinator.release(OWNER, HOTBAR, ROTATION, INTERACTION)
                }
            }

            BowState.RELEASED -> bowState = BowState.IDLE
        }
    }

    private fun tickControl() {
        if (!controlAim || mc.screen != null) {
            cancelOwnRotation()
            AutomationCoordinator.release(OWNER, ROTATION)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastControlAimAt < 75L) return

        val skeleton = getEntities<WitherSkeleton>(DOJO_CENTRE, 25.0) {
            !it.isDeadOrDying && it.helmetItem != Items.REDSTONE_BLOCK
        }.minByOrNull { it.distanceToSqr(player) } ?: return
        if (skeleton.position() == skeleton.oldPosition()) return

        val movement = skeleton.position().subtract(skeleton.oldPosition())
        val target = skeleton.position().add(
            movement.x * predictionTicks,
            1.5 + movement.y,
            movement.z * predictionTicks,
        )
        if (!AutomationCoordinator.acquire(OWNER, controlRotation.toLong() + 150L, ROTATION)) return
        player.rotateSmoothly(getDirection(player.eyePosition, target), controlRotation.toFloat())
        ownsRotationTask = true
        lastControlAimAt = now
    }

    private fun tickSwiftness() {
        if (!autoSwiftness || mc.screen != null) {
            cancelOwnMovement()
            AutomationCoordinator.release(OWNER, MOVEMENT)
            return
        }
        val now = System.currentTimeMillis()
        while (swiftnessTargets.isNotEmpty() && (
                swiftnessTargets.first().expiresAt <= now ||
                    player.position().distanceToSqr(swiftnessTargets.first().position) < 0.45
                )
        ) {
            swiftnessTargets.removeFirst()
        }
        val target = swiftnessTargets.firstOrNull()?.position
        if (target == null) {
            cancelOwnMovement()
            AutomationCoordinator.release(OWNER, MOVEMENT)
            return
        }
        if (!AutomationCoordinator.acquire(OWNER, 750L, MOVEMENT)) return
        player.moveTo(target)
        ownsMovementTask = true
    }

    private fun finishShot() {
        releaseUseKey()
        bowTarget = null
        bowState = BowState.IDLE
        cancelOwnRotation()
        AutomationCoordinator.release(OWNER, HOTBAR, ROTATION, INTERACTION)
    }

    private fun resetTest() {
        dojoType = DojoType.NONE
        retryAttackTask?.cancel()
        retryAttackTask = null
        finishShot()
        masteryTargets.clear()
        swiftnessTargets.clear()
        lastControlAimAt = 0L
        cancelOwnMovement()
        cancelOwnRotation()
        AutomationCoordinator.release(OWNER)
    }

    private fun releaseUseKey() {
        if (!ownsUseKey) return
        ownsUseKey = false
        mc.options.keyUse.isDown = false
        KeyMapping.setAll()
    }

    private fun cancelOwnMovement() {
        if (ownsMovementTask && AutomationCoordinator.owner(MOVEMENT) == OWNER) cancelMovementTask()
        ownsMovementTask = false
    }

    private fun cancelOwnRotation() {
        if (ownsRotationTask && AutomationCoordinator.owner(ROTATION) == OWNER) cancelRotationTask()
        ownsRotationTask = false
    }

    private val Zombie.helmetItem: Item get() = getItemBySlot(EquipmentSlot.HEAD).item
    private val WitherSkeleton.helmetItem: Item get() = getItemBySlot(EquipmentSlot.HEAD).item

    private enum class DojoType { FORCE, STAMINA, MASTERY, DISCIPLINE, SWIFTNESS, CONTROL, TENACITY, NONE }
    private enum class BowState { IDLE, DRAWING, RELEASED }

    private data class MovementTarget(val position: Vec3, val expiresAt: Long)

    private val SWORD_FOR_HELMET = mapOf(
        Items.LEATHER_HELMET to Items.WOODEN_SWORD,
        Items.IRON_HELMET to Items.IRON_SWORD,
        Items.GOLDEN_HELMET to Items.GOLDEN_SWORD,
        Items.DIAMOND_HELMET to Items.DIAMOND_SWORD,
    )
    private val DOJO_CENTRE = Vec3(-207.0, 99.0, -598.0)
    private val DOJO_CENTRE_BLOCK = BlockPos(-207, 99, -598)
    private const val OWNER = "Dojo Automation"
}
