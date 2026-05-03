package cop.module.impl.misc

import cop.module.Module
import cop.utils.skyblock.ItemUtils.extraAttributes
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.network.chat.Component

/**
 * Adds a "Dungeon Quality: X / 50" line to the tooltip of any dungeon item
 * carrying the `dungeon_quality` NBT field. Comparable to SkyHanni / Athen's
 * "Item quality" feature; we read straight from `extraAttributes` since COP
 * already exposes that.
 *
 * @author elvin
 */
object ItemQuality : Module(
    "Item Quality",
    desc = "Shows the dungeon star quality (#stars + crystals out of 50) on item tooltips.",
) {
    private val format by textInput(
        "Format", "&7Dungeon Quality: &c#cur&8/&c#max &8(#floor)",
        desc = "Use #cur (current quality), #max (always 50), #floor (e.g. \"Floor 7\").",
        length = 64,
    )
    private val insertAtTop by switch(
        "Insert at top", true,
        desc = "Place the line right under the item name. When off, it's appended at the very end.",
    )

    init {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled) return@register
            val nbt = stack.extraAttributes ?: return@register
            val quality = nbt.getInt("dungeon_quality").orElse(null) ?: return@register
            val tier = nbt.getInt("dungeon_item_tier").orElse(null)

            val text = format
                .replace("&", "§")
                .replace("#cur", quality.toString())
                .replace("#max", "50")
                .replace("#floor", tier?.let { "Floor $it" } ?: "")

            val insertAt = if (insertAtTop) (1).coerceAtMost(lines.size) else lines.size
            lines.add(insertAt, Component.literal(text))
        }
    }
}
