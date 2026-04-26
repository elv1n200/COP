package cop.module.impl.misc

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.GuiEvent
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.lore

/**
 * Port of NoammAddons InventorySearch (com.github.noamm9.features.impl.misc.InventorySearch) — simplified.
 * Type characters while a container GUI is open to filter slots; matching items are
 * outlined. Backspace clears one char; ESC closes. No math evaluation (kept minimal).
 */
object InventorySearch : Module(
    "Inventory Search",
    desc = "Type letters while an inventory is open to highlight matching items."
) {
    private val ignoreCaps by switch("Ignore caps", true,
        desc = "Search is case-insensitive.")
    private val searchLore by switch("Search lore", true,
        desc = "Also match item lore (not just the name).")
    private val highlightColour by colourPicker("Highlight", Colour.RED.withAlpha(180), allowAlpha = true,
        desc = "Colour used to tint matching slots.")

    private var searchQuery: String = ""

    init {
        on<GuiEvent.Open> {
            searchQuery = ""
        }

        on<GuiEvent.Char> {
            if (screen !is AbstractContainerScreen<*>) return@on
            // ignore control characters
            if (char.code < 32) return@on
            searchQuery += char
            cancel()
        }

        on<GuiEvent.Key.Press> {
            if (screen !is AbstractContainerScreen<*>) return@on
            when (key) {
                259 -> { // GLFW_KEY_BACKSPACE
                    if (searchQuery.isNotEmpty()) {
                        searchQuery = searchQuery.dropLast(1)
                        cancel()
                    }
                }
                261 -> { // GLFW_KEY_DELETE
                    searchQuery = ""
                    cancel()
                }
            }
        }

        on<GuiEvent.Slot.Draw> {
            if (searchQuery.isBlank()) return@on
            val stack = slot.item.takeUnless { it.isEmpty } ?: return@on

            val name = stack.hoverName.string.noControlCodes
            val loreText = stack.lore?.joinToString("\n") { it.noControlCodes } ?: ""

            val nameMatch = name.contains(searchQuery, ignoreCase = ignoreCaps)
            val loreMatch = searchLore && loreText.contains(searchQuery, ignoreCase = ignoreCaps)

            if (nameMatch || loreMatch) {
                ctx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, highlightColour.rgb)
            }
        }

        on<GuiEvent.Draw.Post> {
            if (screen !is AbstractContainerScreen<*>) return@on
            if (searchQuery.isBlank()) return@on
            val sr = mc.window
            val x = sr.guiScaledWidth / 2 - 50
            val y = sr.guiScaledHeight - 30
            ctx.fill(x, y, x + 100, y + 12, 0xC8000000.toInt())
            ctx.drawString(mc.font, "§f$searchQuery", x + 2, y + 2, 0xFFFFFFFF.toInt(), true)
        }
    }
}
