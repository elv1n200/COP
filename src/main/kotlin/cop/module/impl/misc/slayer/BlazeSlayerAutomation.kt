package cop.module.impl.misc.slayer

import cop.api.events.BlockEvent
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.module.Module
import cop.utils.EntityUtils.getEntities
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.WorldUtils.airLike
import cop.utils.WorldUtils.registryName
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.HOTBAR
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import cop.utils.skyblock.player.MovementUtils.cancelMovementTask
import cop.utils.skyblock.player.MovementUtils.moveTo
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.SwapManager
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/** Combat automation for Inferno Demonlord attunements and floor hazards. */
object BlazeSlayerAutomation : Module(
    "Blaze Slayer Automation",
    area = Island.CrimsonIsle,
    desc = "Auto-attunes daggers and moves out of Inferno Demonlord floor hazards.",
) {
    private val autoAttune by switch(
        "Auto attune",
        true,
        desc = "Selects and toggles the correct dagger attunement when you swing at the boss or a demon.",
    )
    private val hideAttuneWarnings by switch(
        "Hide attunement warnings",
        true,
        desc = "Hides the repeated wrong-attunement chat warning while Auto Attune is active.",
    )
    private val swapCooldown by slider("Swap cooldown", 400L, 200L, 1_000L, 25L, unit = "ms")

    private val dodgeDdr by switch(
        "Dodge DDR",
        true,
        desc = "Moves off dangerous terracotta patterns during the final boss phase.",
    )
    private val dodgeFire by switch(
        "Dodge demon fire",
        true,
        desc = "Moves out of fire placed by the demon phase.",
    )
    private val dodgeRadius by slider("Safe-block radius", 3, 1, 5, 1, unit = " blocks")

    private val ddrBlocks = hashSetOf<BlockPos>()
    private val fireBlocks = hashSetOf<BlockPos>()
    private var lastSwapAt = 0L
    private var lastDodgeAt = 0L
    private var fightConfirmedUntil = 0L
    private var equipTask: Scheduler.Task? = null
    private var ownsMovementTask = false

    init {
        on<PacketEvent.Sent, ServerboundSwingPacket> {
            if (packet.hand != InteractionHand.MAIN_HAND || mc.screen != null) return@on
            val target = (mc.hitResult as? EntityHitResult)?.entity as? LivingEntity ?: return@on
            val attunement = target.findAttunement() ?: return@on
            val now = System.currentTimeMillis()
            fightConfirmedUntil = now + FIGHT_CONFIRMATION_MS
            if (!autoAttune || now - lastSwapAt < swapCooldown) return@on

            equipTask?.cancel()
            val targetId = target.id
            equipTask = Scheduler.scheduleTaskHandle { task ->
                if (equipTask !== task) return@scheduleTaskHandle
                equipTask = null
                val currentTarget = (mc.hitResult as? EntityHitResult)?.entity as? LivingEntity
                if (!enabled || !inEnvironment() || !autoAttune || mc.screen != null) return@scheduleTaskHandle
                if (currentTarget == null || currentTarget.id != targetId || currentTarget.isDeadOrDying) return@scheduleTaskHandle
                if (currentTarget.findAttunement() != attunement) return@scheduleTaskHandle
                equip(attunement)
            }
        }

        on<ChatEvent.Packet> {
            if (!autoAttune || !hideAttuneWarnings) return@on
            val clean = message.noControlCodes
            if (clean.startsWith("Strike using the") || clean.startsWith("Your hit was reduced by Hellion Shield!")) {
                cancel()
            }
        }

        on<BlockEvent.Update> {
            if (!isFightNearby() || player.blockPosition().distSqr(pos) > 625.0) return@on

            if (dodgeDdr) updateHazard(ddrBlocks, pos, old.isDdrTerracotta, updated.isDdrTerracotta)
            if (dodgeFire) updateHazard(fireBlocks, pos, old.block == Blocks.FIRE, updated.block == Blocks.FIRE)
        }

        on<TickEvent.End> {
            if (!isFightNearby()) {
                ddrBlocks.clear()
                fireBlocks.clear()
                stopDodge()
                return@on
            }
            if (mc.screen != null) {
                stopDodge()
                return@on
            }
            val now = System.currentTimeMillis()
            if (now - lastDodgeAt < 250L) return@on

            val danger = when {
                dodgeDdr && standingOn(ddrBlocks) -> ddrBlocks
                dodgeFire && (standingOn(fireBlocks) || player.isOnFire) -> fireBlocks
                else -> {
                    stopDodge()
                    return@on
                }
            }

            val safe = findSafeBlock(danger) ?: run {
                stopDodge()
                return@on
            }
            if (!AutomationCoordinator.acquire(OWNER, 1_250L, MOVEMENT)) return@on
            player.moveTo(Vec3(safe.x + 0.5, safe.y + 1.0, safe.z + 0.5))
            ownsMovementTask = true
            lastDodgeAt = now
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun equip(attunement: Attunement) {
        if (!enabled || !inEnvironment() || !autoAttune || mc.screen != null) return
        val now = System.currentTimeMillis()
        if (now - lastSwapAt < swapCooldown) return
        if (!AutomationCoordinator.acquire(OWNER, 250L, HOTBAR, INTERACTION)) return

        val result = SwapManager.swapById(*attunement.daggers)
        if (!result.success) {
            AutomationCoordinator.release(OWNER, HOTBAR, INTERACTION)
            return
        }

        val selected = player.mainHandItem.lore.orEmpty()
            .map { it.noControlCodes }
            .firstOrNull { it.startsWith("Attuned:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.uppercase()

        if (selected != attunement.name) player.rightClick()
        lastSwapAt = now
    }

    private fun LivingEntity.findAttunement(): Attunement? {
        val direct = level.getEntity(id + 2) as? ArmorStand
        direct?.attunement()?.let { return it }

        return getEntities<ArmorStand>(position(), 3.5) { it.customName != null }
            .asSequence()
            .mapNotNull { it.attunement() }
            .firstOrNull()
    }

    private fun ArmorStand.attunement(): Attunement? {
        val name = customName?.string?.noControlCodes ?: return null
        return Attunement.entries.firstOrNull { name.contains(it.name, ignoreCase = true) }
    }

    private fun isFightNearby(): Boolean {
        if (System.currentTimeMillis() > fightConfirmedUntil) return false
        if (getEntities<LivingEntity>(18.0) {
                !it.isDeadOrDying && (it is Blaze || it is WitherSkeleton || it is ZombifiedPiglin)
            }.any { it.findAttunement() != null }
        ) return true

        return getEntities<ArmorStand>(18.0) { stand ->
            stand.customName?.string?.noControlCodes?.contains("Inferno Demonlord", ignoreCase = true) == true
        }.isNotEmpty()
    }

    private fun updateHazard(set: MutableSet<BlockPos>, pos: BlockPos, wasHazard: Boolean, isHazard: Boolean) {
        if (!wasHazard && isHazard) set += pos.immutable()
        else if (wasHazard && !isHazard) set -= pos
    }

    private fun standingOn(blocks: Set<BlockPos>): Boolean {
        if (blocks.isEmpty()) return false
        val y = floor(player.y - 0.1).toInt()
        val px = floor(player.x).toInt()
        val pz = floor(player.z).toInt()
        return BlockPos(px, y, pz) in blocks ||
            BlockPos(px, y + 1, pz) in blocks
    }

    private fun findSafeBlock(activeDanger: Set<BlockPos>): BlockPos? {
        val origin = BlockPos.containing(player.x, player.y - 0.1, player.z)
        return buildList {
            for (dx in -dodgeRadius..dodgeRadius) {
                for (dz in -dodgeRadius..dodgeRadius) {
                    val floor = BlockPos(origin.x + dx, origin.y, origin.z + dz)
                    if (floor in activeDanger || floor in ddrBlocks || floor in fireBlocks) continue
                    if (floor.stateIsUnsafe()) continue
                    add(floor)
                }
            }
        }.minByOrNull { it.distSqr(origin) }
    }

    private fun BlockPos.stateIsUnsafe(): Boolean {
        val level = mc.level ?: return true
        val support = level.getBlockState(this)
        if (support.isAir || support.getCollisionShape(level, this).isEmpty) return true
        return !above().airLike || !above(2).airLike
    }

    private val BlockState.isDdrTerracotta: Boolean
        get() {
            val id = block.registryName
            return id.contains("yellow_terracotta") || id.contains("red_terracotta") || id.contains("brown_terracotta")
        }

    private fun reset() {
        equipTask?.cancel()
        equipTask = null
        ddrBlocks.clear()
        fireBlocks.clear()
        lastSwapAt = 0L
        lastDodgeAt = 0L
        fightConfirmedUntil = 0L
        stopDodge()
        AutomationCoordinator.release(OWNER)
    }

    private fun stopDodge() {
        if (ownsMovementTask && AutomationCoordinator.owner(MOVEMENT) == OWNER) cancelMovementTask()
        ownsMovementTask = false
        AutomationCoordinator.release(OWNER, MOVEMENT)
    }

    private enum class Attunement(vararg val daggers: String) {
        ASHEN("FIREDUST_DAGGER", "BURSTFIRE_DAGGER", "HEARTFIRE_DAGGER"),
        AURIC("FIREDUST_DAGGER", "BURSTFIRE_DAGGER", "HEARTFIRE_DAGGER"),
        SPIRIT("MAWDUST_DAGGER", "BURSTMAW_DAGGER", "HEARTMAW_DAGGER"),
        CRYSTAL("MAWDUST_DAGGER", "BURSTMAW_DAGGER", "HEARTMAW_DAGGER"),
    }

    private const val OWNER = "Blaze Slayer Automation"
    private const val FIGHT_CONFIRMATION_MS = 15_000L
}
