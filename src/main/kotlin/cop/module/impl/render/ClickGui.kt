@file:Suppress("UNUSED")

package cop.module.impl.render

import net.minecraft.Util
import cop.annotations.AlwaysActive
import cop.api.ServerInfo.averagePing
import cop.api.ServerInfo.averageTps
import cop.api.ServerInfo.currentPing
import cop.api.ServerInfo.currentTps
import cop.api.ServerInfo.medianPing
import cop.api.abobaui.AbobaUI
import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.Bounding
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.constraints.impl.size.Fill
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.Element
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.Layout.Companion.divider
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.Popup
import cop.api.abobaui.elements.impl.Scrollable
import cop.api.abobaui.elements.impl.Scrollable.Companion.scroll
import cop.api.abobaui.elements.impl.Scrollable.Companion.scrollToTop
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.abobaui.elements.impl.TextInput.Companion.maxWidth
import cop.api.abobaui.elements.impl.TextInput.Companion.onTextChanged
import cop.api.abobaui.elements.impl.layout.Column
import cop.api.abobaui.elements.impl.popup
import cop.api.colour.Colour
import cop.api.colour.colour
import cop.api.colour.withAlpha
import cop.api.input.CatKeys
import cop.api.input.CursorShape
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Floor
import cop.config.Config
import cop.module.Category
import cop.module.Module
import cop.module.ModuleManager.modules
import cop.module.impl.misc.Test
import cop.module.settings.Setting.Companion.json
import cop.module.settings.UIComponent
import cop.module.settings.UIComponent.Companion.childOf
import cop.module.settings.impl.SelectorComponent
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.capitaliseFirst
import cop.utils.StringUtils.percentColour
import cop.utils.StringUtils.toFixed
import cop.utils.ThemeManager.theme
import cop.utils.WorldUtils.day
import cop.utils.ui.cursor
import cop.utils.ui.elements.themedInput
import cop.utils.ui.hud.Hud
import cop.utils.ui.hud.HudManager
import cop.utils.ui.onHover
import cop.utils.ui.rendering.NVGRenderer
import cop.utils.ui.rendering.NVGRenderer.defaultFont
import cop.utils.ui.screens.UIScreen.Companion.open
import cop.utils.ui.textPair
import java.net.URI

@AlwaysActive
object ClickGui : Module(
    "Click GUI",
    key = CatKeys.KEY_RIGHT_SHIFT
) {
    val forceSkyblock by switch("Force skyblock")
    val forceDungeons by switch("Force dungeon").onValueChanged { old, new -> if (new) Dungeon.setFloor(dungeonFloor.selected) }
    val dungeonFloor: SelectorComponent<Floor> by selector("Floor", Floor.F7).childOf(::forceDungeons).onValueChanged { old, new ->
        if (forceDungeons) Dungeon.setFloor(new.selected)
    }

    val selectedTheme by selector("Theme", "Light", arrayListOf("Light", "Dark", "Onyx")).onValueChanged { _, _ ->
        reopen()
    }.open()

    val seedColour by colourPicker("Colour", Colour.RGB(255, 204, 134)).json("Theme seed").childOf(::selectedTheme).asParent()
    val moduleSorting by selector("Module sorting", ModuleSorting.Default).childOf(::selectedTheme).onValueChanged { _, _ -> reopen() }

    var rainbowSpeed by slider("Rainbow colour speed", 1.0f, 0.05f, 5.0f, 0.05f)
    
    private val prefixDropdown by text("Prefix settings")
    val prefixText by textInput("Prefix", "cop").childOf(::prefixDropdown)
    val prefixColour by colourPicker("Colour", Colour.GREEN).json("Prefix colour").childOf(::prefixDropdown)
    val bracketsColour by colourPicker("Brackets", Colour.WHITE).json("Brackets colour").childOf(::prefixDropdown)

    private val fpsHud by textHud("Fps display") {
        textPair(
            string = "Fps:",
            supplier = { mc.fps },
            labelColour = colour,
            shadow = shadow,
            font = font
        )
    }.setting()

    private val pingType by selector("Ping type", PingType.Average)
    private val pingHud by textHud("Ping display") {
        visibleIf { !mc.isSingleplayer }
        textPair(
            string = "Ping:",
            supplier = { (if (preview) 69.420 else pingType.selected.value()).formatPing },
            labelColour = colour,
            shadow = shadow,
            font = font
        )
    }.withSettings(::pingType).setting()

    private val tpsType by selector("Tps type", TpsType.Average)
    private val tpsHud by textHud("Tps display") {
        visibleIf { !mc.isSingleplayer }
        textPair(
            string = "Tps:",
            supplier = { if (preview) 17.56f.formatTps(2) else tpsType.selected.value().formatTps(2) },
            labelColour = colour,
            shadow = shadow,
            font = font
        )
    }.withSettings(::tpsType).setting()

    private val dayHud by textHud("Day display") {
        textPair(
            string = "Day:",
            supplier = { mc.level?.day },
            labelColour = colour,
            shadow = shadow,
            font = font
        )
    }.setting()

    private var currentPet by textInput("Current pet", "").hide() // just for cfg

    override fun onKeybind() {
        open(clickGui)
    }

    init {
        command.sub("tps") {
            modMessage("Tps: ${currentTps.formatTps()}&r, Average: ${averageTps.formatTps(2)}")
        }.description("Shows tps.")

        command.sub("ping") {
            modMessage("Ping: ${currentPing.formatPing}&r, Average: ${averagePing.formatPing}")
        }.description("Shows ping.")
    }

    private const val MODULE_ROW_HEIGHT = 66.0f
    private var selectedCategory = Category.DUNGEON
    private var openingHudStudio = false

    var clickGui: AbobaUI.Instance = clickGui()
        private set

    private fun clickGui() = aboba("COP · Control Center") {
        val moduleScopes = arrayListOf<Pair<Module, ElementScope<*>>>()
        var searchQuery = ""
        var selectedModule = modulesFor(selectedCategory).firstOrNull() ?: modules.firstOrNull()
        lateinit var detailColumn: ElementScope<Column>
        lateinit var searchInput: ElementScope<cop.api.abobaui.elements.impl.TextInput>
        var moduleListScroll: ElementScope<Scrollable>? = null
        var detailScroll: ElementScope<Scrollable>? = null

        ui.debug = Test.uiDebug
        onRemove {
            Config.save()
            if (!openingHudStudio) HudManager.reinit(immediately = false)
            openingHudStudio = false
        }

        fun matches(module: Module): Boolean {
            val query = searchQuery.trim()
            return query.isEmpty() ||
                module.name.contains(query, true) ||
                module.desc.contains(query, true) ||
                module.category.displayName.contains(query, true) ||
                module.subCategory?.contains(query, true) == true ||
                module.settings.any { it.name.contains(query, true) || it.description.contains(query, true) }
        }

        fun visibleModules(): List<Module> = modules.filter {
            matches(it) && (searchQuery.isNotBlank() || it.category == selectedCategory)
        }

        fun select(module: Module?) {
            selectedModule = module
            detailColumn.renderModuleDetails(module)
            detailScroll?.scrollToTop()
            moduleScopes.forEach { it.second.redraw() }
        }

        fun refreshList() {
            val visible = visibleModules()
            moduleScopes.forEach { (module, scope) ->
                scope.enabled = module in visible
            }
            moduleListScroll?.scrollToTop()
            if (selectedModule !in visible) select(visible.firstOrNull())
            moduleScopes.firstOrNull { it.second.enabled }?.second?.element?.parent?.redraw()
        }

        block(copies(), theme.background.withAlpha(0.82f))

        block(
            constrain(
                x = Centre,
                y = Centre,
                w = 94.percent.coerceAtMost(1180.px),
                h = 90.percent.coerceAtMost(760.px)
            ),
            colour = theme.surfaceContainerLow,
            radius = 14.radius()
        ) {
            dropShadow(
                colour = Colour.BLACK.withAlpha(0.42f),
                blur = 18f,
                spread = 7f,
                radius = 14.radius()
            )
            outline(theme.outlineVariant, thickness = 1.px)

            column(copies()) {
                block(
                    size(Copying, 72.px),
                    colour = theme.surfaceContainer,
                    radius(tl = 14, tr = 14)
                ) {
                    block(
                        constrain(x = 20.px, y = 18.px, w = 36.px, h = 36.px),
                        colour = theme.primary,
                        radius = 10.radius()
                    ) {
                        text("C", colour = theme.onPrimary, size = 20.px)
                    }
                    text(
                        string = "COP",
                        pos = at(x = 68.px, y = 17.px),
                        size = 20.px,
                        colour = theme.onSurface
                    )
                    text(
                        string = "CONTROL CENTER",
                        pos = at(x = 68.px, y = 42.px),
                        size = 10.px,
                        colour = theme.primary
                    )

                    searchInput = themedInput(
                        pos = at(x = Centre, y = 18.px),
                        size = size(38.percent, 36.px),
                        colour = theme.surfaceContainerHighest,
                        radius = 9.radius()
                    ) {
                        textInput(
                            placeholder = "Search modules and settings...",
                            colour = theme.onSurface,
                            placeHolderColour = theme.onSurfaceVariant,
                            caretColour = theme.primary,
                            pos = at(x = 12.px),
                            size = 14.px
                        ) {
                            maxWidth(Fill - 24.px)
                            onTextChanged { (value) ->
                                searchQuery = value
                                refreshList()
                            }
                        }
                    }

                    block(
                        constrain(x = 18.px.alignOpposite, y = 18.px, w = 148.px, h = 36.px),
                        colour = theme.primaryContainer,
                        radius = 9.radius()
                    ) {
                        tonalHover(theme.onPrimaryContainer)
                        cursor(CursorShape.HAND)
                        image(
                            image = theme.moveImage,
                            constraints = constrain(x = 12.px, w = 16.px, h = 16.px),
                            colour = theme.onPrimaryContainer
                        )
                        text(
                            string = "HUD Studio",
                            pos = at(x = 38.px),
                            size = 14.px,
                            colour = theme.onPrimaryContainer
                        )
                        onClick {
                            openHudStudio()
                            true
                        }
                    }
                }

                row(size(Copying, Fill)) {
                    column(size(20.percent, Copying)) {
                        block(size(Copying, 58.px), theme.surfaceContainerLow) {
                            text(
                                string = "WORKSPACES",
                                pos = at(x = 18.px),
                                size = 11.px,
                                colour = theme.onSurfaceVariant
                            )
                        }
                        scrollable(size(Copying, Fill - 54.px)) {
                            column(size(Copying, Bounding), gap = 6.px) {
                                divider(6.px)
                                Category.entries.forEach { category ->
                                    categoryNavigation(category) {
                                        selectedCategory = category
                                        searchQuery = ""
                                        searchInput.element.text = ""
                                        refreshList()
                                    }
                                }
                                divider(6.px)
                            }
                            onScroll { (amount) -> scroll(amount * -46f) }
                        }
                        block(size(Copying, 54.px), theme.surfaceContainerLow) {
                            tonalHover()
                            cursor(CursorShape.HAND)
                            textSupplied(
                                supplier = {
                                    val active = modules.count { it.enabled || it.alwaysActive }
                                    "$active active modules"
                                },
                                pos = at(x = 18.px),
                                size = 12.px,
                                colour = theme.onSurfaceVariant
                            )
                            text(
                                string = "Discord ↗",
                                pos = at(x = 14.px.alignOpposite),
                                size = 11.px,
                                colour = theme.primary,
                            )
                            onClick {
                                Util.getPlatform().openUri(URI("https://discord.gg/Uc9gVncs6P"))
                                true
                            }
                        }
                    }

                    block(size(1.px, Copying), theme.outlineVariant)

                    column(size(36.percent, Copying)) {
                        block(size(Copying, 58.px), theme.surfaceContainerLow) {
                            textSupplied(
                                supplier = { if (searchQuery.isBlank()) selectedCategory.displayName else "Search results" },
                                pos = at(x = 18.px, y = 12.px),
                                size = 18.px,
                                colour = theme.onSurface
                            )
                            textSupplied(
                                supplier = {
                                    val count = modules.count { matches(it) && (searchQuery.isNotBlank() || it.category == selectedCategory) }
                                    "$count ${if (count == 1) "module" else "modules"}"
                                },
                                pos = at(x = 18.px, y = 37.px),
                                size = 11.px,
                                colour = theme.onSurfaceVariant
                            )
                        }
                        moduleListScroll = scrollable(size(Copying, Fill)) {
                            column(
                                constrain(x = 12.px, w = Copying - 24.px, h = Bounding),
                                gap = 8.px
                            ) {
                                divider(4.px)
                                Category.entries.forEach { category ->
                                    modulesFor(category).forEach { module ->
                                        val scope = moduleRow(module, { selectedModule == module }) { select(it) }
                                        moduleScopes += module to scope
                                    }
                                }
                                val emptyState = block(
                                    size(Copying, 86.px),
                                    theme.surfaceContainer,
                                    10.radius()
                                ) {
                                    text(
                                        string = "No modules found",
                                        pos = at(y = 25.px),
                                        size = 15.px,
                                        colour = theme.onSurface
                                    )
                                    text(
                                        string = "Try a shorter or different search.",
                                        pos = at(y = 51.px),
                                        size = 10.px,
                                        colour = theme.onSurfaceVariant
                                    )
                                }
                                emptyState.enabled = false
                                emptyState.operation {
                                    emptyState.enabled = modules.none {
                                        matches(it) && (searchQuery.isNotBlank() || it.category == selectedCategory)
                                    }
                                    false
                                }
                                divider(10.px)
                            }
                            onScroll { (amount) ->
                                scroll(amount * -(MODULE_ROW_HEIGHT + 14f))
                            }
                        }
                    }

                    block(size(1.px, Copying), theme.outlineVariant)

                    column(size(Fill, Copying)) {
                        block(size(Copying, 58.px), theme.surfaceContainerLow) {
                            text(
                                string = "DETAILS & SETTINGS",
                                pos = at(x = 18.px),
                                size = 11.px,
                                colour = theme.onSurfaceVariant
                            )
                        }
                        detailScroll = scrollable(size(Copying, Fill)) {
                            detailColumn = column(
                                constrain(x = 18.px, w = Copying - 36.px, h = Bounding),
                                gap = 12.px
                            ) {
                                renderModuleDetails(selectedModule)
                            }
                            onScroll { (amount) -> scroll(amount * -56f) }
                        }
                    }
                }
            }
        }

        refreshList()
    }

    /** Open the HUD editor as a child of the Control Center. */
    fun openHudStudio() = openHudStudio(reveal = null)

    fun openHudStudio(reveal: Hud?) {
        openingHudStudio = true
        HudManager.openEditor(fromMain = true, reveal = reveal)
    }

    private fun ElementScope<Column>.categoryNavigation(category: Category, onSelect: () -> Unit) {
        block(
            constrain(x = 10.px, w = Copying - 20.px, h = 46.px),
            colour = colour {
                if (selectedCategory == category) theme.primaryContainer.rgb
                else theme.surfaceContainerLow.rgb
            },
            radius = 9.radius()
        ) {
            tonalHover()
            cursor(CursorShape.HAND)

            block(
                constrain(x = 0.px, y = 8.px, w = 3.px, h = 30.px),
                colour = colour {
                    if (selectedCategory == category) theme.primary.rgb
                    else theme.primary.withAlpha(0f).rgb
                },
                radius = 2.radius()
            )
            text(
                string = category.displayName,
                pos = at(x = 14.px),
                size = 14.px,
                colour = colour {
                    if (selectedCategory == category) theme.onPrimaryContainer.rgb
                    else theme.onSurface.rgb
                }
            )
            block(
                constrain(x = 10.px.alignOpposite, w = 30.px, h = 22.px),
                colour = colour {
                    if (selectedCategory == category) theme.primary.withAlpha(0.18f).rgb
                    else theme.surfaceContainerHighest.rgb
                },
                radius = 11.radius()
            ) {
                text(
                    string = modules.count { it.category == category }.toString(),
                    size = 11.px,
                    colour = theme.onSurfaceVariant
                )
            }
            onClick {
                onSelect()
                redraw()
                true
            }
        }
    }

    private fun ElementScope<Column>.moduleRow(
        module: Module,
        isSelected: () -> Boolean,
        onSelect: (Module) -> Unit
    ): ElementScope<*> = block(
        size(Copying, MODULE_ROW_HEIGHT.px),
        colour = colour {
            if (isSelected()) theme.surfaceContainerHighest.rgb
            else theme.surfaceContainer.rgb
        },
        radius = 10.radius()
    ) {
        tonalHover()
        cursor(CursorShape.HAND)
        description(module.desc)

        block(
            constrain(x = 0.px, y = 10.px, w = 3.px, h = 46.px),
            colour = colour {
                if (isSelected()) theme.primary.rgb else theme.primary.withAlpha(0f).rgb
            },
            radius = 2.radius()
        )
        text(
            string = module.name,
            pos = at(x = 14.px, y = 11.px),
            size = 16.px,
            colour = theme.onSurface
        )

        val settingsCount = module.settings.count { it is UIComponent<*> && it.parent == null }
        val group = module.subCategory?.let(::subCategoryLabel) ?: module.category.displayName
        text(
            string = "$group  ·  $settingsCount ${if (settingsCount == 1) "setting" else "settings"}",
            pos = at(x = 14.px, y = 38.px),
            size = 10.px,
            colour = theme.onSurfaceVariant
        )

        if (module.tag == Tag.LEGACY) {
            image(
                image = theme.refreshImage,
                constraints = constrain(x = 60.px.alignOpposite, y = 13.px, w = 15.px, h = 15.px),
                colour = theme.onSurfaceVariant
            ).description(module.tag.desc)
        }

        if (module.alwaysActive) {
            block(
                constrain(x = 12.px.alignOpposite, y = 22.px, w = 42.px, h = 22.px),
                colour = theme.primary.withAlpha(0.14f),
                radius = 11.radius()
            ) {
                text("CORE", size = 9.px, colour = theme.primary)
            }
        } else {
            moduleSwitch(module, at(x = 12.px.alignOpposite, y = 23.px)) {
                redraw()
            }
        }

        onClick {
            onSelect(module)
            true
        }
    }

    private fun ElementScope<*>.moduleSwitch(
        module: Module,
        pos: cop.api.abobaui.constraints.Positions,
        onChanged: () -> Unit = {}
    ) {
        block(
            constrain(x = pos.x, y = pos.y, w = 38.px, h = 20.px),
            colour = colour {
                if (module.enabled) theme.primary.rgb else theme.surfaceVariant.rgb
            },
            radius = 10.radius()
        ) {
            var lastEnabled = module.enabled
            cursor(CursorShape.HAND)
            block(
                constrain(
                    x = object : Constraint.Position {
                        override fun calculatePos(element: Element, horizontal: Boolean): Float =
                            if (module.enabled) 20f else 3f
                    },
                    y = 3.px,
                    w = 14.px,
                    h = 14.px
                ),
                colour = colour {
                    if (module.enabled) theme.onPrimary.rgb else theme.onSurfaceVariant.rgb
                },
                radius = 7.radius()
            )
            operation {
                if (lastEnabled != module.enabled) {
                    lastEnabled = module.enabled
                    redraw()
                }
                false
            }
            onClick {
                module.toggle()
                redraw()
                onChanged()
                true
            }
        }
    }

    private fun ElementScope<Column>.renderModuleDetails(module: Module?) {
        element.removeAll()
        divider(4.px)

        if (module == null) {
            block(size(Copying, 100.px), theme.surfaceContainer, 10.radius()) {
                text("Select a module to view its settings", size = 14.px, colour = theme.onSurfaceVariant)
            }
            element.redraw()
            return
        }

        val descriptionLines = if (module.desc.isBlank()) {
            listOf("No description available.")
        } else {
            NVGRenderer.wrapText(module.desc, 330f, 12f, defaultFont).take(3)
        }
        val headerHeight = 96f + (descriptionLines.size - 1).coerceAtLeast(0) * 15f

        block(size(Copying, headerHeight.px), theme.surfaceContainer, 11.radius()) {
            outline(theme.outlineVariant, thickness = 1.px)
            text(
                string = buildString {
                    append(module.category.displayName.uppercase())
                    module.subCategory?.let { append("  /  ${subCategoryLabel(it).uppercase()}") }
                },
                pos = at(x = 16.px, y = 13.px),
                size = 9.px,
                colour = theme.primary
            )
            text(
                string = module.name,
                pos = at(x = 16.px, y = 34.px),
                size = 19.px,
                colour = theme.onSurface
            )
            descriptionLines.forEachIndexed { index, line ->
                text(
                    string = line,
                    pos = at(x = 16.px, y = (66 + index * 15).px),
                    size = 11.px,
                    colour = theme.onSurfaceVariant
                )
            }

            if (module.alwaysActive) {
                block(
                    constrain(x = 14.px.alignOpposite, y = 30.px, w = 58.px, h = 26.px),
                    theme.primary.withAlpha(0.14f),
                    13.radius()
                ) {
                    text("ALWAYS ON", size = 8.px, colour = theme.primary)
                }
            } else {
                moduleSwitch(module, at(x = 16.px.alignOpposite, y = 33.px)) {
                    element.redraw()
                }
            }
        }

        row(size(Copying, 24.px)) {
            text("SETTINGS", size = 11.px, colour = theme.onSurfaceVariant)
            block(size(Fill, 1.px), theme.outlineVariant)
        }

        val settings = module.settings.filterIsInstance<UIComponent<*>>().filter { it.parent == null }
        if (settings.isEmpty()) {
            block(size(Copying, 76.px), theme.surfaceContainer, 10.radius()) {
                text(
                    string = "No configurable settings",
                    pos = at(y = 22.px),
                    size = 14.px,
                    colour = theme.onSurface
                )
                text(
                    string = "This module only needs its on/off switch.",
                    pos = at(y = 46.px),
                    size = 10.px,
                    colour = theme.onSurfaceVariant
                )
            }
        } else {
            block(size(Copying, Bounding + 28.px), theme.surfaceContainer, 10.radius()) {
                column(
                    constrain(x = 14.px, y = 14.px, w = Copying - 28.px, h = Bounding),
                    gap = 10.px
                ) {
                    settings.forEach { setting -> setting.render(this) }
                }
            }
        }
        divider(12.px)
        element.redraw()
    }

    fun ElementScope<*>.description(desc: String) {
        if (desc.isEmpty()) return

        var popup: Popup? = null

        onHover(duration = 0.5.seconds) {
            if (popup != null) return@onHover

            val x =
                    if (element.x >= ui.main.width / 2)
                        (element.x - 8).px.alignRight
                    else
                        (element.x + element.width + 8).px

            val y = (element.y + 7 / 2).px

            val lines = NVGRenderer.wrapText(desc, 200f, 14f, defaultFont)

            popup = popup(constrain(x, y, Bounding, Bounding), smooth = false) {
                block(
                    constraints = bounds(padding = 5.px),
                    colour = theme.surfaceContainerHighest,
                    5.radius()
                ) {
                    outline(theme.outline, thickness = 2.px)
                    column {
                        lines.forEach {
                            text(
                                string = it,
                                size = theme.textSize - 2.px,
                                colour = theme.onSurface
                            )
                        }
                    }
                }
            }
        }

        onMouseExit {
            popup?.closePopup()
            popup = null
        }
    }

    private fun modulesFor(category: Category): List<Module> =
        modules.filter { it.category == category }.sortedWith(moduleSorting.selected.comparator)
    
    fun currentPet() = currentPet
    fun updateCurrentPet(str: String) {
        currentPet = str
    }

    private val Double.formatPing get() = "§${ // fixme
        when {
            this < 50.0 -> "a"// Colour.MINECRAFT_GREEN
            this < 100.0 -> "2"// Colour.MINECRAFT_DARK_GREEN
            this < 150.0 -> "e"// Colour.MINECRAFT_YELLOW
            this < 200.0 -> "6"// Colour.MINECRAFT_GOLD
            else -> "c"// Colour.MINECRAFT_RED
        }
    }%.2f §7ms".format(this)

    private fun Float.formatTps(decimals: Int = 0) = (this - 15).percentColour(5.0) + this.toFixed(decimals) // fixme

    fun reopen() {
        mc.setScreen(null)
        clickGui = clickGui()
        open(clickGui)
    }

    private enum class PingType(val value: () -> Double) {
        Average({ averagePing }),
        Current({ currentPing }),
        Median({ medianPing })
    }

    private enum class TpsType(val value: () -> Float) {
        Average({ averageTps }),
        Current({ currentTps })
    }

    enum class ModuleSorting(
        val comparator: Comparator<Module>
    ) {
        // Preserves the order modules are registered in `ModuleManager`, which
        // groups them by purpose (visuals → HUD info → solvers → QoL → cheaty
        // automation) instead of mixing helpers and bots randomly.
        Default(
            compareBy { modules.indexOf(it) }
        ),

        WidthDescending(
            compareByDescending<Module> { NVGRenderer.textWidth(it.name, 18f, defaultFont) }.thenBy { it.name.lowercase() }
        ),

        WidthAscending(
            compareBy<Module> { NVGRenderer.textWidth(it.name, 18f, defaultFont) }.thenBy { it.name.lowercase() }
        ),

        Alphabetical(
            compareBy<Module> { it.name.lowercase() }
        );
    }

    /** Stable, user-facing grouping for the ClickGUI. Package names stay terse
     *  for developers while the UI gets readable labels and a deliberate order. */
    private fun subCategoryLabel(sub: String): String = when (sub.lowercase()) {
        "worldrender" -> "World, Map & ESP"
        "huds" -> "HUD & Timers"
        "solvers" -> "Puzzle Solvers"
        "qol" -> "Quality of Life"
        "cheats" -> "Cheats & Automation"
        "automation" -> "General Automation"
        "combat" -> "Combat"
        "slayer" -> "Slayer Automation"
        "dojo" -> "Dojo Automation"
        "economy" -> "Economy Automation"
        "events" -> "Event Automation"
        "movement" -> "Movement"
        "navigation" -> "Navigation"
        "riftsolvers" -> "Rift Solvers"
        else -> sub.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }
    }

}
