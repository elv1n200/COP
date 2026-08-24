package cop.utils.ui.hud

import cop.CopMod.mc
import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Positions
import cop.api.abobaui.constraints.impl.measurements.Animatable
import cop.api.abobaui.constraints.impl.measurements.Pixel
import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.Bounding
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.Element
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.Layout
import cop.api.abobaui.elements.Layout.Companion.divider
import cop.api.abobaui.elements.Layout.Companion.section
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.Popup
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.abobaui.elements.impl.popup
import cop.api.animations.Animation
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.GuiEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus
import cop.api.input.CatKeys
import cop.api.input.CursorShape
import cop.config.Config
import cop.module.settings.UIComponent
import cop.module.settings.impl.ColourPickerComponent
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.toFixed
import cop.utils.ThemeManager.theme
import cop.utils.ui.HUD_EDITOR_TITLE
import cop.utils.ui.cursor
import cop.utils.ui.popupX
import cop.utils.ui.popupY
import cop.utils.ui.rendering.NVGRenderer
import cop.utils.ui.screens.UIContainer
import cop.utils.ui.screens.UIOverlay
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import cop.module.impl.misc.Test
import cop.module.impl.render.ClickGui.clickGui
import cop.utils.ui.screens.UIScreen.Companion.open
import kotlin.collections.forEach
import kotlin.math.abs

object HudManager { // todo add hud grouping
    val huds = arrayListOf<Hud>()
    var stupid = false

    private var overlay: UIOverlay? = null

    private var selected: Popup? = null
    private var lineX: Float = -1f
    private var lineY: Float = -1f
    private var returnToControlCenter = false
    private const val SNAP_THRESHOLD = 5f

    init {
        EventBus.on<GuiEvent.Open.Post> {
            if (screen !is AbstractContainerScreen<*>) return@on

            UIContainer(aloba {
                huds.forEach { hud ->
                    if (!hud.inContainer) return@forEach
                    val element = hud.Element()
                    element.add()
                    Hud.Scope(element, preview = false).apply { hud.builder(this) }
                }
            }, cancelling = false).apply { open() }
        }

        EventBus.on<WorldEvent.Load.Start> { // fixes visibleIf {} shit. todo find an actual fix
            reinit()
        }
    }

    fun init() {
        stupid = true
        if (!mc.isSameThread) {
            mc.execute { init() }
            return
        }

        overlay = UIOverlay(aloba {
            huds.forEach { hud ->
                if (hud.inContainer) return@forEach
                val element = hud.Element()
                element.add()
                Hud.Scope(element, preview = false).apply { hud.builder(this) }
            }
        }).apply { open() }
    }

    fun reinit(immediately: Boolean = true) {
        overlay?.close()
        if (immediately) mc.execute { init() }
        else scheduleTask { mc.execute { init() } }
    }

    var hudSettings: Popup? = null

    fun openEditor(fromMain: Boolean = false) {
        returnToControlCenter = false
        open(
            editor(fromMain),
            onUserClose = {
                if (fromMain) returnToControlCenter = true
            },
        )
    }

    fun editor(fromMain: Boolean = false) = aboba(HUD_EDITOR_TITLE) {

        ui.debug = Test.uiDebug
        var hoverInfo: Popup? = null

        object : Element(copies()) {
            override fun drawNvg() {
                if (lineX >= 0f) NVGRenderer.line(lineX, 0f, lineX, ui.main.height, 1.5f, theme.primary.withAlpha(0.85f).rgb)
                if (lineY >= 0f) NVGRenderer.line(0f, lineY, ui.main.width, lineY, 1.5f, theme.primary.withAlpha(0.85f).rgb)
            }
        }.add()

        onAdd {
            overlay?.close()
        }

        onRemove {
            val shouldReturnToControlCenter = fromMain && returnToControlCenter
            returnToControlCenter = false
            selected?.closePopup()
            selected = null
            hudSettings?.closePopup()
            hudSettings = null
            lineX = -1f
            lineY = -1f
            scheduleTask { Config.save() }
            reinit(immediately = false)
            if (shouldReturnToControlCenter) open(clickGui)
        }

        onClick {
            selected?.closePopup()
            selected = null
            ui.unfocus()
        }

        // Keep movable previews in their own z-layer. They can reorder inside
        // this group without ever covering the editor toolbar below.
        val hudLayer = group(copies()) {
            dragSelection()
        }

        val toolbar = block(
            constrain(
                x = Centre,
                y = 14.px,
                w = 88.percent.coerceAtMost(780.px),
                h = 54.px
            ),
            colour = theme.surfaceContainerHigh.withAlpha(0.96f),
            radius = 12.radius()
        ) {
            dropShadow(
                colour = Colour.BLACK.withAlpha(0.32f),
                blur = 12f,
                spread = 4f,
                radius = 12.radius()
            )
            outline(theme.outlineVariant, thickness = 1.px)
            onClick { true }

            block(
                constrain(x = 12.px, w = 30.px, h = 30.px),
                colour = theme.primary,
                radius = 9.radius()
            ) {
                text("C", size = 16.px, colour = theme.onPrimary)
            }
            text(
                string = "HUD STUDIO",
                pos = at(x = 52.px, y = 10.px),
                size = 15.px,
                colour = theme.onSurface
            )
            text(
                string = "Drag to move  ·  Right-click settings  ·  Wheel scales  ·  Arrows nudge",
                pos = at(x = 52.px, y = 32.px),
                size = 9.px,
                colour = theme.onSurfaceVariant
            )
            block(
                constrain(x = 12.px.alignOpposite, w = 66.px, h = 30.px),
                colour = theme.primaryContainer,
                radius = 9.radius()
            ) {
                tonalHover(theme.onPrimaryContainer)
                cursor(CursorShape.HAND)
                text("Done", size = 12.px, colour = theme.onPrimaryContainer)
                onClick {
                    if (fromMain) returnToControlCenter = true
                    mc.setScreen(null)
                    true
                }
            }
        }

        toolbar.operation {
            // Settings/selection popups are attached to the UI root. Keep the
            // fixed toolbar above those dynamic elements as well as previews.
            if (ui.main.children?.lastOrNull() !== toolbar.element) {
                toolbar.element.moveToTop()
            }
            false
        }

        huds.forEach { hud ->
            val element = hud.Element()
            hudLayer.element.addElement(element)
            element.init()

            Hud.Scope(element, preview = true).apply {
                hud.builder(this)

                var dragging = false
                var clickedX = 0f
                var clickedY = 0f

                onClick(button = 0) {
                    dragging = true

                    if (element.constraints.x !is Pixel) {
                        element.constraints.x = element.x.px
                        element.constraints.y = element.y.px
                    }

                    clickedX = ui.mx - element.x
                    clickedY = ui.my - element.y

                    element.moveToTop()

                    ui.focus(element)
                    true
                }

                onRelease {
                    if (dragging) {
                        // Constraints are updated during mouse movement; apply
                        // them before converting the rendered position back to
                        // the persisted percentage/anchor representation.
                        ui.main.positionChildren()
                        element.savePosition(ui.main.width, ui.main.height)
                        Config.requestSave()
                    }
                    dragging = false
                }

                onMouseMove {
                    if (!dragging) return@onMouseMove false
                    val newX = ui.mx - clickedX
                    val newY = ui.my - clickedY

                    val maxX = maxOf(0f, ui.main.width - element.screenWidth())
                    val maxY = maxOf(0f, ui.main.height - element.screenHeight())

                    element.constraints.x.pixels = newX.coerceIn(0f, maxX)
                    element.constraints.y.pixels = newY.coerceIn(0f, maxY)

                    element.redraw()
                    true
                }

//                draggable(moves = element)

                onRemove {
                    hud.savePosition(element, ui.main.width, ui.main.height)
                }

                onFocus {
                    hoverInfo = popup(at(popupX(), popupY()), smooth = false) {
                        block(
                            bounds(padding = 5.px),
                            colour = theme.surfaceContainerHighest,
                            5.radius()
                        ) {
                            outline(theme.outline, thickness = 2.px)
                            column {
                                textSupplied(
                                    supplier = {
                                        var str = "x: ${element.x.toInt()}, y: ${element.y.toInt()}"
                                        if (element.scaleX != 1.0f) str += ", scale: ${element.scaleX.toFixed(1)}"
                                        str
                                    },
                                    colour = theme.onSurfaceVariant,
                                    size = theme.textSize
                                )
                            }
                        }
                    }
                }

                onFocusLost {
                    hoverInfo?.closePopup()
                    hoverInfo = null
                }

                onKeyPressed { (key, mods) ->
                    val step = if (mods.isShiftDown) 10 else 1
                    val (x, y) = when (key) {
                        CatKeys.KEY_RIGHT -> step to 0
                        CatKeys.KEY_LEFT -> -step to 0
                        CatKeys.KEY_UP -> 0 to -step
                        CatKeys.KEY_DOWN -> 0 to step
                        else -> return@onKeyPressed false
                    }
                    if (element.constraints.x !is Pixel) {
                        element.constraints.x = element.x.px
                        element.constraints.y = element.y.px
                    }

                    val newX = (element.constraints.x.pixels + x).coerceIn(0f, ui.main.width - element.screenWidth())
                    val newY = (element.constraints.y.pixels + y).coerceIn(0f, ui.main.height - element.screenHeight())

                    element.constraints.x.pixels = newX
                    element.constraints.y.pixels = newY
                    ui.main.positionChildren()
                    element.savePosition(ui.main.width, ui.main.height)
                    Config.requestSave()
                    element.redraw()
                    true
                }

                onClick(button = 1) {
                    ui.focus(element)
                    hoverInfo?.closePopup()
                    hudSettings?.closePopup()
                    element.moveToTop()
                    hudSettings = settings(at(popupX(), popupY()), hud, { hudSettings = null }, element) {
                        hud.savePosition(element, ui.main.width, ui.main.height)
                        rebuildHuds()
                    }
                    true
                }

                onScroll { (amount) ->
                    val newValue = hud.scale.value + (hud.scale.incrementD * amount)
                    hud.scale.set(newValue)
                    element.scaleTransformation = hud.scale.value
                    Config.requestSave()
                }
            }
        }
    }

    private fun ElementScope<*>.selectHuds(selectedHuds: List<Hud.Element>): Popup {
        redraw()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = 0f
        var maxY = 0f

        selectedHuds.forEach {
            if (it.constraints.x !is Pixel) {
                it.constraints.x = it.x.px
                it.constraints.y = it.y.px
            }
            val right = it.x + it.screenWidth()
            val bottom = it.y + it.screenHeight()
            minX = minOf(minX, it.x)
            minY = minOf(minY, it.y)
            maxX = maxOf(maxX, right)
            maxY = maxOf(maxY, bottom)
        }

        val px = minX.px
        val py = minY.px
        val width = (maxX - minX).px
        val height = (maxY - minY).px


        return popup(constraints = constrain(px, py, width, height), smooth = true) {
            outlineBlock(
                constraints = copies(),
                colour = theme.primary,
                thickness = 1.5.px,
                4.radius()
            )

            var mouseDown = false
            var offsetX = ui.mx - px.pixels
            var offsetY = ui.my - py.pixels

            onClick {
                mouseDown = true
                offsetX = ui.mx - px.pixels
                offsetY = ui.my - py.pixels
                true
            }

            onRelease {
                if (mouseDown) {
                    ui.main.positionChildren()
                    selectedHuds.forEach { hud ->
                        hud.savePosition(ui.main.width, ui.main.height)
                    }
                    Config.requestSave()
                }
                mouseDown = false
                lineX = -1f
                lineY = -1f
            }

            onMouseMove {
                if (!mouseDown) return@onMouseMove false

                val parent = element.parent ?: return@onMouseMove false
                val centreX = parent.width / 2
                val centreY = parent.height / 2

                var newX = (ui.mx - offsetX).coerceIn(0f, parent.width - element.screenWidth())
                var newY = (ui.my - offsetY).coerceIn(0f, parent.height - element.screenHeight())

                lineX = -1f
                lineY = -1f

                when {
                    abs(newX - centreX) <= SNAP_THRESHOLD -> { // left - centre
                        newX = centreX
                        lineX = centreX
                    }
                    abs(newX + element.screenWidth() / 2 - centreX) <= SNAP_THRESHOLD -> { // centre - centre
                        newX = centreX - element.screenWidth() / 2
                        lineX = centreX
                    }
                    abs(newX + element.screenWidth() - centreX) <= SNAP_THRESHOLD -> { // right - centre
                        newX = centreX - element.screenWidth()
                        lineX = centreX
                    }
                }

                when {
                    abs(newY - centreY) <= SNAP_THRESHOLD -> { // top - centre
                        newY = centreY
                        lineY = centreY
                    }
                    abs(newY + element.screenHeight() / 2 - centreY) <= SNAP_THRESHOLD -> { // centre - centre
                        newY = centreY - element.screenHeight() / 2
                        lineY = centreY
                    }
                    abs(newY + element.screenHeight() - centreY) <= SNAP_THRESHOLD -> { // bot - centre
                        newY = centreY - element.screenHeight()
                        lineY = centreY
                    }
                }


                parent.children?.forEach { other ->
                    if (other !is Hud.Element || other in selectedHuds || !other.enabled) return@forEach

                    val otherRight = other.x + other.screenWidth()
                    val thisRight = newX + element.screenWidth()
                    val otherBottom = other.y + other.screenHeight()
                    val thisBot = newY + element.screenHeight()

                    when {
                        abs(thisBot - otherBottom) <= SNAP_THRESHOLD -> { // bot - bot
                            newY = otherBottom - element.screenHeight()
                            lineY = otherBottom
                        }
                        abs(newY - other.y) <= SNAP_THRESHOLD -> { // top - top
                            newY = other.y
                            lineY = newY
                        }
                        abs(thisBot - other.y) <= SNAP_THRESHOLD -> { // bot - top
                            newY = other.y - element.screenHeight()
                            lineY = other.y
                        }
                        abs(newY - otherBottom) <= SNAP_THRESHOLD -> { // top - bot
                            newY = otherBottom
                            lineY = newY
                        }
                    }

                    when {
                        abs(thisRight - otherRight) <= SNAP_THRESHOLD -> { // right - right
                            newX = otherRight - element.screenWidth()
                            lineX = otherRight
                        }
                        abs(newX - other.x) <= SNAP_THRESHOLD -> { // left - left
                            newX = other.x
                            lineX = newX
                        }
                        abs(thisRight - other.x) <= SNAP_THRESHOLD -> { // right left
                            newX = other.x - element.screenWidth()
                            lineX = other.x
                        }
                        abs(newX - otherRight) <= SNAP_THRESHOLD -> { // left - right
                            newX = otherRight
                            lineX = newX
                        }
                    }
                }

                val dX = newX - px.pixels
                val dY = newY - py.pixels

                if (dX != 0f || dY != 0f) {
                    px.pixels = newX
                    py.pixels = newY
                    selectedHuds.forEach {
                        it.constraints.x.pixels += dX
                        it.constraints.y.pixels += dY
                    }
                    redraw()
                }
                true
            }
        }
    }

    private fun ElementScope<*>.dragSelection() {
        val selection = block(
            constraints = constrain(0.px, 0.px, 0.px, 0.px),
            colour = theme.primary.withAlpha(0.18f),
            4.radius()
        ).outline(theme.primary, thickness = 1.px).toggle()

        var clickedX = 0f
        var clickedY = 0f

        onClick {
            selection.toggle()
            selection.element.moveToTop()
            clickedX = ui.mx
            clickedY = ui.my

            selection.element.constraints.apply {
                x.pixels = clickedX
                y.pixels = clickedY
                width.pixels = 0f
                height.pixels = 0f
            }
            selection.redraw()
            true
        }

        onRelease {
            if (selection.enabled) {
                val selectedHuds = element.children
                    ?.filterIsInstance<Hud.Element>()
                    ?.filter { it.enabled && it.intersects(selection.element) }
                    ?.toList()
                    .orEmpty()

                selected?.closePopup()
                selected = if (selectedHuds.isEmpty()) null else selectHuds(selectedHuds)

                selection.toggle()
                selection.redraw()
            } else selected?.takeIf { !it.element.isInside(ui.mx, ui.my) }?.closePopup()?.also { selected = null }
        }

        onMouseMove {
            if (!selection.enabled) return@onMouseMove false

            val newW = ui.mx - clickedX
            val newH = ui.my - clickedY

            selection.element.constraints.apply {
                x.pixels = if (newW < 0) clickedX + newW else clickedX
                y.pixels = if (newH < 0) clickedY + newH else clickedY
                width.pixels = abs(newW)
                height.pixels = abs(newH)
                redraw()
            }
            true
        }
    }

    fun ElementScope<*>.settings(pos: Positions, hud: Hud, onClose: () -> Unit, hudElement: Element? = null, onValue: () -> Unit = {}) = popup(copies(), smooth = false) {
        onClick {
            closePopup()
            onClose()
        }

        group(
            constrain(
                x = pos.x, y = pos.y,
                w = 260.px, h = GroupHeight
            )
        ) {

            column {
                onClick {
                    true
                }

                dropShadow(
                    colour = Colour.BLACK.withAlpha(0.25f),
                    blur = 10f,
                    spread = 5f,
                    radius = 6.radius()
                )

                block(
                    size(260.px, 35.px),
                    colour = theme.surfaceContainerLow,
                    radius(tl = 6, tr = 6)
                ) {
                    text(
                        string = hud.name,
                        size = 70.percent,
                        colour = theme.onSurface
                    )
                }

                column(size(w = Copying), gap = 5.px) {
                    block(
                        copies(),
                        colour = theme.surface.withAlpha(0.7f)
                    )
                    column(constrain(x = 5.px, w = Copying - 10.px, h = ColumnHeight), gap = 5.px) { // fixme
                        divider(5.px)
                        if (hud.settings.size == 4) {
                            block(
                                size(Copying, 62.px),
                                colour = theme.surfaceContainer,
                                radius = 6.radius()
                            ) {
                                text(
                                    string = "No extra settings",
                                    pos = at(y = 16.px),
                                    size = 14.px,
                                    colour = theme.onSurface
                                )
                                text(
                                    string = "Use the mouse wheel over this HUD to change its scale.",
                                    pos = at(y = 39.px),
                                    size = 9.px,
                                    colour = theme.onSurfaceVariant
                                )
                            }
                        }
                        hud.settings.forEach { setting ->
                            if (setting !is UIComponent) return@forEach
                            if (setting.parent != null && setting.parent as UIComponent in hud.settings) return@forEach

                            var wasRainbow = (setting as? ColourPickerComponent)?.rainbow ?: false

                            val dummy = hud.settings.first() as UIComponent<*>
                            val asSub = setting in dummy.children && setting.children.isNotEmpty() && !setting.forceParent
                            setting.render(this, asSub).onEvent(setting.valueUpdated) {
                                val cs = setting as? ColourPickerComponent
                                if (cs?.rainbow != wasRainbow) onValue()
                                wasRainbow = cs?.rainbow ?: false
                                true
                            }
                        }
                    }
                    section(size = 40.px) {
                        val thickness = Animatable(from = 2.px, to = 3.px)
                        val outlineCol = Colour.Animated(from = theme.outline, to = theme.primary)
                        block(
                            size(w = 90.percent, h = 70.percent),
                            colour = theme.surfaceContainerHigh,
                            5.radius()
                        ) {
                            outline(outlineCol, thickness = thickness)

                            text(
                                string = "Reset",
                                size = 70.percent,
                                colour = theme.onSurface
                            )

                            onMouseEnterExit {
                                outlineCol.animate(0.2.seconds, Animation.Style.EaseInOutQuint)
                            }

                            onClick(button = 0) {
                                hud.settings.drop(if (hudElement == null) 0 else 3).forEach { // schizo, idc
                                    it.reset()
                                }
                                thickness.animate(0.25.seconds, style = Animation.Style.EaseInOutQuint)?.onFinish {
                                    scheduleTask { thickness.animate(0.25.seconds, style = Animation.Style.EaseInOutQuint) }
                                }

//                                hudElement?.let { element ->
//                                    element.constraints.x.pixels = 0.0f
//                                    element.constraints.y.pixels = 0.0f
//                                }
                                true
                            }
                        }
                    }
                }


                block(
                    size(260.px, 10.px),
                    colour = theme.surfaceContainerLow,
                    radius(bl = 6, br = 6)
                )
            }
        }
    }
}

// temporary fix for "wobble" on visibility setting change.... I am an idiot sandwich and idk how to fix Bounding...
private val ColumnHeight = object : Constraint.Size {
    override fun calculateSize(element: Element, horizontal: Boolean): Float {
        if (horizontal) return Bounding.calculateSize(element, true)

        val layout = element as? Layout ?: return Bounding.calculateSize(element, false)

        val gap = layout.gap?.calculateSize(layout, false) ?: 0f
        var totalHeight = 0f

        layout.children?.forEach { child ->
            if (child.enabled) {
                val childH = child.constraints.height.calculateSize(child, false)
                val gap = if (child is Layout.Divider) 0f else gap
                val actualGap = if (childH < gap) childH else gap

                totalHeight += childH + actualGap
            }
        }
        return totalHeight
    }

    override fun reliesOnChildren() = true
}

val GroupHeight = object : Constraint.Size {
    override fun calculateSize(element: Element, horizontal: Boolean): Float {
        if (horizontal) return Bounding.calculateSize(element, true)

        var value = 0f
        element.children?.forEach {
            if (it.enabled) {
                val new = it.constraints.height.calculateSize(it, false)
                if (new > value) value = new
            }
        }
        return value
    }

    override fun reliesOnChildren() = true
}
