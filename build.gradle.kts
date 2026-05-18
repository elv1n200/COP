import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // mc26: non-obfuscated loom plugin (no remap, no mappings config).
    id("net.fabricmc.fabric-loom")
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
    // Mojang's new date-based scheme. 26.1.2 sits *above* 1.21.11 in Stonecutter's
    // version comparison, so every `>= 1.21.11` source branch + replacement also
    // applies here; any 26.x-only breakage gets its own `>= 26.1` guards.
    "26.1.2" to McVersionInfo(
        minecraft     = "26.1.2",
        loader        = "0.19.2",
        fabricKotlin  = "1.13.9+kotlin.2.3.10",
        fabricApi     = "0.149.0+26.1.2",
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

// mc26 branch: unobfuscated MC only. Mojang ships readable names in the jar,
// the LoomNoRemapGradlePlugin does NO remapping — so there is no `minecraft`
// remap, no `mappings(...)` config (the no-remap plugin doesn't even define
// that configuration), and mods are plain `implementation`/`runtimeOnly`
// rather than `modImplementation`/`modRuntimeOnly` (those would re-enable the
// remap machinery). The obfuscated 1.21.x wiring lives on `main`.
dependencies {
    minecraft("com.mojang:minecraft:${mcInfo.minecraft}")

    implementation("net.fabricmc:fabric-loader:${mcInfo.loader}")
    implementation("net.fabricmc:fabric-language-kotlin:${mcInfo.fabricKotlin}")
    implementation("net.fabricmc.fabric-api:fabric-api:${mcInfo.fabricApi}")
    runtimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.1")

    // Self-update against GitHub releases. `include(...)` bundles it inside the
    // mod jar so end users don't have to install a second mod.
    implementation("moe.nea:libautoupdate:1.3.1")
    include("moe.nea:libautoupdate:1.3.1")

    implementation("io.github.classgraph:classgraph:4.8.184")
    include("io.github.classgraph:classgraph:4.8.184")

    property("minecraft_lwjgl_version").let {
        implementation("org.lwjgl:lwjgl-nanovg:$it")
        include("org.lwjgl:lwjgl-nanovg:$it")

        listOf("windows", "linux", "macos", "macos-arm64").forEach { v ->
            implementation("org.lwjgl:lwjgl-nanovg:$it:natives-$v")
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
            // MC 26.x is Java-25 bytecode; 1.21.x stays on 21.
            jvmTarget = if (mcVersion.startsWith("26")) JvmTarget.JVM_25 else JvmTarget.JVM_21
            freeCompilerArgs.add("-Xlambdas=class")
        }
    }

    compileJava {
        val rel = if (mcVersion.startsWith("26")) "25" else "21"
        sourceCompatibility = rel
        targetCompatibility = rel
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    // Per-version "build + collect into dist/" task. Stonecutter aggregates
    // these across all subprojects.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this MC version and copies its jar into dist/."
        // Obfuscated loom produces `remapJar`; the no-remap (unobfuscated 26.x)
        // plugin has no remap step so the artifact is the plain `jar`. Pick
        // whichever exists — referencing a missing task fails configuration
        // and cascades (Stonecutter wiring breaks too).
        val jarTaskName = if (project.tasks.findByName("remapJar") != null) "remapJar" else "jar"
        from(project.tasks.named(jarTaskName))
        into(rootProject.rootDir.resolve("dist"))
        dependsOn("build")
    }
}

// mc26: Stonecutter 0.9 + the no-remap loom plugin doesn't auto-redirect the
// subproject's source set onto the preprocessed output (verified: stonecutter-
// Generate produces correct sources under build/generated/stonecutter/main but
// compileKotlin still reads raw src/). Wire it explicitly and order the compile
// tasks after generation.
if (mcVersion.startsWith("26")) {
    val genMain = layout.buildDirectory.dir("generated/stonecutter/main")
    sourceSets.named("main") {
        java.setSrcDirs(listOf(genMain.map { it.dir("java") }))
        resources.setSrcDirs(listOf(genMain.map { it.dir("resources") }))
    }
    // Kotlin keeps its own source-dir list; point it at the generated kotlin
    // (+ java, for mixed sources) tree.
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        sourceSets.named("main") {
            kotlin.setSrcDirs(listOf(genMain.map { it.dir("kotlin") }, genMain.map { it.dir("java") }))
        }
    }
    listOf("compileKotlin", "compileJava", "processResources").forEach { t ->
        tasks.named(t) { dependsOn("stonecutterGenerate") }
    }
}

java {
    withSourcesJar()
    // Loom decompiles MC under this toolchain — 26.x mandates JDK 25.
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(if (mcVersion.startsWith("26")) 25 else 21)
        )
    }
}
