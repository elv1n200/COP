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
        id("fabric-loom") version loom_version
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
        versions("1.21.10", "1.21.11")
        vcsVersion = "1.21.10"  // The "canonical" branch — used for IDE source attachment.
    }
}