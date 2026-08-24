package cop.utils.skyblock.player

import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import cop.CopMod.mc
import cop.annotations.Init
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus.on
import cop.api.events.core.Priority
import cop.api.skyblock.dungeon.Dungeon.dungeonTeammatesNoSelf
import cop.api.skyblock.dungeon.Dungeon.getMageCooldownMultiplier
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.dungeon.DungeonPlayer
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator.Channel

@Init
object LeapManager {
    private val leapQueue = mutableListOf<String>()
    private var menuOpened = false
    private var inProgress = false
    private var selectionCommitted = false
    private var leapContainerId = -1
    private var actionGeneration = 0

    private var pendingLeap: DungeonPlayer? = null
    private var pendingLeapSince = 0L

    var lastLeap = 0L
        private set

    var leapCD = 0.0
        private set

    private val currentLeap get() = leapQueue.firstOrNull()

    init {
        on<PacketEvent.Received> (Priority.LOWEST) {
            when (packet) {
                is ClientboundContainerSetSlotPacket -> {
                    if (!inProgress || packet.containerId != leapContainerId) return@on
                    cancel()
                    if (!menuOpened || selectionCommitted || packet.slot !in 0..<LEAP_MENU_SLOTS) return@on
                    val target = currentLeap ?: return@on
                    if (!packet.item.isEmpty && packet.item.displayName.string.contains(target, ignoreCase = true)) {
                        selectLeapSlot(packet.slot)
                    }
                }

                is ClientboundContainerSetContentPacket -> {
                    if (!inProgress || packet.containerId != leapContainerId) return@on
                    cancel()
                    if (!menuOpened || selectionCommitted) return@on
                    val target = currentLeap ?: return@on
                    val menuItems = packet.items.take(LEAP_MENU_SLOTS)
                    val targetSlot = menuItems.indexOfFirst {
                        !it.isEmpty && it.displayName.string.contains(target, ignoreCase = true)
                    }
                    if (targetSlot >= 0) {
                        selectLeapSlot(targetSlot)
                    } else if (packet.items.size >= LEAP_MENU_SLOTS) {
                        modMessage("§cFailed to leap! §r$target §cnot found!")
                        finishActiveLeap()
                    }
                }

                is ClientboundOpenScreenPacket -> {
                    if (!inProgress || currentLeap == null) return@on
                    if (!packet.title.string.contains("Leap", ignoreCase = true)) return@on
                    leapContainerId = packet.containerId
                    menuOpened = true
                    cancel()
                }

                is ClientboundContainerClosePacket -> {
                    if (inProgress && packet.containerId == leapContainerId) finishActiveLeap(closeMenu = false)
                }
            }
        }

        on<ChatEvent.PacketClient> {
            if (!inProgress) return@on
            if (message.noControlCodes == "You cannot use this in a solo dungeon!" ||
                message.noControlCodes == "There are no other players to teleport to!") {
                modMessage("&cFailed to leap! You're in a solo dungeon!")
                finishActiveLeap()
            }
        }

        on<TickEvent.Server> {
            if (leapCD > 0) leapCD -= 1

            val target = pendingLeap ?: return@on
            if (System.currentTimeMillis() - pendingLeapSince > PENDING_TIMEOUT_MS) {
                pendingLeap = null
                pendingLeapSince = 0L
                modMessage("&cQueued leap to &r${target.name} &ctimed out.")
                return@on
            }
            if (inProgress || mc.screen != null || ContainerUtils.containerId != -1) return@on

            when (doLeap(target)) {
                LeapAttempt.STARTED, LeapAttempt.FAILED -> {
                    pendingLeap = null
                    pendingLeapSince = 0L
                }
                LeapAttempt.RETRY -> Unit
            }
        }

        on<WorldEvent.Change> { reset() }
    }

    fun leap(target: Any): Boolean {
        if (!inDungeons || target == DungeonClass.Unknown || inProgress || pendingLeap != null) return false

        val teammate = when (target) {
            is String -> dungeonTeammatesNoSelf.firstOrNull { it.name.equals(target, true) }
            is DungeonClass -> dungeonTeammatesNoSelf.firstOrNull { it.clazz == target }
            else -> null
        }
        if (teammate == null) {
            modMessage("&cFailed to leap! &r$target &cnot found")
            return false
        }
        if (leapCD > 0) {
            modMessage("&cFailed to leap! On cooldown: ${"%.1f".format(leapCD / 20.0)}s")
            return false
        }

        if (mc.screen != null || ContainerUtils.containerId != -1) {
            queuePendingLeap(teammate)
            modMessage("&eQueued leap to &f${teammate.name}")
            return true
        }

        return when (doLeap(teammate)) {
            LeapAttempt.STARTED -> true
            LeapAttempt.RETRY -> {
                queuePendingLeap(teammate)
                modMessage("&eQueued leap to &f${teammate.name}")
                true
            }
            LeapAttempt.FAILED -> false
        }
    }

    private fun doLeap(target: DungeonPlayer): LeapAttempt {
        if (inProgress) return LeapAttempt.RETRY
        if (leapCD > 0) {
            modMessage("&cFailed to leap! On cooldown: ${"%.1f".format(leapCD / 20.0)}s")
            return LeapAttempt.FAILED
        }
        if (!AutomationCoordinator.acquire(
                OWNER,
                ACTION_LEASE_MS,
                Channel.HOTBAR,
                Channel.INVENTORY,
                Channel.INTERACTION
            )) return LeapAttempt.RETRY

        val swap = SwapManager.swapById("INFINITE_SPIRIT_LEAP", "SPIRIT_LEAP")
        if (!swap.success) {
            AutomationCoordinator.release(OWNER)
            return if (swap == SwapResult.TOO_FAST) LeapAttempt.RETRY else LeapAttempt.FAILED
        }

        // Do not expose a queued leap until the held item was actually selected.
        inProgress = true
        menuOpened = false
        selectionCommitted = false
        leapContainerId = -1
        leapQueue.clear()
        leapQueue.add(target.name)
        val generation = ++actionGeneration

        scheduleTask {
            if (generation != actionGeneration || !inProgress) return@scheduleTask
            val heldId = mc.player?.mainHandItem?.skyblockId
            if (mc.screen != null || ContainerUtils.containerId != -1 || heldId !in LEAP_ITEM_IDS) {
                modMessage("&cFailed to leap: the selected item or menu changed.")
                finishActiveLeap()
                return@scheduleTask
            }
            PlayerUtils.interact()
            lastLeap = System.currentTimeMillis()
            leapCD = 48 * getMageCooldownMultiplier()
            modMessage("&aLeaping to &r${target.name}")
        }

        scheduleTask(LEAP_TIMEOUT_TICKS) {
            if (generation != actionGeneration || !inProgress) return@scheduleTask
            modMessage("&cFailed to leap! The leap menu timed out.")
            finishActiveLeap()
        }
        return LeapAttempt.STARTED
    }

    private fun selectLeapSlot(slot: Int) {
        if (selectionCommitted) return
        if (leapContainerId == -1 || ContainerUtils.containerId != leapContainerId || !ContainerUtils.click(slot)) {
            modMessage("&cFailed to click the leap target.")
            finishActiveLeap()
            return
        }

        selectionCommitted = true
        menuOpened = false
        val generation = actionGeneration
        val ownedContainer = leapContainerId

        // ContainerUtils.click sends on the next client tick. Close only after
        // that packet, and only if this exact hidden menu still exists.
        scheduleTask(2) {
            if (generation != actionGeneration || !inProgress) return@scheduleTask
            ContainerUtils.closeContainer(ownedContainer)
            finishActiveLeap(closeMenu = false)
        }
    }

    private fun queuePendingLeap(target: DungeonPlayer) {
        pendingLeap = target
        pendingLeapSince = System.currentTimeMillis()
    }

    private fun finishActiveLeap(closeMenu: Boolean = true) {
        val ownedContainer = leapContainerId
        if (closeMenu && ownedContainer != -1) ContainerUtils.closeContainer(ownedContainer)
        actionGeneration++
        leapQueue.clear()
        menuOpened = false
        inProgress = false
        selectionCommitted = false
        leapContainerId = -1
        AutomationCoordinator.release(OWNER)
    }

    private fun reset() {
        actionGeneration++
        leapQueue.clear()
        menuOpened = false
        inProgress = false
        selectionCommitted = false
        leapContainerId = -1
        pendingLeap = null
        pendingLeapSince = 0L
        lastLeap = 0L
        leapCD = 0.0
        AutomationCoordinator.release(OWNER)
    }

    private enum class LeapAttempt { STARTED, RETRY, FAILED }

    private const val OWNER = "LeapManager"
    private const val LEAP_MENU_SLOTS = 36
    private const val LEAP_TIMEOUT_TICKS = 80
    private const val PENDING_TIMEOUT_MS = 5_000L
    private const val ACTION_LEASE_MS = 5_000L
    private val LEAP_ITEM_IDS = setOf("INFINITE_SPIRIT_LEAP", "SPIRIT_LEAP")
}
