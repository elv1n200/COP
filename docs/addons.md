# Writing a COP addon

[← Documentation index](README.md)

> [!WARNING]
> The addon API is **experimental**. It is intentionally small, but it does not yet carry a source- or binary-compatibility guarantee. Compile and test an addon against the exact COP release and Minecraft target you intend to support.

A COP addon is a separate Fabric mod that registers additional COP modules. Registered modules appear in the ClickGUI and use COP's settings, keybind and event infrastructure without copying their source into the COP repository.

## Target environment

The active `mc26` branch targets:

- Minecraft 26.1.2;
- Java 25;
- Fabric Loader 0.19.2;
- Fabric API 0.149.0+26.1.2;
- Fabric Language Kotlin 1.13.9+kotlin.2.3.10.

An addon built for an older 1.21.x COP jar cannot be assumed to work with mc26.

## 1. Add the COP jar

COP is not published to a Maven repository. Put the playable `cop-<version>+mc26.1.2.jar` in your addon's `libs/` directory and add it as a normal mc26 implementation dependency:

```kotlin
dependencies {
    implementation(files("libs/cop-1.8.0-beta.4+mc26.1.2.jar"))

    // Add the same Minecraft, Fabric Loader, Fabric API and
    // Fabric Language Kotlin dependencies as your addon normally uses.
}
```

Use `implementation` on mc26. Do not copy a 1.21.x `modImplementation` example: the mc26 setup uses Fabric's no-remap path for unobfuscated Minecraft 26. Also do not depend on the `-sources.jar`.

## 2. Declare the entrypoint

Declare COP as a Fabric dependency and register a `cop` entrypoint in the addon's `fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "my-cop-addon",
  "version": "1.0.0",
  "name": "My COP Addon",
  "environment": "client",
  "depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": "=26.1.2",
    "java": ">=25",
    "fabric-api": ">=0.149.0+26.1.2",
    "fabric-language-kotlin": ">=1.13.9+kotlin.2.3.10",
    "cop": "=1.8.0-beta.4"
  },
  "entrypoints": {
    "cop": [
      {
        "adapter": "kotlin",
        "value": "com.example.myaddon.MyAddon"
      }
    ]
  }
}
```

Update both the jar filename and the exact `cop` version constraint when moving to another tested COP release. For a Java entrypoint, omit `"adapter": "kotlin"` and point `value` at the Java implementation.

## 3. Implement `CopAddon`

```kotlin
package com.example.myaddon

import cop.api.addon.CopAddon
import cop.api.addon.CopAddonRegistrar

object MyAddon : CopAddon {
    override fun onInitialize(registrar: CopAddonRegistrar) {
        registrar.register(CoolModule)
    }
}
```

COP calls `onInitialize` once during client initialization, after its built-in modules are registered and before configuration is loaded. If an addon throws during initialization, COP logs and skips that addon instead of stopping COP's own startup.

## 4. Create a module

```kotlin
package com.example.myaddon

import cop.api.events.TickEvent
import cop.module.Module
import cop.utils.ChatUtils.modMessage

object CoolModule : Module(
    name = "MyAddon Cool Module",
    desc = "Posts a periodic local status message.",
) {
    private val interval by slider(
        "Interval",
        20.0,
        1.0,
        100.0,
        1.0,
        desc = "Ticks between messages.",
    )

    private var ticks = 0

    init {
        on<TickEvent.End> {
            if (++ticks < interval.toInt()) return@on
            ticks = 0
            modMessage("My addon is active.")
        }
    }
}
```

Because this class is outside the `cop.` package, it appears in the **Addon** ClickGUI category by default.

### Choose a category explicitly

Import `Category` and pass the primary `Module` constructor parameters `explicitCategory` and `explicitSubCategory`:

```kotlin
package com.example.myaddon

import cop.module.Category
import cop.module.Module

object DungeonHelper : Module(
    name = "MyAddon Dungeon Helper",
    desc = "Example module grouped under Dungeons.",
    explicitCategory = Category.DUNGEON,
    explicitSubCategory = "myaddon",
) {
    // Module implementation
}
```

The import is required for the example to compile. An unknown subcategory name is allowed and becomes a collapsible subgroup label.

## Module names must be globally unique

Choose a module name that is unique across COP, every installed addon and every other module **without regard to letter case**. For example, `MyAddon Routes` conflicts with `myaddon routes`.

This matters because command lookup and persisted configuration resolve names case-insensitively. If an addon tries to register a colliding name, COP skips that module and writes a warning to the log. Prefixing names with the addon name is recommended.

## Available surface

Addon modules can currently use the same public classes that COP's built-in modules use, including:

- `Module` and the settings DSL (`switch`, `slider`, `colourPicker`, `selector`, `textInput`, `keybind` and HUD builders);
- event listeners through `on<Event> { ... }`;
- `cop.utils.skyblock.PriceClient`;
- dungeon data and scanning helpers;
- world-render and HUD helpers.

Only `CopAddon` and `CopAddonRegistrar.register(...)` form the dedicated addon entry surface today. Other classes are accessible from the jar but remain internal project APIs in practice and may change between COP releases. Keep dependencies narrow and retest after every COP update.

## Release checklist for an addon

- Build with Java 25 against the exact mc26 COP jar.
- Start a client with COP, the addon and only their required Fabric dependencies.
- Confirm the module appears once and in the intended category.
- Toggle it by ClickGUI, keybind and `/cop toggle <exact name>`.
- Restart the client and verify its settings persist.
- Check that every module name remains globally unique with all supported addons installed.
- Document the exact COP and Minecraft versions supported by the addon release.
