package cop.module.impl.dungeon.cheats

import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.phys.AABB
import cop.api.events.MouseEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.M7Phases
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.Scheduler
import cop.utils.SoundUtils
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import kotlin.reflect.KProperty0

/**
 * Server-tick based Last Breath helper for F7/M7 debuffing.
 *
 * A use-item acknowledgement arms the timer, so a lag spike before the server
 * accepts the bow draw does not eat into the configured charge duration. The
 * helper releases and (optionally) redraws while the physical right mouse
 * button remains held.
 */
object DebuffHelper : Module(
    "Debuff Helper",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Fires Last Breath after a configurable number of server ticks."
) {
    private const val LEASE_OWNER = "dungeon-debuff-helper"

    private val semiAuto by switch(
        "Semi-auto",
        true,
        desc = "Re-draws Last Breath while right click remains held."
    )
    private val playSound by switch(
        "Charged sound",
        true,
        desc = "Plays a sound whenever the configured charge time is reached."
    )
    private val chargedSound = sound("Charged").childOf(::playSound)

    private val phaseTicksHeader by text("F7 phase charge ticks")
    private val p1Ticks by chargeSlider("P1 ticks", ::phaseTicksHeader)
    private val p2Ticks by chargeSlider("P2 ticks", ::phaseTicksHeader)
    private val p3Ticks by chargeSlider("P3 ticks", ::phaseTicksHeader)
    private val p4Ticks by chargeSlider("P4 ticks", ::phaseTicksHeader)

    private val dragonTicksHeader by text("M7 dragon charge ticks")
    private val purpleTicks by chargeSlider("Purple ticks", ::dragonTicksHeader)
    private val greenTicks by chargeSlider("Green ticks", ::dragonTicksHeader)
    private val redTicks by chargeSlider("Red ticks", ::dragonTicksHeader)
    private val orangeTicks by chargeSlider("Orange ticks", ::dragonTicksHeader)
    private val blueTicks by chargeSlider("Blue ticks", ::dragonTicksHeader)

    private var physicalUseHeld = false
    private var armed = false
    private var lastUseSequence = -1
    private var ticksHeld = 0
    private var redrawTask: Scheduler.Task? = null

    init {
        on<MouseEvent.Click> {
            if (button != 1) return@on
            physicalUseHeld = state
            if (!state) resetCharge()
        }

        on<PacketEvent.Sent, ServerboundUseItemPacket> {
            if (!isHoldingLastBreath()) return@on
            lastUseSequence = packet.sequence
        }

        on<PacketEvent.ReceivedClient, ClientboundBlockChangedAckPacket> {
            if (packet.sequence != lastUseSequence || !physicalUseHeld || !isHoldingLastBreath()) return@on

            val chargeTicks = configuredTicks()
            if (chargeTicks <= 0) return@on resetCharge()
            if (!AutomationCoordinator.acquire(
                    LEASE_OWNER,
                    (chargeTicks + 8L) * 50L,
                    Channel.INTERACTION
                )) return@on resetCharge()

            armed = true
            ticksHeld = 0
        }

        on<TickEvent.Server> {
            if (!armed) return@on
            if (!physicalUseHeld || mc.screen != null || !isHoldingLastBreath()) return@on resetCharge()

            val targetTicks = configuredTicks()
            if (targetTicks <= 0) return@on resetCharge()
            if (!AutomationCoordinator.extend(
                    LEASE_OWNER,
                    (targetTicks + 5L) * 50L,
                    Channel.INTERACTION
                )) return@on resetCharge()

            ticksHeld++
            if (ticksHeld >= targetTicks) fire()
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun chargeSlider(name: String, parent: KProperty0<*>) =
        slider(
            name,
            8,
            0,
            20,
            1,
            unit = "t",
            desc = "Server ticks to charge; 0 disables this phase."
        ).childOf(parent)

    private fun fire() {
        val player = mc.player ?: return resetCharge()
        if (playSound) SoundUtils.play(chargedSound)

        // Releasing the key is handled by vanilla on the next client tick and
        // sends the normal release-use-item packet for the bow.
        mc.options.keyUse.isDown = false
        armed = false
        ticksHeld = 0
        lastUseSequence = -1

        redrawTask?.cancel()
        if (!semiAuto) {
            AutomationCoordinator.release(LEASE_OWNER)
            return
        }

        redrawTask = Scheduler.scheduleTaskHandle(2) {
            redrawTask = null
            if (enabled && physicalUseHeld && mc.screen == null && isHoldingLastBreath()) {
                mc.options.keyUse.isDown = true
            }
            AutomationCoordinator.release(LEASE_OWNER)
        }
    }

    private fun configuredTicks(): Int {
        val position = mc.player?.position() ?: return 0
        return when (Dungeon.getF7Phase()) {
            M7Phases.P1 -> p1Ticks
            M7Phases.P2 -> p2Ticks
            M7Phases.P3 -> p3Ticks
            M7Phases.P4 -> p4Ticks
            M7Phases.P5 -> when {
                PURPLE_DRAGON.contains(position) -> purpleTicks
                GREEN_DRAGON.contains(position) -> greenTicks
                RED_DRAGON.contains(position) -> redTicks
                ORANGE_DRAGON.contains(position) -> orangeTicks
                BLUE_DRAGON.contains(position) -> blueTicks
                else -> 0
            }
            M7Phases.Unknown -> 0
        }
    }

    private fun isHoldingLastBreath(): Boolean =
        mc.player?.mainHandItem?.skyblockId?.contains("LAST_BREATH", ignoreCase = true) == true

    private fun resetCharge() {
        armed = false
        ticksHeld = 0
        lastUseSequence = -1
        redrawTask?.cancel()
        redrawTask = null
        AutomationCoordinator.release(LEASE_OWNER)
    }

    private fun reset() {
        physicalUseHeld = false
        mc.options.keyUse.isDown = false
        resetCharge()
    }

    private val PURPLE_DRAGON = AABB(47.0, 8.0, 113.0, 64.0, 28.0, 135.0)
    private val GREEN_DRAGON = AABB(13.0, 5.0, 85.0, 40.0, 27.0, 103.0)
    private val RED_DRAGON = AABB(13.0, 4.0, 47.0, 40.0, 20.0, 68.0)
    private val ORANGE_DRAGON = AABB(72.0, 3.0, 47.0, 97.0, 31.0, 65.0)
    private val BLUE_DRAGON = AABB(72.0, 3.0, 85.0, 97.0, 31.0, 107.0)
}
