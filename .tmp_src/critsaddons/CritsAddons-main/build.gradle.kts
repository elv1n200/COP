import org.gradle.kotlin.dsl.annotationProcessor
import org.gradle.kotlin.dsl.implementation
import org.gradle.kotlin.dsl.include
import org.gradle.kotlin.dsl.mappings
import org.gradle.kotlin.dsl.modImplementation
import org.gradle.kotlin.dsl.modRuntimeOnly
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.HttpURLConnection
import java.net.URI

plugins {
    kotlin("jvm") version "2.3.10"
    id("fabric-loom") version "1.15-SNAPSHOT"
    id("maven-publish")
    id ("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

fun updateGradleProperty(key: String, value: String) {
    val propsFile = rootProject.file("gradle.properties")
    val lines = propsFile.readLines().toMutableList()
    var updated = false

    for (i in lines.indices) {
        val raw = lines[i]
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("$key=") || trimmed.startsWith("$key =")) {
            lines[i] = "$key=$value"
            updated = true
            break
        }
    }

    if (!updated) lines.add("$key=$value")
    propsFile.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}



repositories {
    maven { url = uri("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") }
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    include("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    modImplementation("com.github.Noamm9.NoammAddons:${project.property("noammaddons_type")}:${project.property("noammaddons_version")}")
}

tasks.register("setNoammAddonsVersion") {
    group = "automation"
    description = "Updates noammaddons_version in gradle.properties. Usage: gradle setNoammAddonsVersion -PnoammVersion=<hash-or-tag>"

    doLast {
        val noammVersion = (findProperty("noammVersion") as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Missing -PnoammVersion. Example: gradle setNoammAddonsVersion -PnoammVersion=8afcda8279")

        updateGradleProperty("noammaddons_version", noammVersion)
        logger.lifecycle("Updated noammaddons_version=$noammVersion in gradle.properties")
    }
}

tasks.register("syncNoammAddonsVersion") {
    group = "automation"
    description = "Fetches latest commit SHA for NoammAddons branch (defaults to noammaddons_type) and updates noammaddons_version."

    doLast {
        val owner = (findProperty("noammRepoOwner") as String?)?.trim().orEmpty().ifBlank { "Noamm9" }
        val repo = (findProperty("noammRepoName") as String?)?.trim().orEmpty().ifBlank { "NoammAddons" }
        val branch = (findProperty("noammBranch") as String?)?.trim().orEmpty().ifBlank {
            (findProperty("noammaddons_type") as String).trim()
        }
        val shaLength = (findProperty("noammShaLength") as String?)?.toIntOrNull() ?: 10

        val apiUrl = URI("https://api.github.com/repos/$owner/$repo/commits/$branch").toURL()
        val connection = (apiUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CritsAddons-Gradle")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        val response = runCatching {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        }.getOrElse { error("Failed requesting GitHub API: ${it.message}") }

        val (statusCode, body) = response
        if (statusCode !in 200..299) {
            error("GitHub API request failed ($statusCode): $body")
        }

        val sha = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: error("Could not parse commit SHA from GitHub response.")

        val finalVersion = sha.take(shaLength.coerceIn(7, 40))
        updateGradleProperty("noammaddons_version", finalVersion)
        logger.lifecycle("Updated noammaddons_version=$finalVersion from $owner/$repo branch '$branch'")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_version" to project.property("minecraft_version")!!, "loader_version" to project.property("loader_version")!!, "kotlin_loader_version" to project.property("kotlin_loader_version")!!)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }
}

loom {
    runConfigs.named("client") {
        isIdeConfigGenerated = true
        vmArg("-XX:+AllowEnhancedClassRedefinition")
    }

    runConfigs.named("server") {
        isIdeConfigGenerated = false
    }
}

afterEvaluate {
    loom.runs.named("client") {
        vmArg("-javaagent:${configurations.compileClasspath.get().find { "sponge-mixin" in it.name }}")
    }
}
