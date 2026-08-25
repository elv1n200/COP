# Branching & versioning

COP targets **Minecraft 26.x only**. Hypixel SkyBlock no longer runs on 1.21.x,
so the 1.21 line is frozen — all active development happens on the 26.x line.

## Branches

| Branch | Status | Minecraft | Toolchain | Purpose |
|---|---|---|---|---|
| **`main`** | **active** | 26.x (currently 26.1.2) | JDK 25 · Kotlin 2.3 · Loom 1.16 | The rolling development line. Everything ships from here. |
| **`legacy-1.21`** | **frozen** | 1.21.10 / 1.21.11 | JDK 21 · Kotlin 2.2 · Loom 1.14 | Final 1.21 snapshot. No fixes, no features — kept for history only. |

> `main` is a *rolling* branch: it tracks whatever Minecraft version Hypixel
> SkyBlock currently runs. When a new 26.x point release (26.2, …) lands, it is
> handled on `main` via Stonecutter source-preprocessing (`//?` directives keyed
> on version comparisons), the same mechanism that already bridges the
> 1.21.11 → 26 renames.

### `legacy-1.21` is closed

The last 1.21 release is tagged **`1.7.2`**. `legacy-1.21` exists so that history,
issues and the old toolchain remain reachable — **not** as a maintained target.
Bug reports against 1.21.x are out of scope; the fix is to update the game/loader
to a 26.x-supported setup.

## Versioning

- One continuous [SemVer](https://semver.org/) line. `1.7.x` was the final 1.21
  series; the 26.x line continues from **`1.8.0`**.
- 26.x releases are cut as **pre-releases** (`-beta.N`) until the
  [client-test checklist](docs/client-test-checklist.md) has been completed
  in-game for the candidate — a green Gradle build is necessary but not
  sufficient, because mixins, rendering, inputs and packet behaviour only show
  up in a real client.
- Jars are named `cop-<version>+mc<minecraft_version>.jar`
  (e.g. `cop-1.8.0+mc26.1.2.jar`), so the game version is always visible in the
  filename.

## Why the split existed

During the 1.21 → 26 port the two eras diverged hard: 26.x ships **unobfuscated**
and is Java-25 bytecode, forcing a newer toolchain (JDK 25 / Kotlin 2.3 / Loom
1.16) that the 1.21 line couldn't use. That work happened on a transitional
`mc26` branch. With SkyBlock dropping 1.21.x, `mc26` has become the mainline and
is folded into `main`; the old 1.21 `main` moved to `legacy-1.21`.
