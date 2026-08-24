package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cop.CopMod.scope
import cop.api.commands.internal.GreedyString
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.SkyblockPlayer.InvincibilityType
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.ContainerUtils
import cop.utils.skyblock.player.MovementUtils.stop

// Kyleen
object AutoMask : Module( // todo remove in the future
    "Auto Mask",
    desc = "Automatically swaps to invincibility mask."
) {

    private val dungeonsOnly by switch("Dungeons only")
    private val bossOnly by switch("Boss only")
    private val p3Only by switch("Phase 3 only")
    private val stopMoving by switch("Prevent moving", true)

    private var equipJob: Job? = null
    private var worldEpoch = 0
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    init {
        command.sub("equip") { maskName: GreedyString ->
            triggerEquip(maskName.string)
        }.description("Automatically swaps to a specified mask.").requires("&cAuto Mask module is disabled!") { enabled }

        on<WorldEvent.Change> {
            worldEpoch++
            reset()
        }

        on<TickEvent.Start> {
            if (stopMoving && equipJob?.isActive == true) player.stop()
        }

        on<ChatEvent.PacketClient> {
            if (dungeonsOnly && !Dungeon.inDungeons) return@on
            if (bossOnly && !Dungeon.inBoss) return@on
            if (p3Only && !Dungeon.inP3) return@on
            val messageRaw = message.noControlCodes

            val proc = InvincibilityType.entries.firstOrNull { messageRaw.matches(it.regex) }
                ?.takeIf { it == InvincibilityType.BONZO || it == InvincibilityType.SPIRIT }
                ?: return@on

            // Suppress the legacy swap only when the full chain will actually
            // act on this proc under its current floor/phase/settings.
            if (AutoInvincibility.willHandleProc(proc)) return@on
            triggerEquip(if (proc == InvincibilityType.BONZO) "spirit mask" else "bonzo's mask")
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    fun triggerEquip(maskName: String): Boolean {
        if (Dungeon.isDead || equipJob != null || Dungeon.inTerminal || mc.screen != null) return false

        val currentHelmet = player.inventory.getItem(39)
        val helmetName = currentHelmet.displayName.string

        if (helmetName.contains(maskName, ignoreCase = true)) return false
        if (!AutomationCoordinator.acquire(
                OWNER,
                8_000L,
                Channel.INVENTORY,
                Channel.MOVEMENT,
                Channel.INTERACTION,
            )) return false

        val epoch = worldEpoch
        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                equipMask(maskName, epoch)
            } finally {
                AutomationCoordinator.release(OWNER)
            }
        }
        equipJob = job
        job.invokeOnCompletion { mc.execute { if (equipJob === job) equipJob = null } }
        job.start()
        return true
    }

    private fun reset() {
        equipJob?.cancel()
        AutomationCoordinator.release(OWNER)
    }

    /** Allows composite dungeon automations to abort a mask swap they started. */
    fun cancelPendingEquip() {
        reset()
    }

    private suspend fun equipMask(name: String, epoch: Int) {
        val success = ContainerUtils.getContainerItemsClick(
            command = "eq",
            container = "Your Equipment and Stats",
            name = name,
            inContainer = false,
            shift = true,
            cancelReopen = true,
            closeAfterClick = true,
        )

        if (success && epoch == worldEpoch) delay(100L)
    }

    private const val OWNER = "AutoMask"
}
