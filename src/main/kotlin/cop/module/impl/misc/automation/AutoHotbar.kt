package cop.module.impl.misc.automation

import cop.CopMod.scope
import cop.api.commands.internal.GreedyString
import cop.api.events.ChatEvent
import cop.api.events.KeyEvent
import cop.api.events.MouseEvent
import cop.api.events.WorldEvent
import cop.config.configList
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.wait
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.ItemUtils.skyblockUuid
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.HOTBAR
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INVENTORY
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.minecraft.client.KeyMapping
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack

/** Persistent named hotbar presets with optional exact chat triggers. */
object AutoHotbar : Module(
    "Auto Hotbar",
    desc = "Saves and equips named hotbar presets with safe multi-pass correction.",
) {
    private val passDelay by slider("Pass delay", 4, 1, 20, 1, unit = "t")
    private val clickDelay by slider("Click delay", 2, 1, 10, 1, unit = "t")
    private val correctionPasses by slider("Correction passes", 3, 1, 5, 1)
    private val blockInput by switch(
        "Block input while swapping",
        false,
        desc = "Ignores keyboard and mouse input until the preset swap finishes.",
    )
    private val clearEmptySlots by switch(
        "Enforce empty slots",
        false,
        desc = "Moves items out of hotbar positions saved as empty. Requires a free inventory slot.",
    )

    private val presets by configList<HotbarPreset>("hotbar_presets.json")
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }
    private var swapJob: Job? = null
    private var blocking = false
    private var runGeneration = 0L

    init {
        val hotbar = command.sub("hotbar").description("Manages Auto Hotbar presets.")
        hotbar.sub("save") { name: GreedyString -> savePreset(name.string) }
        hotbar.sub("load") { name: GreedyString -> loadPreset(name.string) }
            .suggests { presets.map { it.name } }
        hotbar.sub("delete") { name: GreedyString -> deletePreset(name.string) }
            .suggests { presets.map { it.name } }
        hotbar.sub("list") { listPresets() }
        hotbar.sub("settrigger") { preset: String, message: GreedyString -> setTrigger(preset, message.string) }
            .suggests("preset") { presets.map { it.name } }
        hotbar.sub("cleartrigger") { preset: GreedyString -> setTrigger(preset.string, null) }
            .suggests { presets.map { it.name } }

        on<ChatEvent.PacketClient> {
            val clean = message.noControlCodes
            presets.firstOrNull { it.trigger?.equals(clean, ignoreCase = false) == true }?.let { loadPreset(it.name) }
        }

        on<KeyEvent.Press> { if (blockInput && blocking) cancel() }
        on<MouseEvent.Click> { if (blockInput && blocking && state) cancel() }
        on<MouseEvent.Scroll> { if (blockInput && blocking) cancel() }
        on<MouseEvent.Move> { if (blockInput && blocking) cancel() }
        on<WorldEvent.Change> { stopSwap() }
    }

    override fun onDisable() {
        stopSwap()
        super.onDisable()
    }

    private fun savePreset(rawName: String) {
        val name = rawName.trim().take(32)
        if (name.isEmpty()) return modMessage("&cPreset name cannot be empty.")
        if (presets.any { it.name.equals(name, true) }) return modMessage("&cA preset named &e$name &calready exists.")
        val inventory = mc.player?.inventory ?: return

        presets.add(
            HotbarPreset(
                name = name,
                slots = (0..8).map { HotbarItem.from(inventory.getItem(it)) }.toMutableList(),
            ),
        )
        modMessage("&aSaved hotbar preset &e$name&a.")
    }

    private fun loadPreset(rawName: String) {
        if (!enabled) return modMessage("&cEnable Auto Hotbar first.")
        if (swapJob?.isActive == true) return modMessage("&eA hotbar preset is already being equipped.")
        val preset = findPreset(rawName) ?: return modMessage("&cNo preset matching &e${rawName.trim()}&c.")
        val currentPlayer = mc.player ?: return modMessage("&cJoin a world before equipping a hotbar preset.")
        if (currentPlayer.containerMenu !== currentPlayer.inventoryMenu) {
            return modMessage("&eClose the current container before equipping a hotbar preset.")
        }
        if (!AutomationCoordinator.acquire(OWNER, 15_000L, HOTBAR, INVENTORY)) {
            return modMessage("&eAnother inventory automation is currently active.")
        }

        val generation = ++runGeneration
        blocking = true
        val launched = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            try {
                modMessage("&7Equipping hotbar preset &e${preset.name}&7...")
                var menuStayedValid = true
                for (pass in 0 until correctionPasses) {
                    if (pass > 0) wait(passDelay)
                    if (!runPass(preset)) {
                        menuStayedValid = false
                        break
                    }
                }

                val unresolved = unresolvedSlots(preset)
                when {
                    !menuStayedValid -> modMessage("&cHotbar swap stopped because another container became active.")
                    unresolved.isEmpty() -> modMessage("&aHotbar preset &e${preset.name} &aequipped.")
                    else -> modMessage(
                        "&eHotbar preset &f${preset.name} &ecould not fill slot(s) &f${unresolved.joinToString()}&e.",
                    )
                }
            } finally {
                if (runGeneration == generation) {
                    blocking = false
                    if (blockInput) KeyMapping.setAll()
                    AutomationCoordinator.release(OWNER)
                    swapJob = null
                }
            }
        }
        swapJob = launched
        launched.start()
    }

    private suspend fun runPass(preset: HotbarPreset): Boolean {
        val currentPlayer = mc.player ?: return false
        if (currentPlayer.containerMenu !== currentPlayer.inventoryMenu) return false
        val inventory = currentPlayer.inventory
        val touched = hashSetOf<Int>()

        for (target in 0..8) {
            val desired = preset.slots.getOrNull(target) ?: HotbarItem()
            val current = inventory.getItem(target)
            if (desired.matches(current)) continue

            val source = if (desired.isEmpty) {
                if (!clearEmptySlots || current.isEmpty) continue
                (9..35).firstOrNull { it !in touched && inventory.getItem(it).isEmpty } ?: continue
            } else {
                (0..35).firstOrNull { slot ->
                    slot != target && slot !in touched && desired.matches(inventory.getItem(slot))
                } ?: continue
            }

            val menuSlot = if (source in 0..8) source + 36 else source
            if (currentPlayer.containerMenu !== currentPlayer.inventoryMenu) return false
            mc.gameMode?.handleInventoryMouseClick(
                currentPlayer.inventoryMenu.containerId,
                menuSlot,
                target,
                ClickType.SWAP,
                currentPlayer,
            ) ?: continue

            touched += source
            touched += target
            wait(clickDelay)
        }
        return true
    }

    private fun unresolvedSlots(preset: HotbarPreset): List<Int> {
        val inventory = mc.player?.inventory ?: return (1..9).toList()
        return (0..8).mapNotNull { slot ->
            val desired = preset.slots.getOrNull(slot) ?: HotbarItem()
            val resolved = if (desired.isEmpty && !clearEmptySlots) true else desired.matches(inventory.getItem(slot))
            (slot + 1).takeUnless { resolved }
        }
    }

    private fun deletePreset(rawName: String) {
        val preset = findPreset(rawName) ?: return modMessage("&cPreset not found.")
        presets.remove(preset)
        modMessage("&aDeleted hotbar preset &e${preset.name}&a.")
    }

    private fun setTrigger(rawName: String, message: String?) {
        val preset = findPreset(rawName) ?: return modMessage("&cPreset not found.")
        preset.trigger = message?.trim()?.takeIf(String::isNotEmpty)
        presets.save()
        if (preset.trigger == null) modMessage("&aCleared the trigger for &e${preset.name}&a.")
        else modMessage("&aPreset &e${preset.name} &awill trigger on: &f${preset.trigger}")
    }

    private fun listPresets() {
        if (presets.isEmpty()) return modMessage("&cNo hotbar presets saved.")
        modMessage(
            presets.joinToString("\n") {
                "&e${it.name}&7 — trigger: &f${it.trigger ?: "none"}"
            },
            id = "hotbar_presets".hashCode(),
        )
    }

    private fun findPreset(name: String): HotbarPreset? {
        val query = name.trim()
        return presets.firstOrNull { it.name.equals(query, true) }
            ?: presets.firstOrNull { it.name.contains(query, true) }
    }

    private fun stopSwap() {
        runGeneration++
        swapJob?.cancel()
        swapJob = null
        val restoreInputs = blocking && blockInput
        blocking = false
        if (restoreInputs) KeyMapping.setAll()
        AutomationCoordinator.release(OWNER)
    }

    data class HotbarPreset(
        var name: String = "",
        var slots: MutableList<HotbarItem> = mutableListOf(),
        var trigger: String? = null,
    )

    data class HotbarItem(
        var uuid: String? = null,
        var id: String? = null,
        var name: String = "None",
    ) {
        val isEmpty: Boolean get() = uuid == null && id == null

        fun matches(stack: ItemStack?): Boolean {
            if (stack == null || stack.isEmpty) return isEmpty
            uuid?.let { expected ->
                return stack.skyblockUuid == expected
            }
            return id?.let { expected -> stack.skyblockId?.equals(expected, true) == true } ?: false
        }

        companion object {
            fun from(stack: ItemStack?): HotbarItem {
                if (stack == null || stack.isEmpty) return HotbarItem()
                return HotbarItem(stack.skyblockUuid, stack.skyblockId, stack.hoverName.string.noControlCodes)
            }
        }
    }

    private const val OWNER = "Auto Hotbar"
}
