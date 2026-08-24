package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import cop.CopMod.scope
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult
import cop.utils.skyblock.player.interact.AuraManager

/** Triggerbot for Goldor's first three barrier gates. */
object BarrierBoom : Module(
    "Barrier Boom",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Automatically detonates Goldor gates when you look at their barrier block."
) {
    private val reactionDelay by slider("Reaction delay", 0, 0, 10, 1, unit = "t", desc = "Delay before detonating the looked-at gate.")
    private val swingHand by switch("Swing hand", true, desc = "Shows an attack swing when detonating the gate.")
    private val swapBack by switch("Swap back", true, desc = "Returns to the original hotbar slot after throwing TNT.")
    private val swapBackDelay by slider("Swap-back delay", 2, 1, 10, 1, unit = "t")
        .childOf(::swapBack)

    private var armedTarget: BlockPos? = null
    private var boomJob: Job? = null
    private var retryNotBefore = 0L
    private var worldEpoch = 0
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    init {
        on<WorldEvent.Change> {
            worldEpoch++
            reset()
        }

        on<TickEvent.Start> {
            if (!Dungeon.inP3 || Dungeon.isDead || Dungeon.inTerminal || mc.screen != null) {
                armedTarget = null
                return@on
            }

            val section = Dungeon.getP3Section()
            if (section.number !in 1..3 || section.gate) {
                armedTarget = null
                return@on
            }

            val hit = mc.hitResult as? BlockHitResult
            val gateArea = GATE_AREAS[section.number] ?: return@on
            val target = hit?.takeIf { it.type == HitResult.Type.BLOCK }
                ?.blockPos
                ?.takeIf {
                    mc.level?.getBlockState(it)?.block == Blocks.BARRIER &&
                        gateArea.contains(it.center) &&
                        player.eyePosition.distanceToSqr(it.center) <= MAX_REACH_SQUARED
                }

            if (target == null) {
                armedTarget = null
                return@on
            }
            if (target == armedTarget || boomJob?.isActive == true) return@on
            if (System.currentTimeMillis() < retryNotBefore) return@on

            if (startBoom(target, section.number, worldEpoch)) armedTarget = target
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun startBoom(target: BlockPos, sectionNumber: Int, epoch: Int): Boolean {
        val inventory = mc.player?.inventory ?: return false
        val originalSlot = inventory.selectedSlot.takeIf { it in 0..8 } ?: return false
        val tntSlot = (0..8).firstOrNull { inventory.getItem(it).skyblockId in SUPERBOOM_IDS } ?: return false
        if (!AutomationCoordinator.acquire(OWNER, 3_000L, Channel.HOTBAR, Channel.INTERACTION)) return false

        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                val swap = SwapManager.swapToSlot(tntSlot)
                if (swap != SwapResult.SUCCESS && swap != SwapResult.ALREADY_SELECTED) return@launch
                delay(reactionDelay * 50L)

                if (!isValidDelayedTarget(target, sectionNumber, epoch)) return@launch

                AuraManager.breakBlock(target, immediate = true, swing = swingHand)
                delay(swapBackDelay * 50L)
            } finally {
                if (swapBack && epoch == worldEpoch && mc.player != null) {
                    SwapManager.swapToSlot(originalSlot)
                }
                AutomationCoordinator.release(OWNER)
            }
        }

        boomJob = job
        job.invokeOnCompletion {
            mc.execute {
                if (boomJob !== job) return@execute
                boomJob = null
                if (armedTarget == target) armedTarget = null
                retryNotBefore = System.currentTimeMillis() + RETRY_DELAY_MS
            }
        }
        job.start()
        return true
    }

    private fun isValidDelayedTarget(target: BlockPos, sectionNumber: Int, epoch: Int): Boolean {
        if (!enabled || epoch != worldEpoch || Dungeon.isDead || !Dungeon.inP3 ||
            Dungeon.inTerminal || mc.screen != null
        ) return false

        val currentPlayer = mc.player ?: return false
        val section = Dungeon.getP3Section()
        if (section.number != sectionNumber || section.gate) return false
        val gateArea = GATE_AREAS[sectionNumber] ?: return false
        val hit = mc.hitResult as? BlockHitResult ?: return false

        return hit.type == HitResult.Type.BLOCK && hit.blockPos == target &&
            mc.level?.getBlockState(target)?.block == Blocks.BARRIER &&
            gateArea.contains(target.center) &&
            currentPlayer.eyePosition.distanceToSqr(target.center) <= MAX_REACH_SQUARED
    }

    private fun reset() {
        armedTarget = null
        retryNotBefore = 0L
        boomJob?.cancel()
        boomJob = null
        AutomationCoordinator.release(OWNER)
    }

    private const val OWNER = "BarrierBoom"
    private const val MAX_REACH_SQUARED = 36.0
    private const val RETRY_DELAY_MS = 750L
    private val SUPERBOOM_IDS = setOf("SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT")
    private val GATE_AREAS = mapOf(
        1 to AABB(95.0, 114.0, 122.0, 106.0, 135.0, 125.0),
        2 to AABB(18.0, 114.0, 127.0, 20.0, 135.0, 139.0),
        3 to AABB(1.0, 114.0, 49.0, 15.0, 135.0, 52.0),
    )
}
