package cop.api.addon

import cop.module.Module

/**
 * Entry point for a COP addon — a *separate* Fabric mod that hooks into COP to
 * add its own modules, without living in the COP source tree.
 *
 * ## How to write an addon
 *
 * 1. Make a normal Fabric mod that depends on COP. In your `build.gradle(.kts)`
 *    add the COP jar as a compile dependency (e.g. `modImplementation` /
 *    `compileOnly` pointing at a COP release jar), and in your `fabric.mod.json`
 *    add COP to `depends`:
 *    ```json
 *    "depends": { "cop": "*" }
 *    ```
 *
 * 2. Register a `cop` entrypoint pointing at your [CopAddon] implementation:
 *    ```json
 *    "entrypoints": {
 *      "cop": [
 *        { "adapter": "kotlin", "value": "com.example.MyAddon" }
 *      ]
 *    }
 *    ```
 *    (Drop the `"adapter": "kotlin"` line if your addon is written in Java.)
 *
 * 3. Implement [CopAddon] and register your modules:
 *    ```kotlin
 *    object MyAddon : CopAddon {
 *        override fun onInitialize(registrar: CopAddonRegistrar) {
 *            registrar.register(CoolModule)
 *        }
 *    }
 *    ```
 *
 * 4. Write modules exactly like COP's own — extend `Module`, use the same
 *    settings DSL (`switch`, `slider`, `colourPicker`, `keybind`, …), the same
 *    event bus (`on<SomeEvent> { … }`), and COP utilities (`PriceClient`,
 *    dungeon scanning, render helpers). Since your module's package isn't under
 *    `cop.`, it lands in the **Addon** ClickGUI column by default; pass
 *    `category = ...` / `subCategory = ...` to the `Module` constructor to place
 *    it elsewhere or group it.
 *
 * COP calls [onInitialize] once at client-init time, after its own modules are
 * registered but before config is loaded — so addon module settings persist and
 * restore exactly like built-in ones. An addon that throws during init is logged
 * and skipped; it won't take COP down with it.
 */
interface CopAddon {
    fun onInitialize(registrar: CopAddonRegistrar)
}

/**
 * Handed to each [CopAddon.onInitialize]. The surface is intentionally small for
 * now — [register] your [Module]s and they show up in the ClickGUI, get keybind
 * handling, and persist to the shared config. More hooks (commands, HUD elements)
 * can be added here later without breaking existing addons.
 */
interface CopAddonRegistrar {
    /** Add one or more modules to COP. Order is preserved within a category. */
    fun register(vararg modules: Module)
}
