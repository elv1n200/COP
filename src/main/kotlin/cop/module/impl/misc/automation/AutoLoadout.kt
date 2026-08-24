package cop.module.impl.misc.automation

import cop.CopMod.scope
import cop.api.events.KeyEvent
import cop.api.events.MouseEvent
import cop.api.events.WorldEvent
import cop.api.input.CatKeys
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.module.settings.impl.KeybindComponent
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.wait
import cop.utils.skyblock.ItemUtils.loreString
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INVENTORY
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import cop.utils.skyblock.player.ContainerUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.minecraft.client.KeyMapping

object AutoLoadout : Module(
    "Auto Loadout",
    desc = "Equips SkyBlock loadout slots from anywhere through commands or keybinds.",
) {
    private val preventMoving by switch("Prevent movement", true)
    private val blockInputs by switch("Block input", false)
    private val closeDelay by slider("Close delay", 4, 1, 20, 1, unit = "t")
    private val keybindHeader by text("Loadout keybinds")
    @Suppress("unused")
    private val loadoutKeys = (1..12).map { slot ->
        register(
            KeybindComponent("Loadout $slot", CatKeys.KEY_NONE, "Equips loadout slot $slot.")
                .childOf(::keybindHeader)
                .onPress { equip(slot) },
        )
    }

    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }
    private var job: Job? = null
    private var busy = false
    private var runGeneration = 0L
    private var ownedSession: ContainerUtils.ContainerSession? = null

    init {
        command.sub("loadout") { slot: Int -> equip(slot) }
            .description("Equips SkyBlock loadout slot 1-12.")

        on<KeyEvent.Input> {
            if (!busy || !preventMoving) return@on
            input.forward = false
            input.backward = false
            input.left = false
            input.right = false
            input.jump = false
        }
        on<KeyEvent.Press> { if (busy && blockInputs) cancel() }
        // Releases must reach vanilla or a key held before the automation can
        // remain logically pressed after it finishes.
        on<MouseEvent.Click> { if (busy && blockInputs && state) cancel() }
        on<MouseEvent.Scroll> { if (busy && blockInputs) cancel() }
        on<WorldEvent.Change> { stop() }
    }

    override fun onDisable() {
        stop()
        super.onDisable()
    }

    private fun equip(slot: Int) {
        if (!enabled) return modMessage("&cEnable Auto Loadout first.")
        if (slot !in 1..LOADOUT_SLOTS.size) return modMessage("&cLoadout slot must be 1-${LOADOUT_SLOTS.size}.")
        if (job?.isActive == true) return modMessage("&eA loadout action is already running.")
        if (mc.screen != null) return modMessage("&eClose the current menu before equipping a loadout.")

        val channels = if (preventMoving) arrayOf(INVENTORY, MOVEMENT) else arrayOf(INVENTORY)
        if (!AutomationCoordinator.acquire(OWNER, 8_000L, *channels)) {
            return modMessage("&eAnother inventory automation is active.")
        }

        val generation = ++runGeneration
        busy = true
        val launched = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                val snapshot = ContainerUtils.getContainerSnapshot(
                    "loadout", "(1/3) Loadouts", slots = 54, timeout = 50,
                ) ?: return@launch
                ownedSession = snapshot.session
                val items = snapshot.items
                val target = LOADOUT_SLOTS[slot - 1]
                val stack = items.getOrNull(target)
                if (stack == null || stack.loreString?.contains("Left-click to equip!", true) != true) {
                    modMessage("&cLoadout slot &e$slot &cis empty, locked or not ready.")
                    return@launch
                }

                if (!ContainerUtils.clickAwait(snapshot.session, target)) {
                    modMessage("&cCould not click loadout slot &e$slot&c.")
                    return@launch
                }
                wait(closeDelay)
                modMessage("&aEquipped loadout &f$slot&a.")
            } finally {
                // A cancelled predecessor must never close/release a newer
                // run which uses the same coordinator owner string.
                if (runGeneration == generation) {
                    ownedSession?.let { ContainerUtils.closeContainer(it) }
                    ownedSession = null
                    busy = false
                    if (blockInputs) KeyMapping.setAll()
                    AutomationCoordinator.release(OWNER)
                    job = null
                }
            }
        }
        job = launched
        launched.start()
    }

    private fun stop() {
        runGeneration++
        job?.cancel()
        job = null
        val restoreInputs = busy && blockInputs
        busy = false
        ownedSession?.let { ContainerUtils.closeContainer(it) }
        ownedSession = null
        if (restoreInputs) KeyMapping.setAll()
        AutomationCoordinator.release(OWNER)
    }

    private val LOADOUT_SLOTS = listOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)
    private const val OWNER = "Auto Loadout"
}
