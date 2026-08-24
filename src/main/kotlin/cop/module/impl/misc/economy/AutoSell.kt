package cop.module.impl.misc.economy

import cop.api.commands.internal.GreedyString
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.config.configList
import cop.module.Module
import cop.module.impl.misc.ItemProtection
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INVENTORY
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import kotlin.random.Random

/** Explicit allow-list based selling with conservative item safety guards. */
object AutoSell : Module(
    "Auto Sell",
    desc = "Automatically sells allow-listed items in supported merchant menus.",
) {
    private val delayTicks by slider("Click delay", 6, 2, 20, 1, unit = "t")
    private val randomTicks by slider("Delay randomisation", 2, 0, 8, 1, unit = "t")
    private val clickType by selector("Click type", "Shift", listOf("Shift", "Middle", "Left"))
    private val allowStarred by switch("Allow starred items", false)
    private val allowRecombobulated by switch("Allow recombobulated items", false)
    private val allowReforged by switch("Allow reforged items", false)
    private val allowEnchanted by switch("Allow enchanted items", false)
    @Suppress("unused")
    private val addDefaults by button(
        "Add safe defaults",
        desc = "Adds common low-value dungeon and material drops to the sell list.",
    ) { addDefaultEntries() }

    private val sellList by configList<String>("auto_sell_items.json")
    private var nextClickAt = 0L

    init {
        val autoSell = command.sub("autosell").description("Manages Auto Sell's allow list.")
        autoSell.sub("add") { addHeld() }
        autoSell.sub("add") { name: GreedyString -> addName(name.string) }
        autoSell.sub("remove") { removeHeld() }
        autoSell.sub("remove") { name: GreedyString -> removeName(name.string) }
            .suggests { sellList.toList() }
        autoSell.sub("list") { listEntries() }
        autoSell.sub("clear") {
            val count = sellList.size
            sellList.clear()
            modMessage("&aCleared &f$count&a Auto Sell entries.")
        }
        autoSell.sub("defaults") { addDefaultEntries() }

        on<TickEvent.Start> {
            if (sellList.isEmpty()) return@on
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!isSellMenu(screen)) return@on

            val now = System.currentTimeMillis()
            if (now < nextClickAt) return@on
            val slotId = screen.menu.slots.indexOfFirst {
                it.container is Inventory && shouldSell(it.item)
            }.takeIf { it >= 0 } ?: return@on

            val duration = ((delayTicks + randomTicks) * 50L).coerceAtLeast(100L)
            if (!AutomationCoordinator.acquire(OWNER, duration, INVENTORY, INTERACTION)) return@on

            when (clickType.selected) {
                "Middle" -> player.clickSlot(slotId, screen.menu.containerId, button = 2)
                "Left" -> player.clickSlot(slotId, screen.menu.containerId)
                else -> player.clickSlot(slotId, screen.menu.containerId, shift = true)
            }
            nextClickAt = now + (delayTicks + if (randomTicks == 0) 0 else Random.nextInt(randomTicks + 1)) * 50L
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun isSellMenu(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string.noControlCodes
        if (SUPPORTED_TITLES.none { title.contains(it, ignoreCase = true) }) return false

        val marker = screen.menu.slots.getOrNull(49)?.item ?: return false
        val name = marker.hoverName.string.noControlCodes
        val lore = marker.lore.orEmpty().map { it.noControlCodes }
        return name.equals("Sell Item", true) || lore.any {
            it.contains("Click items in your inventory to sell", true) || it.contains("Click to buyback", true)
        }
    }

    private fun shouldSell(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (ItemProtection.isProtected(stack)) return false
        val name = stack.sellName() ?: return false
        if (name == "skeleton master chestplate") return false
        if (sellList.none { normalize(it) == name }) return false

        val attributes = stack.extraAttributes
        if (!allowStarred && (attributes?.getInt("upgrade_level")?.orElse(0) ?: 0) > 0) return false
        if (!allowRecombobulated && (attributes?.getInt("rarity_upgrades")?.orElse(0) ?: 0) > 0) return false
        if (!allowReforged && !attributes?.getString("modifier")?.orElse("").isNullOrBlank()) return false
        if (!allowEnchanted && attributes?.getCompound("enchantments")?.orElse(null)?.isEmpty == false) return false
        return true
    }

    private fun ItemStack.sellName(): String? {
        val modifier = extraAttributes?.getString("modifier")?.orElse(null)
        return normalize(customName?.string ?: hoverName.string, modifier).takeIf(String::isNotEmpty)
    }

    private fun normalize(raw: String, modifier: String? = null): String {
        var value = raw.noControlCodes
        if (!modifier.isNullOrBlank()) value = value.replace(modifier, "", ignoreCase = true)
        return value
            .replace(STACK_SIZE, "")
            .replace("'", "")
            .trim()
            .lowercase()
            .replace(WHITESPACE, " ")
    }

    private fun addHeld() {
        val stack = mc.player?.mainHandItem?.takeUnless(ItemStack::isEmpty)
            ?: return modMessage("&cHold an item or provide its name.")
        addName(stack.sellName() ?: return)
    }

    private fun removeHeld() {
        val stack = mc.player?.mainHandItem?.takeUnless(ItemStack::isEmpty)
            ?: return modMessage("&cHold an item or provide its name.")
        removeName(stack.sellName() ?: return)
    }

    private fun addName(raw: String) {
        val name = normalize(raw)
        if (name.isBlank()) return modMessage("&cItem name cannot be empty.")
        if (sellList.any { normalize(it) == name }) return modMessage("&e$name &7is already on the sell list.")
        sellList.add(name)
        modMessage("&aAdded &f$name&a to Auto Sell.")
    }

    private fun removeName(raw: String) {
        val name = normalize(raw)
        val matches = sellList.filter { normalize(it) == name }
        if (matches.isEmpty()) return modMessage("&e$name &7is not on the sell list.")
        sellList.removeAll(matches)
        modMessage("&aRemoved &f$name&a from Auto Sell.")
    }

    private fun listEntries() {
        if (sellList.isEmpty()) return modMessage("&cAuto Sell's list is empty.")
        modMessage(sellList.sorted().joinToString("\n") { "&7- &f$it" }, id = "auto_sell_list".hashCode())
    }

    private fun addDefaultEntries() {
        val existing = sellList.mapTo(hashSetOf(), ::normalize)
        val additions = DEFAULT_ITEMS.filter { normalize(it) !in existing }
        sellList.addAll(additions)
        modMessage("&aAdded &f${additions.size}&a safe default entries.")
    }

    private fun reset() {
        nextClickAt = 0L
        AutomationCoordinator.release(OWNER)
    }

    private const val OWNER = "Auto Sell"
    private val SUPPORTED_TITLES = listOf("Trades", "Booster Cookie", "Farm Merchant", "Ophelia")
    private val STACK_SIZE = Regex("^(?:[1-9]|[1-5]\\d|6[0-4])(?:\\s*[xX×])?\\s+|\\s+(?:[xX×]\\s*)?(?:[1-9]|[1-5]\\d|6[0-4])$")
    private val WHITESPACE = Regex("\\s+")
    private val DEFAULT_ITEMS = listOf(
        "enchanted ice", "superboom tnt", "rotten", "skeleton grunt", "cutlass", "skeleton soldier",
        "zombie soldier", "zombie knight", "skeletor", "heavy", "sniper helmet", "dreadlord",
        "earth shard", "machine gun", "sniper bow", "soulstealer bow", "training weight", "beating heart",
        "premium flesh", "mimic fragment", "enchanted rotten flesh", "enchanted bone", "defuse kit",
        "optical lens", "tripwire hook", "button", "carpet", "lever", "diamond atom", "candycomb",
    )
}
