import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("fabric-loom")
    kotlin("jvm")
    `maven-publish`
}

// ---------------------------------------------------------------------------
// Multi-MC-version support via Stonecutter.
// ---------------------------------------------------------------------------
// This file runs once per versioned subproject (one per entry in
// settings.gradle.kts → `stonecutter.create { versions(...) }`).
// `stonecutter.current.version` identifies which MC version this invocation
// is configuring, and `versionMatrix` maps that to the matching dep set.
//
// Useful tasks:
//   ./gradlew build                                    builds the active version
//   ./gradlew "Reset active project"                   reset to default
//   ./gradlew "Set active project to 1.21.11"          switch active version
//   ./gradlew chiseledBuild                            builds *all* versions
//   ./gradlew chiseledBuildAndCollect                  ↑ + collects jars in dist/
// ---------------------------------------------------------------------------
data class McVersionInfo(
    val minecraft: String,
    val loader: String,
    val fabricKotlin: String,
    val fabricApi: String,
)

val versionMatrix = mapOf(
    "1.21.10" to McVersionInfo(
        minecraft     = "1.21.10",
        loader        = "0.18.2",
        fabricKotlin  = "1.13.7+kotlin.2.2.21",
        fabricApi     = "0.138.4+1.21.10",
    ),
    "1.21.11" to McVersionInfo(
        minecraft     = "1.21.11",
        loader        = "0.18.4",
        // Keeping kotlin 2.2.21 here even though Nebulune uses 1.13.8+kotlin.2.3.0 —
        // we'd otherwise have to bump our own kotlin compiler too.
        fabricKotlin  = "1.13.7+kotlin.2.2.21",
        fabricApi     = "0.140.2+1.21.11",
    ),
)

val mcVersion: String = stonecutter.current.version
val mcInfo = versionMatrix[mcVersion]
    ?: error("Unknown stonecutter version='$mcVersion'. Add to versionMatrix.")

// Stamp the jar with the MC version so the two builds don't clobber each other.
version = "${property("mod_version")}+mc${mcInfo.minecraft}"
// Override the default archive base name — Stonecutter names the versioned
// subprojects after the MC version (e.g. `1.21.10`), which Gradle would
// otherwise use as the jar prefix (`1.21.10-1.0.0+mc1.21.10.jar`). Force `cop`
// so we get the clean `cop-1.0.0+mc1.21.10.jar`.
base.archivesName.set(property("archives_base_name") as String)

repositories {
    mavenCentral()
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    // libautoupdate (used by AutoUpdater module). Same maven the Skyblocker /
    // Nebulune / Athen autoupdater integrations pull from.
    maven("https://repo.nea.moe/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:${mcInfo.minecraft}")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${mcInfo.loader}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${mcInfo.fabricKotlin}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${mcInfo.fabricApi}")
    modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.1")

    // Self-update against GitHub releases. `include(...)` bundles it inside the
    // mod jar so end users don't have to install a second mod.
    modImplementation("moe.nea:libautoupdate:1.3.1")
    include("moe.nea:libautoupdate:1.3.1")

    modImplementation("io.github.classgraph:classgraph:4.8.184")
    include("io.github.classgraph:classgraph:4.8.184")

    property("minecraft_lwjgl_version").let {
        modImplementation("org.lwjgl:lwjgl-nanovg:$it")
        include("org.lwjgl:lwjgl-nanovg:$it")

        listOf("windows", "linux", "macos", "macos-arm64").forEach { v ->
            modImplementation("org.lwjgl:lwjgl-nanovg:$it:natives-$v")
            include("org.lwjgl:lwjgl-nanovg:$it:natives-$v")
        }
    }
}

loom {
    // Each versioned subproject lives in its own buildscript dir, but we share
    // the same fabric.mod.json from the root resources tree.
    runConfigs.named("client") {
        isIdeConfigGenerated = true
        vmArgs.addAll(
            arrayOf(
                "-Dmixin.debug.export=true",
                "-Ddevauth.enabled=true",
                "-Ddevauth.account=main",
                "-XX:+AllowEnhancedClassRedefinition"
            )
        )
    }

    runConfigs.named("server") {
        isIdeConfigGenerated = false
    }
}

afterEvaluate {
    loom.runs.named("client") {
        vmArg("-javaagent:${configurations.compileClasspath.get().find { it.name.contains("sponge-mixin") }}")
    }
}

tasks {
    processResources {
        // Build the expansion map explicitly so MC-version-specific values come
        // from `mcInfo` (selected by stonecutter active version) rather than
        // gradle.properties.
        val expansion = mapOf(
            "mod_id"                to project.property("mod_id"),
            "mod_name"              to project.property("mod_name"),
            "mod_version"           to project.property("mod_version"),
            "minecraft_version"     to mcInfo.minecraft,
            "loader_version"        to mcInfo.loader,
            "fabric_kotlin_version" to mcInfo.fabricKotlin,
            "fabric_api_version"    to mcInfo.fabricApi,
        )
        inputs.properties(expansion)
        filesMatching("fabric.mod.json") {
            expand(expansion)
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            freeCompilerArgs.add("-Xlambdas=class")
        }
    }

    compileJava {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    // Per-version "build + collect into dist/" task. Stonecutter aggregates
    // these across all subprojects via `chiseledBuildAndCollect`.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this MC version and copies its jar into dist/."
        // remapJar is loom's RemapJarTask, not org.gradle.api.tasks.bundling.Jar —
        // pass the named provider untyped and let `from` introspect its outputs.
        from(named("remapJar"))
        into(rootProject.rootDir.resolve("dist"))
        dependsOn("build")
    }
}

java {
    withSourcesJar()
}
