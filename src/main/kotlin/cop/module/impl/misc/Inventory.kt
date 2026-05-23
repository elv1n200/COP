package cop.module.impl.misc

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.LivingEntity
import cop.api.abobaui.constraints.impl.size.Fill
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.Element
import cop.api.abobaui.elements.Layout.Companion.divider
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.RefreshableGroup
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.string
import cop.api.abobaui.elements.impl.TextInput.Companion.maxWidth
import cop.api.abobaui.elements.impl.TextInput.Companion.onTextChanged
import cop.api.abobaui.elements.impl.refreshableGroup
import cop.api.colour.*
import cop.api.events.GuiEvent
import cop.api.events.KeyEvent
import cop.api.events.TickEvent
import cop.api.events.core.Priority
import cop.utils.Scheduler.scheduleTask
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import cop.api.input.CursorShape
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.toFixed
import cop.utils.StringUtils.width
import cop.utils.render.DrawContextUtils.drawEntity
import cop.utils.render.DrawContextUtils.drawText
import cop.utils.render.DrawContextUtils.rect
import cop.utils.skyblock.ItemUtils.loreString
import cop.utils.ui.cursor
import cop.utils.ui.delegateClick
import cop.utils.ui.inHudEditor
import cop.utils.ui.rendering.NVGRenderer.minecraftFont
import kotlin.math.pow

object Inventory : Module(
    "Inventory",
    desc = "Various quality of life features for inventory GUIs"
) {

    private val bgColour by colourPicker("Background colour", Colour.GREY.withAlpha(100), allowAlpha = true)
    private val outlineColour by colourPicker("Outline colour", Colour.GREY.withAlpha(150), allowAlpha = true)
    // Match outlines: a name-match uses one colour, a lore-only match another, so
    // you can tell at a glance whether the item itself or just its lore matched.
    private val nameColour by colourPicker("Name match", Colour.RGB(255, 220, 40, 1f), allowAlpha = true,
        desc = "Outline colour for items whose name matches the search.")
    private val loreColour by colourPicker("Lore colour", Colour.MAGENTA.withAlpha(200), allowAlpha = true,
        desc = "Outline colour for items whose lore (but not name) matches the search.")
    private val borderWidth by slider("Border width", 2, 1, 4, 1,
        desc = "Outline thickness drawn around matching slots, in pixels.", unit = "px")
    private val tintFill by switch("Tint fill", true,
        desc = "Also tint the slot interior — peeks through transparent parts of the item icon.")

    // dump = move matching items from your inventory into the open chest.
    // withdraw = move matching items from the open chest into your inventory.
    private val dumpKey by keybind("Dump key",
        desc = "Move all matched items from your inventory into the open chest.")
    private val withdrawKey by keybind("Withdraw key",
        desc = "Move all matched items from the open chest into your inventory.")
    private val transferDelay by slider("Transfer delay", 3, 1, 10, 1,
        desc = "Ticks between each shift-click while dumping/withdrawing.", unit = "t")

    private val searchBar by textHud("Search bar", font = null, anchor = null) {
        block(
            constrain(w = 360.px, h = 40.px),
            colour = bgColour,
            10.radius()
        ) {
            outline(outlineColour, thickness = 2.px)

            val calcText = text(
                string = "",
                pos = at(x = 3.percent.alignOpposite),
                colour = colour
            ).toggle()

            val input = textInput(
                string = searchText,
                placeholder = "Search",
                colour = colour,
                placeHolderColour = colour { colour.rgb.multiply(0.8f) },
                caretColour = if (bgColour.toHSB().brightness < 0.6f) Colour.WHITE else Colour.BLACK,
                pos = at(x = 3.percent)
            ) {

                shadow = this@textHud.shadow

                onTextChanged { (string) ->
                    searchText = string

                    calculate(string)?.let { result ->
                        val str = result.toFixed(4).trimEnd('0').trimEnd('.')
                        calcText.string = "= $str"
                        maxWidth(93.percent - calcText.element.getTextWidth().px)
                        calcText.enabled = true
                    } ?: run {
                        calcText.enabled = false
                        maxWidth(93.percent)
                    }
                }

                onFocusChanged {
                    focused = !focused()
                }
            }

            if (!inHudEditor) {
                cursor(CursorShape.IBEAM)
                delegateClick(input)
            }
        }
    }.container().withSettings(::bgColour, ::outlineColour, ::nameColour, ::loreColour).setting()


    private val playerModel by switch("Player model")

    private val inventoryHud by resizableHud("Inventory", colour = Colour.RGB(139, 139, 139).withAlpha(155), outline = Colour.RGB(250, 250, 250).withAlpha(155)) {
        block(
            size(if (playerModel) 488.px else 400.px, 136.px),
            colour = colour,
            5.radius()
        ) {
            row(inset(4f)) {
                column(gap = 4.px) {
                    for (row in 0..2) {
                        row(gap = 4.px) {
                            repeat(9) { col ->
                                val slotIndex = 9 + (row * 9 + col)

                                outlineBlock(
                                    size(40.px, 40.px),
                                    colour = outline,
                                    thickness = thickness,
                                    radius = 5.radius()
                                ) {
                                    object : Element(size(40.px, 40.px)) {
                                        init { usingCtx = true }
                                        override fun drawCtx() {
                                            val stack = player.inventory.getItem(slotIndex)
                                            if (stack.isEmpty) return
                                            withScale {
                                                ctx.pose().scale(2f, 2f)
                                                ctx.renderItem(stack, 2, 2)
                                                if (stack.count > 1) {
                                                    val t = stack.count.toString()
                                                    ctx.drawText(t, 20 - t.width(), 20 - mc.font.lineHeight)
                                                }
                                            }
                                        }
                                    }.add()
                                }
                            }
                        }
                    }
                }

                if (playerModel) {
                    divider(4.px)
                    object : Element(size(Fill, Fill)) {
                        init { usingCtx = true }
                        override fun drawCtx() {
                            withScale {
                                ctx.drawEntity(mc.player as LivingEntity, 0, 0, width.toInt(), height.toInt(), 30f, yaw = -45f to 45f)
                            }
                        }
                    }.add()
                }
            }
        }
    }.withSettings(::playerModel).setting()

    private var searchText = ""
    private var focused = false
    private var highlightSlots = mutableListOf<HighlightSlot>()

    val equationMap: Map<String, (Double, Double) -> Double> = mapOf(
        "x" to Double::times,
        "*" to Double::times,
        "/" to Double::div,
        "+" to Double::plus,
        "-" to Double::minus,
        "%" to Double::rem,
        "^" to Double::pow
    )

    init {
        on<TickEvent.End> {
            if (mc.screen !is AbstractContainerScreen<*> || !searchBar.enabled || searchText.isEmpty()) return@on
            highlightSlots.clear()

            val queries = searchText.lowercase().split(",").map { it.trim() }
            player.containerMenu.items.forEachIndexed { i, stack ->
                val name = stack.customName?.string?.lowercase()?.trim().orEmpty()
                val lore = stack.loreString?.lowercase()?.trim().orEmpty()
                if (name.isEmpty() && lore.isEmpty()) return@forEachIndexed
                queries.forEach {
                    matchType(name, lore, it)?.let { lore ->
                        highlightSlots.add(HighlightSlot(i, if (lore) loreColour else nameColour))
                    }
                }
            }
        }

        on<GuiEvent.Slot.Draw> {
            val colour = highlightSlots.find { it.slot == slot.index }?.colour?.rgb ?: return@on
            val bw = borderWidth
            // Draw the outline OUTSIDE the 16x16 item area (on the slot frame)
            // so the item icon can't cover it. ctx.rect(x, y, w, h, colour) takes
            // (x, y, width, height) — used as 4 thin rectangles to form a ring.
            ctx.rect(slot.x - bw, slot.y - bw, 16 + bw * 2, bw, colour)            // top
            ctx.rect(slot.x - bw, slot.y + 16, 16 + bw * 2, bw, colour)            // bottom
            ctx.rect(slot.x - bw, slot.y, bw, 16, colour)                          // left
            ctx.rect(slot.x + 16, slot.y, bw, 16, colour)                          // right
            if (tintFill) {
                // Faint interior tint — only peeks through transparent edges of
                // the item icon, so it's an extra contrast cue on busy items.
                ctx.rect(slot.x, slot.y, 16, 16, (colour and 0x00FFFFFF) or 0x40000000)
            }
        }

        on<GuiEvent.Key.Press> (Priority.LOW) {
            if (focused) cancel()
        }

        on<KeyEvent.Press> {
            if (mc.screen !is AbstractContainerScreen<*>) return@on
            if (highlightSlots.isEmpty()) return@on
            when (key) {
                dumpKey.key -> { transferMatching(toChest = true); cancel() }
                withdrawKey.key -> { transferMatching(toChest = false); cancel() }
            }
        }
    }

    /** Shift-click every currently-matched slot one-by-one (with a small delay
     *  between clicks so Hypixel can keep up) in the direction that moves the
     *  item across the chest/inventory boundary.
     *
     *  @param toChest true  -> dump: matches in the player inventory get shift-
     *                          clicked into the chest (iterate top-down).
     *                 false -> withdraw: matches in the chest get shift-clicked
     *                          into the inventory (iterate bottom-up). */
    private fun transferMatching(toChest: Boolean) {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return
        val menu = screen.menu
        val containerId = menu.containerId
        val totalSlots = menu.slots.size
        val chestEnd = totalSlots - 36  // first 'chestEnd' slots are the chest, the rest is the player inv

        val toMove = highlightSlots.map { it.slot }
            .filter { if (toChest) it >= chestEnd else it < chestEnd }
            .let { if (toChest) it.sortedDescending() else it.sorted() }
            .toList()
        if (toMove.isEmpty()) return

        // Snapshot the screen title so we abort if the player swaps to a
        // different container mid-chain.
        val title = screen.title.string

        fun step(i: Int) {
            if (i >= toMove.size) return
            val s = mc.screen as? AbstractContainerScreen<*> ?: return
            if (s.menu.containerId != containerId || s.title.string != title) return
            mc.player?.clickSlot(toMove[i], containerId, button = 0, shift = true)
            scheduleTask(transferDelay) { step(i + 1) }
        }
        step(0)
    }

    private fun matchType(name: String, lore: String, string: String) = when {
        name.isEmpty() || lore.isEmpty() || string.isEmpty() -> null
        name.contains(string, true) -> false
        lore.contains(string, true) -> true
        else -> null
    }

    private fun calculate(string: String): Double? {
        var s = string.replace(",", "")

        Regex("""\(([^()]+)\)""").find(s)?.let {
            return calculate(s.replaceRange(it.range, calculate(it.groupValues[1]).toString()))
        }


        listOf("\\^", "[*x/%]", "[+\\-]").forEach { operators ->
            val eqRegex = Regex("""([\d.]+)\s*($operators)\s*([\d.]+)""")

            var match = eqRegex.find(s)
            while (match != null) {
                val (n1, op, n2) = match.destructured
                val result = equationMap.getValue(op)(n1.toDouble(), n2.toDouble())
                s = s.replaceRange(match.range, result.toString())
                match = eqRegex.find(s)
            }
        }

        return s.toDoubleOrNull()
    }


    data class HighlightSlot(var slot: Int, val colour: Colour)
}