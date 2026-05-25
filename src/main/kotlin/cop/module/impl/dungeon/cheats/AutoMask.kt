package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.launch
import cop.CopMod.scope
import cop.api.commands.internal.GreedyString
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes
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

    private var swappping = false

    init {
        command.sub("equip") { maskName: GreedyString ->
            triggerEquip(maskName.string)
        }.description("Automatically swaps to a specified mask.").requires("&cAuto Mask module is disabled!") { enabled }

        on<WorldEvent.Change> {
            swappping = false
        }

        on<TickEvent.Start> {
            if (stopMoving && swappping) player.stop()
        }

        on<ChatEvent.Packet> {
            if (dungeonsOnly && !Dungeon.inDungeons) return@on
            if (bossOnly && !Dungeon.inBoss) return@on
            if (p3Only && !Dungeon.inP3) return@on
            val messageRaw = message.noControlCodes

            val bonzoMsg = messageRaw == "Your Bonzo's Mask saved your life!" || messageRaw == "Your ⚚ Bonzo's Mask saved your life!"
            val spiritMsg = messageRaw == "Second Wind Activated! Your Spirit Mask saved your life!"

            if (bonzoMsg || spiritMsg) {
                triggerEquip(if (bonzoMsg) "spirit mask" else "bonzo's mask")
            }
        }
    }

    fun triggerEquip(maskName: String) {
        if (Dungeon.isDead || swappping || Dungeon.inTerminal) return

        val currentHelmet = player.inventory.getItem(39)
        val helmetName = currentHelmet.displayName.string

        if (helmetName.contains(maskName, ignoreCase = true)) return

        swappping = true
        scope.launch {
            try {
                equipMask(maskName)
            } finally {
                swappping = false
            }
        }
    }

    private suspend fun equipMask(name: String) {
        val success = ContainerUtils.getContainerItemsClick(
            command = "eq",
            container = "Your Equipment and Stats",
            name = name,
            inContainer = false,
            shift = true,
            cancelReopen = true
        )

        if (success) {
            scheduleTask(2) { ContainerUtils.closeContainer() }
        }
    }
}