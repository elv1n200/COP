package cop.module

import cop.CopMod
import cop.api.commands.CopCommand
import cop.api.events.PacketEvent
import cop.api.events.core.Event
import cop.api.events.core.EventBus
import cop.api.events.core.PacketScope
import cop.api.events.core.UnfilteredEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.IslandArea
import cop.api.skyblock.Location
import cop.module.settings.Setting
import cop.module.settings.impl.Keybinding
import cop.utils.ChatUtils.modMessage
import net.minecraft.network.protocol.Packet
import cop.annotations.AlwaysActive
import cop.module.settings.SettingsDSL
import cop.utils.ui.hud.HudDSL

abstract class Module(
    val name: String,
    val area: IslandArea? = null,
    val subarea: String? = null,
    val key: Int = CatKeys.KEY_NONE,
    @Transient val desc: String = "",
    toggled: Boolean = false,
    val tag: Tag = Tag.NONE,
    /** Explicit category override. COP's own modules derive it from their
     *  package (`cop.module.impl.<category>.…`), but addon modules live in a
     *  foreign package so package-derivation can't find one — they pass this
     *  (or default to [Category.ADDON]). */
    @Transient private val explicitCategory: Category? = null,
    /** Explicit sub-category override — same rationale as [explicitCategory].
     *  Lets an addon group its own modules under collapsible sub-headers. */
    @Transient private val explicitSubCategory: String? = null,
) : SettingsDSL(), HudDSL {
    constructor(
        name: String,
        area: Island,
        subarea: String? = null,
        key: Int = CatKeys.KEY_NONE,
        desc: String = "",
        toggled: Boolean = false,
        tag: Tag = Tag.NONE,
        category: Category? = null,
        subCategory: String? = null,
    ) : this(name, IslandArea.Base(area), subarea, key, desc, toggled, tag, category, subCategory)

    private var isRegistered = false

    val events = mutableListOf<EventBus.EventListener>()

    @Transient
    val category: Category = explicitCategory
        ?: getCategory(this::class.java)
        // COP's own uncategorised modules keep defaulting to RENDER for back-
        // compat; anything outside the `cop.` package tree is an addon module,
        // so default it into the ADDON column instead of misfiling under RENDER.
        ?: if (this::class.java.`package`?.name?.startsWith("cop.") == true) Category.RENDER else Category.ADDON

    /** Optional sub-grouping inside [category]. For COP's own modules it's
     *  derived from the package segment immediately after the category name
     *  (e.g. a module at `cop.module.impl.dungeon.cheats.AutoRCM` gets
     *  `subCategory = "cheats"`); addon modules pass it explicitly. Flat modules
     *  get null. Used by the ClickGUI to render collapsible sub-headers under
     *  crowded categories. */
    @Transient
    val subCategory: String? = explicitSubCategory ?: getSubCategory(this::class.java, category)

    val keybinding: Keybinding = this@Module.key.let { Keybinding(it).apply { onPress = ::onKeybind } }  // todo on press/release/hold

    var enabled: Boolean = toggled
        private set

    protected inline val mc get() = CopMod.mc
    inline val level get() = requireNotNull(CopMod.mc.level) { "tried to access level before world was loaded" } // should never be null in tick events
    inline val player get() = requireNotNull(CopMod.mc.player) { "tried to access player before it was loaded" } // should never be null in tick events

    protected inline val command get() = CopCommand.command

    @Transient
    val alwaysActive = this::class.java.isAnnotationPresent(AlwaysActive::class.java)

    val settings: ArrayList<Setting<*>> = ArrayList()

    open fun onEnable() {
        if (!alwaysActive) onToggle(true)
    }

    open fun onDisable() {
        if (!alwaysActive) onToggle(false)
    }

    open fun onKeybind() {
        if (mc.screen != null) return
        toggle()
        toggleMessage()
    }

    fun toggleMessage() {
        modMessage("$name ${if (enabled) "§aenabled" else "§cdisabled"}§r.", name.hashCode())
    }

    fun toggle() {
        enabled = !enabled
        if (enabled) onEnable()
        else onDisable()
    }

    fun addSettings(vararg setArray: Setting<*>) {
        setArray.forEach {
            settings.add(it)
        }
    }

    override fun <K : Setting<*>> register(setting: K): K {
        addSettings(setting)
        return setting
    }

    fun getSettingByName(name: String?): Setting<*>? {
        for (setting in settings) {
            if (setting.jsonName.equals(name, true) /*|| setting.name.equals(name, true)*/) {
                return setting
            }
        }
        return null
    }

    fun onToggle(state: Boolean) {
        val shouldBeRegistered = state // && inEnvironment()

        if (shouldBeRegistered && !isRegistered) {
            events.forEach { it.add() }
            isRegistered = true
        } else if (!shouldBeRegistered && isRegistered) {
            events.forEach { it.remove() }
            isRegistered = false
        }
    }

    fun inArea() = area?.inBase() ?: true

    fun inSubarea(): Boolean {
        if (subarea == null) return true

        return Location.subarea?.contains(subarea, true) == true
    }

    fun inEnvironment(): Boolean = area?.inArea() ?: true && inSubarea()

    protected inline fun <reified T : Event> on(priority: Int = 0, noinline cb: T.() -> Unit) {
        events.add(EventBus.on<T>(priority, {
            val event = this
            when (event) {
                is UnfilteredEvent -> if (inArea() && inSubarea()) cb()
                else -> if (inEnvironment()) cb()
            }
        }, false))
    }

    @JvmName("onPacket")
    protected inline fun <reified E, reified P : Packet<*>> on(
        priority: Int = 0,
        noinline cb: PacketScope<E, P>.() -> Unit
    ) where E : Event, E : PacketEvent {
        events.add(EventBus.on<E, P>(priority, {
            if (inEnvironment()) cb()
        }, false))
    }

    enum class Tag(val desc: String = "") {
        NONE, LEGACY("A rewrite is currently planned. This module is no longer updated.")
    }

    private companion object {
        private fun getCategory(clazz: Class<out Module>): Category? =
            Category.entries.find { clazz.`package`.name.contains(it.name, true) }

        /** Extract the package segment immediately after the category name as
         *  the sub-category label. Returns null if the module sits directly in
         *  the category package (no sub-grouping). Lowercased so the rendered
         *  header can capitalise consistently. */
        private fun getSubCategory(clazz: Class<out Module>, category: Category): String? {
            val parts = clazz.`package`.name.split('.')
            val idx = parts.indexOfFirst { it.equals(category.name, true) }
            if (idx < 0 || idx + 1 >= parts.size) return null
            return parts[idx + 1].lowercase()
        }
    }
}