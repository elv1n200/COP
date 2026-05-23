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
    private val highlightColour by colourPicker("Highlight", Colour.RGB(255, 220, 40, 1f), allowAlpha = true,
        desc = "Colour of the outline drawn around matching slots.")
    private val borderWidth by slider("Border width", 2, 1, 4, 1,
        desc = "Thickness of the outline in pixels.", unit = "px")
    private val tintFill by switch("Tint fill", true,
        desc = "Also tint the slot interior (peeks through transparent parts of the item).")

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
                val c = highlightColour.rgb
                val bw = borderWidth
                // Outline drawn OUTSIDE the 16x16 item area (on the slot frame),
                // so the item icon can't cover it. This is the visible cue.
                ctx.fill(slot.x - bw, slot.y - bw, slot.x + 16 + bw, slot.y, c)            // top
                ctx.fill(slot.x - bw, slot.y + 16, slot.x + 16 + bw, slot.y + 16 + bw, c)  // bottom
                ctx.fill(slot.x - bw, slot.y, slot.x, slot.y + 16, c)                       // left
                ctx.fill(slot.x + 16, slot.y, slot.x + 16 + bw, slot.y + 16, c)             // right
                // Faint interior tint (mostly hidden by opaque icons, but peeks
                // through transparent edges — extra contrast on busy items).
                if (tintFill) {
                    ctx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, (c and 0x00FFFFFF) or 0x40000000)
                }
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
