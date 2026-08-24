package cop.module.impl.misc

import cop.api.events.GuiEvent
import cop.api.events.KeyEvent
import cop.api.events.core.Priority
import cop.api.input.CatKeys
import cop.api.skyblock.dungeon.Dungeon
import cop.config.configList
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.key
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.ItemUtils.skyblockUuid
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack

/**
 * Local item-drop/sell guard. NoammAddons' ProtectItem was used only as a
 * behavioural reference; persistence, conservative menu recognition and the
 * event handling below are an independent COP implementation.
 */
object ItemProtection : Module(
    "Item Protection",
    desc = "Prevents protected, starred or recombobulated items from being dropped or sold accidentally.",
) {
    private val protectClickKey by keybind(
        "Protect-click key", CatKeys.KEY_L,
        desc = "Hold this key and click an inventory item to protect/unprotect that exact item.",
    )
    private val protectStarred by switch(
        "Protect starred items", true,
        desc = "Automatically protects items with dungeon/master stars.",
    )
    private val protectRecombobulated by switch(
        "Protect recombobulated items", true,
        desc = "Automatically protects items with a rarity upgrade.",
    )
    private val allowDungeonDropKey by switch(
        "Allow dungeon drop key", true,
        desc = "Does not block the drop-key press during a dungeon, where Hypixel uses it for class abilities.",
    )
    private val showSlotMarker by switch(
        "Show slot marker", true,
        desc = "Draws a small green P over protected inventory items.",
    )
    private val markerOnlySaved by switch(
        "Marker only for saved items", false,
        desc = "Do not mark items protected only because they are starred/recombobulated.",
    ).childOf(::showSlotMarker)

    private val protectedUuids by configList<String>("protected_item_uuids.json")
    private val protectedIds by configList<String>("protected_item_ids.json")

    init {
        command.sub("protect") { action: String? -> handleCommand(action) }
            .suggests("action", "toggle", "id", "list", "clear")
            .description("Protects the held item. Modes: toggle, id, list, clear.")

        on<KeyEvent.Press>(Priority.HIGHEST) {
            if (key != mc.options.keyDrop.key.value) return@on
            if (allowDungeonDropKey && Dungeon.inDungeons) return@on
            val stack = mc.player?.mainHandItem ?: return@on
            val reason = protectionReason(stack) ?: return@on

            cancel()
            blocked(stack, reason)
        }

        on<GuiEvent.Slot.Click>(Priority.HIGHEST) {
            val player = mc.player ?: return@on
            val menu = player.containerMenu
            val clickedStack = slot.item

            if (slot.container is Inventory && protectClickKey.isDown() && !clickedStack.isEmpty) {
                cancel()
                toggleExact(clickedStack)
                return@on
            }

            val atRiskStack = when {
                actionType == ClickType.THROW -> clickedStack
                !isDestructiveMenu(screen) -> ItemStack.EMPTY
                slot.container is Inventory -> clickedStack
                actionType == ClickType.SWAP -> when (button) {
                    in 0 until Inventory.getSelectionSize(), Inventory.SLOT_OFFHAND -> player.inventory.getItem(button)
                    else -> ItemStack.EMPTY
                }
                else -> menu.carried
            }
            val reason = protectionReason(atRiskStack) ?: return@on

            cancel()
            blocked(atRiskStack, reason)
        }

        on<GuiEvent.Slot.OutsideClick>(Priority.HIGHEST) {
            if (slotId != OUTSIDE_SLOT) return@on
            val carried = mc.player?.containerMenu?.carried ?: return@on
            val reason = protectionReason(carried) ?: return@on

            cancel()
            blocked(carried, reason)
        }

        on<GuiEvent.Slot.Draw> {
            if (!showSlotMarker || slot.container !is Inventory || slot.item.isEmpty) return@on
            val reason = protectionReason(slot.item) ?: return@on
            if (markerOnlySaved && reason != ProtectionReason.EXACT_ITEM && reason != ProtectionReason.ITEM_TYPE) return@on

            ctx.drawString(mc.font, "§aP", slot.x + 1, slot.y + 1, 0xFFFFFFFF.toInt(), true)
        }
    }

    private fun handleCommand(rawAction: String?) {
        when (rawAction?.lowercase() ?: "toggle") {
            "toggle", "exact" -> {
                val stack = heldItemOrMessage() ?: return
                toggleExact(stack)
            }

            "id", "type" -> {
                val stack = heldItemOrMessage() ?: return
                val id = stack.skyblockId?.trim()?.uppercase().orEmpty()
                if (id.isEmpty()) return modMessage("&cThis item has no SkyBlock ID.")
                toggle(protectedIds, id, "item type &f$id")
            }

            "list" -> modMessage(
                "&aItem Protection:&7 ${protectedUuids.size} exact item(s), ${protectedIds.size} item type(s).",
            )

            "clear" -> {
                val count = protectedUuids.size + protectedIds.size
                protectedUuids.clear()
                protectedIds.clear()
                modMessage("&aCleared &f$count&a saved item protection entries.")
            }

            else -> modMessage("&cUsage: /cop protect [toggle|id|list|clear]")
        }
    }

    private fun heldItemOrMessage(): ItemStack? {
        val stack = mc.player?.mainHandItem?.takeUnless(ItemStack::isEmpty)
        if (stack == null) modMessage("&cHold the item you want to protect first.")
        return stack
    }

    private fun toggleExact(stack: ItemStack) {
        val uuid = stack.skyblockUuid?.trim().orEmpty()
        if (uuid.isNotEmpty()) {
            toggle(protectedUuids, uuid, "&f${stack.hoverName.string.noControlCodes}")
            return
        }

        val id = stack.skyblockId?.trim()?.uppercase().orEmpty()
        if (id.isNotEmpty()) {
            toggle(protectedIds, id, "item type &f$id")
        } else {
            modMessage("&cThis item has neither a SkyBlock UUID nor an item ID.")
        }
    }

    private fun toggle(list: MutableList<String>, value: String, label: String) {
        if (list.remove(value)) modMessage("&eProtection removed for $label&e.")
        else {
            list.add(value)
            modMessage("&aProtection added for $label&a.")
        }
        if (!enabled) modMessage("&7Enable the Item Protection module for the guard to become active.")
    }

    /**
     * Shared guard for automated inventory actions which do not pass through
     * [GuiEvent.Slot.Click]. A disabled protection module deliberately does
     * not veto actions, matching the manual-click behaviour.
     */
    fun isProtected(stack: ItemStack): Boolean = enabled && protectionReason(stack) != null

    private fun protectionReason(stack: ItemStack): ProtectionReason? {
        if (stack.isEmpty) return null

        // `extraAttributes` returns a defensive NBT copy. Read it once here:
        // this path also runs for every visible inventory slot while markers
        // are enabled, so copying the same tag three times per frame adds up.
        val attributes = stack.extraAttributes
        val uuid = attributes?.getString("uuid")?.orElse(null)?.trim().orEmpty()
        if (uuid.isNotEmpty() && uuid in protectedUuids) return ProtectionReason.EXACT_ITEM

        val id = attributes?.getString("id")?.orElse(null)?.trim()?.uppercase().orEmpty()
        if (id.isNotEmpty() && id in protectedIds) return ProtectionReason.ITEM_TYPE

        val upgradeLevel = attributes?.getInt("upgrade_level")?.orElse(0) ?: 0
        if (protectStarred && (upgradeLevel > 0 || STAR_MARKERS.any(stack.hoverName.string::contains))) {
            return ProtectionReason.STARRED
        }

        val rarityUpgrades = attributes?.getInt("rarity_upgrades")?.orElse(0) ?: 0
        if (protectRecombobulated && rarityUpgrades > 0) return ProtectionReason.RECOMBOBULATED

        return null
    }

    private fun isDestructiveMenu(screen: net.minecraft.client.gui.screens.Screen): Boolean {
        val container = screen as? AbstractContainerScreen<*> ?: return false
        val menu = container.menu
        val topSlotCount = (menu.slots.size - PLAYER_INVENTORY_SLOTS).coerceAtLeast(0)
        if (topSlotCount == 0) return false

        val title = screen.title.string.noControlCodes
        if (title.contains("Salvage", ignoreCase = true)) return true

        return menu.slots.take(topSlotCount).any { topSlot ->
            val stack = topSlot.item
            if (stack.isEmpty) return@any false
            val name = stack.hoverName.string.noControlCodes
            name.contains("Sell Item", ignoreCase = true) ||
                name.contains("Salvage Item", ignoreCase = true) ||
                stack.lore.orEmpty().any {
                    it.contains("Click to buyback", ignoreCase = true) ||
                        it.contains("Click to salvage", ignoreCase = true)
                }
        }
    }

    private fun blocked(stack: ItemStack, reason: ProtectionReason) {
        modMessage(
            "&cBlocked an action on &f${stack.hoverName.string.noControlCodes}&c " +
                "&7(${reason.label}).",
            id = BLOCK_MESSAGE_ID,
        )
    }

    private enum class ProtectionReason(val label: String) {
        EXACT_ITEM("saved exact item"),
        ITEM_TYPE("saved item type"),
        STARRED("starred"),
        RECOMBOBULATED("recombobulated"),
    }

    private val STAR_MARKERS = charArrayOf('✪', '➊', '➋', '➌', '➍', '➎')
    private const val OUTSIDE_SLOT = -999
    private const val PLAYER_INVENTORY_SLOTS = 36
    private const val BLOCK_MESSAGE_ID = 0x434F5050
}
