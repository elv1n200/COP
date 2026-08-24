package cop.utils

import cop.CopMod
import cop.api.skyblock.Location
import cop.module.ModuleManager
import net.fabricmc.loader.api.FabricLoader
import java.time.Instant

/**
 * Builds a paste-ready support report without collecting player names,
 * server addresses, access tokens, individual setting values, or filesystem
 * paths. Enabled module names are deliberately listed for troubleshooting.
 */
object DiagnosticsReport {
    private const val MEBIBYTE = 1024L * 1024L

    fun create(): String {
        val loader = FabricLoader.getInstance()
        val runtime = Runtime.getRuntime()
        val mc = CopMod.mc

        return format(
            DiagnosticsSnapshot(
                generatedAt = Instant.now().toString(),
                copVersion = loader.versionOf(CopMod.MOD_ID),
                minecraftVersion = loader.versionOf("minecraft"),
                loaderVersion = loader.versionOf("fabricloader"),
                fabricApiVersion = loader.optionalVersionOf("fabric-api"),
                fabricKotlinVersion = loader.optionalVersionOf("fabric-language-kotlin"),
                javaVersion = System.getProperty("java.version", "unknown"),
                javaVendor = System.getProperty("java.vendor", "unknown"),
                osName = System.getProperty("os.name", "unknown"),
                osVersion = System.getProperty("os.version", "unknown"),
                architecture = System.getProperty("os.arch", "unknown"),
                maxMemoryMiB = runtime.maxMemory() / MEBIBYTE,
                allocatedMemoryMiB = runtime.totalMemory() / MEBIBYTE,
                window = "${mc.window.width}x${mc.window.height} @ ${formatScale(mc.window.guiScale.toDouble())}x",
                worldLoaded = mc.level != null,
                currentScreen = mc.screen?.javaClass?.simpleName,
                inSkyblock = Location.inSkyblock,
                area = Location.currentArea.displayName,
                subarea = Location.subarea,
                enabledModules = ModuleManager.modules.asSequence()
                    .filter { it.enabled }
                    .map { it.name }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .toList(),
                loadedMods = loader.allMods.asSequence()
                    .map { container ->
                        DiagnosticMod(
                            id = container.metadata.id,
                            version = container.metadata.version.friendlyString,
                        )
                    }
                    .sortedBy { it.id.lowercase() }
                    .toList(),
            ),
        )
    }

    internal fun format(snapshot: DiagnosticsSnapshot): String = buildString {
        appendLine("# COP diagnostics")
        appendLine()
        appendLine("Generated: ${clean(snapshot.generatedAt)}")
        appendLine("Privacy: enabled module names are listed; no player name, server address, token, individual setting value, or filesystem path is included.")
        appendLine()
        appendLine("## Runtime")
        appendLine("- COP: ${clean(snapshot.copVersion)}")
        appendLine("- Minecraft: ${clean(snapshot.minecraftVersion)}")
        appendLine("- Fabric Loader: ${clean(snapshot.loaderVersion)}")
        appendLine("- Fabric API: ${clean(snapshot.fabricApiVersion ?: "not installed")}")
        appendLine("- Fabric Language Kotlin: ${clean(snapshot.fabricKotlinVersion ?: "not installed")}")
        appendLine("- Java: ${clean(snapshot.javaVersion)} (${clean(snapshot.javaVendor)})")
        appendLine("- OS: ${clean(snapshot.osName)} ${clean(snapshot.osVersion)} (${clean(snapshot.architecture)})")
        appendLine("- Memory: ${snapshot.allocatedMemoryMiB} MiB allocated / ${snapshot.maxMemoryMiB} MiB max")
        appendLine("- Window: ${clean(snapshot.window)}")
        appendLine()
        appendLine("## Client state")
        appendLine("- World loaded: ${snapshot.worldLoaded}")
        appendLine("- Current screen: ${clean(snapshot.currentScreen ?: "none")}")
        appendLine("- SkyBlock detected: ${snapshot.inSkyblock}")
        appendLine("- Area: ${clean(snapshot.area ?: "unknown")}")
        appendLine("- Subarea: ${clean(snapshot.subarea ?: "unknown")}")
        appendLine("- Enabled COP modules (${snapshot.enabledModules.size}): ${formatList(snapshot.enabledModules)}")
        appendLine()
        appendLine("## Loaded mods (${snapshot.loadedMods.size})")
        snapshot.loadedMods
            .sortedBy { it.id.lowercase() }
            .forEach { appendLine("- ${clean(it.id)}: ${clean(it.version)}") }
    }.trimEnd()

    private fun FabricLoader.versionOf(id: String): String =
        getModContainer(id).orElseThrow { IllegalStateException("Required mod '$id' is unavailable") }
            .metadata.version.friendlyString

    private fun FabricLoader.optionalVersionOf(id: String): String? =
        getModContainer(id).map { it.metadata.version.friendlyString }.orElse(null)

    private fun formatScale(scale: Double): String =
        if (scale % 1.0 == 0.0) scale.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, scale)

    private fun formatList(values: List<String>): String =
        values.takeIf { it.isNotEmpty() }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.joinToString(", ") { clean(it) }
            ?: "none"

    private fun clean(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
        .take(200)
}

internal data class DiagnosticsSnapshot(
    val generatedAt: String,
    val copVersion: String,
    val minecraftVersion: String,
    val loaderVersion: String,
    val fabricApiVersion: String?,
    val fabricKotlinVersion: String?,
    val javaVersion: String,
    val javaVendor: String,
    val osName: String,
    val osVersion: String,
    val architecture: String,
    val maxMemoryMiB: Long,
    val allocatedMemoryMiB: Long,
    val window: String,
    val worldLoaded: Boolean,
    val currentScreen: String?,
    val inSkyblock: Boolean,
    val area: String?,
    val subarea: String?,
    val enabledModules: List<String>,
    val loadedMods: List<DiagnosticMod>,
)

internal data class DiagnosticMod(val id: String, val version: String)
