package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import cop.CopMod.scope
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.PlayerUtils
import cop.utils.skyblock.player.SwapManager
import cop.utils.skyblock.player.SwapResult

/** Uses Wither Cloak on the F7/M7 boss-entry countdown title at 2. */
object AutoWitherCloak : Module(
    "Auto Wither Cloak",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Automatically activates Creeper Veil on the F7/M7 boss countdown."
) {
    private val useDelay by slider("Use delay", 3, 0, 10, 1, unit = "t", desc = "Delay after the countdown reaches 2.")
    private val swapBack by switch("Swap back", true, desc = "Returns to the original hotbar slot after cloaking.")
    private val swapBackDelay by slider("Swap-back delay", 2, 1, 10, 1, unit = "t")
        .childOf(::swapBack)
    private val statusMessage by switch("Status message", true, desc = "Reports a successful automatic cloak.")

    private var inCloak = false
    private var cloakJob: Job? = null
    private var worldEpoch = 0
    private var lastTriggerAt = 0L
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    init {
        on<WorldEvent.Change> {
            worldEpoch++
            reset()
        }

        on<ChatEvent.PacketClient> {
            when (message.noControlCodes) {
                "Creeper Veil Activated!" -> inCloak = true
                "Creeper Veil De-activated!",
                "Creeper Veil De-activated! (Expired)",
                "Not enough mana! Creeper Veil De-activated!" -> inCloak = false
            }
        }

        on<PacketEvent.ReceivedClient, ClientboundSetTitleTextPacket> {
            if (!Dungeon.isFloor(7) || !Dungeon.inBoss || Dungeon.isDead || Dungeon.inTerminal || mc.screen != null) return@on
            if (packet.text.string.noControlCodes != "2" || inCloak || cloakJob?.isActive == true) return@on

            val now = System.currentTimeMillis()
            if (now - lastTriggerAt < 3_000L) return@on
            lastTriggerAt = now
            startCloak(worldEpoch)
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun startCloak(epoch: Int) {
        val originalSlot = mc.player?.inventory?.selectedSlot?.takeIf { it in 0..8 } ?: return
        val cloakSlot = findCloakSlot() ?: run {
            if (statusMessage) modMessage("&cAuto Wither Cloak: Wither Cloak Sword is not on your hotbar.")
            return
        }
        if (!AutomationCoordinator.acquire(OWNER, 3_000L, Channel.HOTBAR, Channel.INTERACTION)) return

        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                val swap = SwapManager.swapToSlot(cloakSlot)
                if (swap != SwapResult.SUCCESS && swap != SwapResult.ALREADY_SELECTED) return@launch

                delay(useDelay * 50L)
                if (!enabled || epoch != worldEpoch || !Dungeon.isFloor(7) || !Dungeon.inBoss ||
                    Dungeon.isDead || Dungeon.inTerminal || mc.screen != null || inCloak
                ) return@launch
                if (mc.player?.mainHandItem?.skyblockId != "WITHER_CLOAK") return@launch

                PlayerUtils.interact()
                if (statusMessage) modMessage("&aCreeper Veil activated automatically.")
                delay(swapBackDelay * 50L)
            } finally {
                if (swapBack && epoch == worldEpoch && mc.player != null) {
                    SwapManager.swapToSlot(originalSlot)
                }
                AutomationCoordinator.release(OWNER)
            }
        }

        cloakJob = job
        job.invokeOnCompletion { mc.execute { if (cloakJob === job) cloakJob = null } }
        job.start()
    }

    private fun findCloakSlot(): Int? {
        val inventory = mc.player?.inventory ?: return null
        return (0..8).firstOrNull { inventory.getItem(it).skyblockId == "WITHER_CLOAK" }
    }

    private fun reset() {
        inCloak = false
        cloakJob?.cancel()
        cloakJob = null
        AutomationCoordinator.release(OWNER)
    }

    private const val OWNER = "AutoWitherCloak"
}
