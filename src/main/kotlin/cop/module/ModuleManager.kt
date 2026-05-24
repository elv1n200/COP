package cop.module

import cop.api.events.GuiEvent
import cop.api.events.KeyEvent
import cop.api.events.MouseEvent
import cop.api.events.core.EventBus
import cop.api.input.CatKeys
import cop.module.impl.dungeon.*
import cop.module.impl.dungeon.puzzlesolvers.PuzzleSolvers
import cop.module.impl.mining.*
import cop.module.impl.misc.*
import cop.module.impl.misc.riftsolvers.MirrorverseSolvers
import cop.module.impl.player.*
import cop.module.impl.render.*
import cop.module.settings.impl.KeybindComponent

object ModuleManager {
    val modules = mutableListOf<Module>()

    fun initialise() {
        modules += listOf(
            // ===========================================================
            // DUNGEONS — grouped by what each module actually *does*, so
            // the in-game category list reads as a coherent flow instead
            // of mixing visuals, helpers and bots randomly.
            // ===========================================================

            // --- World render / ESP -----------------------------------
            DungeonMap,
            DungeonESP,
            HiddenMobs,
            NecronPlatformHighlight,
            FullBlockHitboxes,
            FuckDiorite,
            PersistentSecretHeads,

            // --- HUD timers / counters / per-floor info ---------------
            Splits,
            Secrets,
            TickTimers,
            InvincibilityTimer,
            CooldownDisplay,
            M3FFDisplay,
            F7BossTitles,
            M7Relics,
            DoorKeys,
            MaxorsCrystals,
            ShadowAssassinAlert,

            // --- Solvers / overlays for puzzles & mechanics -----------
            PuzzleSolvers,
            SimonSays,
            ArrowAlign,
            SecretRoutes,
            AutoCroesus,
            BonzoStaffHelper,
            TerminalWaypoints,

            // --- QoL / mild automation --------------------------------
            LeapMenu,
            AutoLeap,
            AutoCloseChest,
            CancelInteract,
            AutoMask,
            AutoGFS,
            AutoPotionBag,
            AutoSuperboom,
            Ragnarock,

            // --- Macros / heavy automation ("cheaty" stuff) -----------
            TerminalAura,
            AutoTerms,
            SecretTriggerBot,
            SecretAura,
            DungeonBreaker,
            AutoBloodRush,
            AutoClear,
            AutoRCM,
            AutoLCM,
            M3AutoFF,

            // MISC
            Test,
            Chat,
            ChatReplacements, // todo remove/replace
            VisualWords,
            ItemQuality,
            PetKeybinds,
            WardrobeKeybinds,
            AntiNick,
            AutoClicker,
            AutoAnvilBookCombine,
            Inventory,
//            CustomTriggers,
            MirrorverseSolvers,
            CatMode,
            SpotifyDisplay,
            LobbyMarker,
            AutoUpdater,

            // PLAYER
            AutoSprint,
            PlayerDisplay,
            Tweaks,
            CameraHelper,
            LagDetector,
            SnapTap,
            EtherwarpHelper,
            FishingHelper,
            AutoSoulcry,

            // RENDER
            NameTags,
            RenderOptimiser,
            NickHider,
            ClickGui,
            PlayerESP,
            EtherwarpOverlay,
            CustomMageBeam,
            ArrowHitboxes,
            GameTint,

            // MINING
            CrystalHollowsMap,
            CrystalHollowsScanner,
            GrieferTracker,
        )

        modules.forEach { module ->
            module.keybinding.let {
                module.register(KeybindComponent("Key bind", it, desc = "Toggles the module"))
            }
        }

//        EventBus.on<AreaEvent.Main> { modules.forEach { it.onToggle(it.enabled) } }
//        EventBus.on<AreaEvent.Sub> { modules.forEach { it.onToggle(it.enabled) } }

        EventBus.on<KeyEvent.Press> { invokeKeybind(key, true) }
        EventBus.on<KeyEvent.Release> { invokeKeybind(key, false) }
        EventBus.on<MouseEvent.Click> { invokeKeybind(button - 100, state) }

        EventBus.on<GuiEvent.Key.Press> { invokeKeybind(key, true) }
        EventBus.on<GuiEvent.Key.Release> { invokeKeybind(key, false) }
        EventBus.on<GuiEvent.Click> { invokeKeybind(button - 100, state) }
    }

    private fun invokeKeybind(key: Int, pressed: Boolean) {
        if (key == CatKeys.KEY_NONE) return

        modules.forEach { module ->
            module.settings.filterIsInstance<KeybindComponent>()
                .filter { it.value.key == key && it.value.isModifierDown() }
                .forEach { component ->
                    if (pressed) component.value.onPress?.invoke()
                    else component.value.onRelease?.invoke()
                }
        }
    }

    fun getModuleByName(name: String?): Module? = modules.firstOrNull { it.name.equals(name, true) }
}