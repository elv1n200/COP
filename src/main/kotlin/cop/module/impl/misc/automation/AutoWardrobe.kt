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
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INVENTORY
import cop.utils.skyblock.player.AutomationCoordinator.Channel.MOVEMENT
import cop.utils.skyblock.player.ContainerUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.minecraft.client.KeyMapping

object AutoWardrobe : Module(
    "Auto Wardrobe",
    desc = "Equips wardrobe slots from anywhere through commands or keybinds.",
) {
    private val disableUnequip by switch("Disable unequip", true)
    private val preventMoving by switch("Prevent movement", true)
    private val blockInputs by switch("Block input", false)
    private val closeDelay by slider("Close delay", 4, 1, 20, 1, unit = "t")
    private val keybindHeader by text("Wardrobe keybinds")
    @Suppress("unused")
    private val wardrobeKeys = (1..9).map { slot ->
        register(
            KeybindComponent("Wardrobe $slot", CatKeys.KEY_NONE, "Equips wardrobe slot $slot.")
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
        command.sub("wardrobe") { slot: Int -> equip(slot) }
            .description("Equips wardrobe slot 1-9.")

        on<KeyEvent.Input> {
            if (!busy || !preventMoving) return@on
            input.forward = false
            input.backward = false
            input.left = false
            input.right = false
            input.jump = false
        }
        on<KeyEvent.Press> { if (busy && blockInputs) cancel() }
        on<MouseEvent.Click> { if (busy && blockInputs && state) cancel() }
        on<MouseEvent.Scroll> { if (busy && blockInputs) cancel() }
        on<WorldEvent.Change> { stop() }
    }

    override fun onDisable() {
        stop()
        super.onDisable()
    }

    private fun equip(slot: Int) {
        if (!enabled) return modMessage("&cEnable Auto Wardrobe first.")
        if (slot !in 1..9) return modMessage("&cWardrobe slot must be 1-9.")
        if (job?.isActive == true) return modMessage("&eA wardrobe action is already running.")
        if (mc.screen != null) return modMessage("&eClose the current menu before equipping a wardrobe slot.")

        val channels = if (preventMoving) arrayOf(INVENTORY, MOVEMENT) else arrayOf(INVENTORY)
        if (!AutomationCoordinator.acquire(OWNER, 8_000L, *channels)) {
            return modMessage("&eAnother inventory automation is active.")
        }

        val generation = ++runGeneration
        busy = true
        val launched = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                val snapshot = ContainerUtils.getContainerSnapshot(
                    "wardrobe", "(1/3) Armor Sets", slots = 54, timeout = 50,
                ) ?: return@launch
                ownedSession = snapshot.session
                val items = snapshot.items
                val target = slot + 35
                val state = items.getOrNull(target)?.hoverName?.string?.noControlCodes.orEmpty()
                when {
                    state.endsWith(": Empty", true) || state.endsWith(": Locked", true) || state.isBlank() -> {
                        modMessage("&cWardrobe slot &e$slot &cis empty or locked.")
                        return@launch
                    }
                    disableUnequip && state.endsWith(": Equipped", true) -> {
                        modMessage("&eWardrobe slot &f$slot &eis already equipped.")
                        return@launch
                    }
                }

                if (!ContainerUtils.clickAwait(snapshot.session, target)) {
                    modMessage("&cCould not click wardrobe slot &e$slot&c.")
                    return@launch
                }
                wait(closeDelay)
                modMessage("&aEquipped wardrobe slot &f$slot&a.")
            } finally {
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

    private const val OWNER = "Auto Wardrobe"
}
