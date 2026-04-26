# CritsAddons 1.21.10

Addon features for NoammAddons on Minecraft 1.21.10. Current mod version: `1.2.2`.

## What Is This

CritsAddons is an addon mod that extends NoammAddons with extra client-side dungeon/QoL features.

## Install

1. Install [Fabric for Minecraft 1.21.10](https://fabricmc.net/use/installer/).
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
4. Install NoammAddons for Minecraft `1.21.10` from the [NoammAddons releases page](https://github.com/Noamm9/NoammAddons/releases).
5. Download the latest CritsAddons `.jar` from the [CritsAddons releases page](https://github.com/fateshop/CritsAddons/releases).
6. Put both `.jar` files in `.minecraft/mods`.
7. Launch Minecraft with your Fabric profile.

NoammAddons is required. CritsAddons does not run standalone.

## Features

- **Auto LCM**  
  While holding left click as Mage in dungeons, auto-clicks LCM with configurable random tick delay.
- **Auto RCM**  
  On one right-click with a selected trigger item, swaps to a selected RCM item, right-clicks, then swaps back. Optional cooldown check can block activation while the RCM item is on cooldown.
- **Better Glow**  
  Custom high-star mob glow/chams controls.
- **Cooldown Display**  
  Client-side item cooldown overlay for selected SkyBlock ability items.
- **Custom Font**  
  Loads a `.ttf` font from `config/CritsAddons/fonts` and applies it to Minecraft text rendering.
- **2D Star ESP**  
  Draws a flat 2D ESP box around starred dungeon mobs with configurable border color, fill color, line width, glow border, glow radius, through-walls rendering, and max distance. The box can either rotate with the mob or always face your camera.
- **M3 FF Display**  
  Displays a Fire Freeze timing indicator for M3 Professor dialogue.
- **M3 Auto FF**  
  Swaps to Fire Freeze Staff when the M3 FF timer starts, right-clicks when the timer reaches zero, then swaps back.
- **NSR Completed**  
  Tracks completed rooms in `secretRoutes.json` and renders a HUD sorted as **Yellow (in current run, incomplete)**, **Green (completed)**, **Red (incomplete)**.
- **NSR Helper**  
  Shows route completeness helpers including same start/end checks, start door coverage, end door coverage, and missing block rendering.
- **Party Finder**  
  Adds Party Finder overlays, tooltip stats, and optional auto-kick requirements for class, secrets, score-per-run, and personal best.
- **Party HUD**  
  Displays party members with class, class level, Catacombs level, secrets stats, and personal best, with live party chat syncing and cache clear.
- **Persistent Secret Heads**  
  Keeps specific clicked secret heads visible as ghost targets for easier route recording.
- **Secret Routes**  
  Records/replays room routes with Etherwarp/TNT/Break/Hyperion/secret/wait/bat steps, start links, end links/helpers, auto-start, and step resume.
- **Secret Routes Debugger**  
  Channel-based debug logs for route planning/playback/packet/etherwarp/mana/wait/recording.
- **Stat Display**  
  Custom HUD bars and numbers for health, mana, overflow mana, EHP, defense, and speed, with color controls, icon labels, optional default stat hiding, and optional experience bar hiding.
- **Zoom**  
  Keybind zoom with mouse-wheel zoom adjustment and optional smoothed camera turning.

## Default Files

Fresh installs include bundled defaults from this build:

- `config/NoammAddons/config.json` is created from the current CritsAddons/NoammAddons settings if it does not already exist.
- `config/NoammAddons/secretRoutes.json` is created from the current active Secret Routes file if it does not already exist.
- `config/CritsAddons/fonts/BubbleLetters_Filled_TrueFix.ttf` is created if it does not already exist.

Existing files are never overwritten by the default installer.

## Secret Routes Commands

Use commands while inside a scanned dungeon room.

- `/nsr`  
  Start recording the main route (requires standing centered on the start block).
- `/nsr continue`  
  Continue recording from the existing saved route in the current room (appends steps to the current route).
- `/nsr save`  
  Save the active route recording.
- `/nsr cancel`  
  Cancel active `/nsr`, `/nsr start`, or `/nsr end` recording.
- `/nsr delete`  
  Delete the room's saved route.
- `/nsr complete`  
  Mark the current room as completed for **NSR Completed** tracking.
- `/nsr start`  
  Start one-link start recording (requires centered block position). Do exactly one Etherwarp from your current start block to an existing known start block. It auto-saves after a valid link.
- `/nsr start delete`  
  While centered on a non-original start block, delete that start link and dependent links that route through it.
- `/nsr end`  
  Start one-link end recording from your current known end/end-helper block to a final end block (auto-saves after one valid Etherwarp).
- `/nsr end helper`  
  Start one-link recording from your current known end/end-helper block to an end-helper block.
- `/nsr end delete`  
  While centered on a deletable end/end-helper block, delete it and dependent linked end nodes.
- `/nsr wait`  
  Insert a wait-for-secret-progress step in the active `/nsr` recording.
- `/nsr bat`  
  Insert a wait-for-bat-spawn step in the active `/nsr` recording.
- `/nsr kill`  
  Pause recording, right-click ground with a Wither Blade (Hyperion/Astraea/Valkyrie/Scylla), then resume recording.
- `/nsr add ew` or `/nsr add etherwarp`  
  Add an Etherwarp step immediately.
- `/nsr add tnt`  
  Add a TNT placement step from the current hit result.
- `/nsr add break`  
  Add a break-block step from the current hit result.
- `/nsr add hyp` or `/nsr add hyperion`  
  Add a Hyperion step (uses any Wither Blade at playback).
- `/nsr add secret`  
  Add a right-click secret step from the current hit result.
- `/nsr add wait`  
  Add a wait-for-secret-progress step.
- `/nsr add bat`  
  Add a wait-for-bat-spawn step.

## Secret Routes Playback Notes

- Playback starts from the Secret Routes playback keybind.
- Auto-start supports start-block-only mode, center-only checks, center hold time, and center radius.
- Start route from anywhere supports step resume from centered recorded step blocks.
- Start-link chains are followed before route-step resume logic.
- End links/helpers support post-route chaining to non-original final end blocks.
- Routes file is selectable via `Routes Config File`.
- `Reload Routes File` reloads the selected JSON without restarting the game.
- `secretRoutes.json` now stores both route data and NSR completion data (`routes` + `completedRooms`).

## Party HUD Notes

- Party snapshot now updates from party chat events (`/p list`, joins, leaves, removes).
- `Clear Cache` button clears HUD/profile caches and forces a short live refetch window.

## Zoom Notes

- Hold the zoom keybind to zoom in.
- Scroll while zoomed to adjust zoom amount.
- Rotation smoothing while zoomed is controlled by `Rotation Smoothness` (`0` = instant, `100` = very smooth/slow).

## Auto LCM Notes

- While in dungeons as Mage, holding left click auto-triggers LCM left clicks.
- Delay is randomized between `Min Delay (ticks)` and `Max Delay (ticks)` (default 4 to 6 ticks).

## Dependency Update Automation

If NoammAddons updates, you can sync `noammaddons_version` in `gradle.properties` with Gradle tasks.

- Manual set:  
  `./gradlew setNoammAddonsVersion -PnoammVersion=<hash-or-tag>`
- Auto-sync latest commit from upstream branch:  
  `./gradlew syncNoammAddonsVersion`

Optional overrides for auto-sync:

- `-PnoammBranch=<branch>` (defaults to `noammaddons_type`, usually `cheat`)
- `-PnoammShaLength=<7-40>` (default `10`)
- `-PnoammRepoOwner=<owner>` and `-PnoammRepoName=<repo>` (defaults: `Noamm9`, `NoammAddons`)

Windows example:

`.\\gradlew.bat syncNoammAddonsVersion -PnoammBranch=cheat`

## Build

- `./gradlew build`

## Contributions

- Open an issue for bug reports or feature requests.
- Open a pull request for fixes or additions.

## License

This project is licensed under [LICENSE.txt](LICENSE.txt).
