# Writing a COP addon

COP exposes a small, stable addon API so you can ship your own modules in a
**separate Fabric mod** without touching COP's source. Your modules appear in
COP's ClickGUI, get keybind handling, and persist to the shared config exactly
like the built-in ones.

## Why an addon instead of a fork

- Your code stays in your own repo / jar, on your own release cycle.
- You depend on a COP release rather than merging against its `main`.
- Users install COP + your addon jar side by side.

## 1. Depend on COP

In your addon's `build.gradle.kts`, add a COP release jar as a dependency
(there's no maven publication yet, so point at the jar directly):

```kotlin
dependencies {
    modImplementation(files("libs/cop-1.4.4+mc1.21.10.jar"))
    // ...your usual fabric-loader / fabric-api / fabric-language-kotlin deps
}
```

In your `fabric.mod.json`, declare COP as a dependency **and** register a `cop`
entrypoint pointing at your [`CopAddon`](../src/main/kotlin/cop/api/addon/CopAddon.kt)
implementation:

```json
{
  "id": "my-cop-addon",
  "depends": { "cop": "*" },
  "entrypoints": {
    "cop": [
      { "adapter": "kotlin", "value": "com.example.MyAddon" }
    ]
  }
}
```

Drop `"adapter": "kotlin"` if your addon is written in Java.

## 2. Implement `CopAddon`

```kotlin
package com.example

import cop.api.addon.CopAddon
import cop.api.addon.CopAddonRegistrar

object MyAddon : CopAddon {
    override fun onInitialize(registrar: CopAddonRegistrar) {
        registrar.register(CoolModule)
    }
}
```

COP calls `onInitialize` once at client init — after its own modules are
registered, before config is loaded — so your module settings save/restore like
any other. If your addon throws during init, COP logs it and carries on; it
won't crash the client.

## 3. Write a module

Exactly like a COP module: extend `Module`, use the same settings DSL and event
bus.

```kotlin
package com.example

import cop.api.events.TickEvent
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.modMessage

object CoolModule : Module(
    name = "Cool Module",
    desc = "Does a cool thing."
) {
    private val interval by slider("Interval", 20.0, 1.0, 100.0, 1.0, desc = "Ticks between messages.")

    private var ticks = 0

    init {
        on<TickEvent.End> {
            if (++ticks < interval.toInt()) return@on
            ticks = 0
            modMessage("§dmeow from the addon")
        }
    }
}
```

### Categories

Because your module's package isn't under `cop.`, it lands in the **Addon**
ClickGUI column by default. To place it in an existing column or group it under
a collapsible sub-header, pass the constructor overrides:

```kotlin
object CoolModule : Module(
    name = "Cool Module",
    category = Category.DUNGEON,   // cop.module.Category
    subCategory = "myaddon",       // collapsible sub-header label
) { /* ... */ }
```

## What you can use from COP

Everything COP's own modules use is on the classpath once you depend on the jar:

- **`Module`** base class + the settings DSL (`switch`, `slider`, `colourPicker`,
  `selector`, `textInput`, `keybind`, `.childOf(...)`, HUD builders).
- **Event bus** via `on<Event> { ... }` inside a module's `init` — every event
  under `cop.api.events.*` (Tick / Render / Packet / Chat / Gui / Dungeon / ...).
- **`cop.utils.skyblock.PriceClient`** — bazaar + LBIN + item-registry pricing.
- **Dungeon scanning** — `cop.api.skyblock.dungeon.Dungeon`, `ScanUtils`,
  `OdonRoom`, `RouteData`, `SecretCoords`.
- **Render helpers** — `cop.utils.render.*` (`drawLine`, `drawFilledBox`,
  `drawWireFrameBox`, `drawText`, …) and the HUD system.

The addon surface itself is deliberately tiny right now (`register(vararg
modules)`); more hooks (commands, HUD elements) can be added to
`CopAddonRegistrar` later without breaking existing addons.
