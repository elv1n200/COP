pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net")
        maven("https://maven.kikugie.dev/snapshots")
        maven("https://maven.kikugie.dev/releases")
    }

    val loom_version: String by settings
    val kotlin_version: String by settings

    plugins {
        // mc26: `net.fabricmc.fabric-loom` = LoomNoRemapGradlePlugin (the
        // non-obfuscated path, no `mappings` config). The short `fabric-loom`
        // id maps to the legacy remap plugin which demands mappings — wrong
        // for unobfuscated 26.x. `main` keeps the short id for obf 1.21.x.
        id("net.fabricmc.fabric-loom") version loom_version
        kotlin("jvm") version kotlin_version
    }
}

plugins {
    // Source-preprocessing multi-version plugin. Versioned subprojects share
    // the same `src/` tree, with per-version code branches expressed as
    // `//? if mc >= "1.21.11" { ... } else { ... }` comments.
    id("dev.kikugie.stonecutter") version "0.8"
}

rootProject.name = "cop"

stonecutter {
    create(rootProject) {
        // mc26 branch builds *only* 26.1.2 — the 1.21.x targets stay on `main`
        // with the Kotlin-2.2 / Java-21 toolchain.
        versions("26.1.2")
        vcsVersion = "26.1.2"
    }
}