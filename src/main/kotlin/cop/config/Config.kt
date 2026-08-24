package cop.config

import cop.CopMod.logger
import cop.CopMod.mc
import cop.api.events.core.EventBus
import cop.api.events.GameEvent
import cop.module.ModuleManager
import cop.module.settings.Saving
import com.google.gson.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/** Parse and validate only the document-level shape of the module config. */
internal fun parseModuleConfigRoot(text: String): JsonArray {
    val root = JsonParser.parseString(text)
    require(root.isJsonArray) { "COP config root is not an array" }
    return root.asJsonArray
}

/**
 * Apply each module entry independently. A malformed entry or a failure from
 * setting restoration/onEnable is local to that entry and cannot abort later
 * modules from an otherwise valid JSON document.
 */
internal fun applyModuleConfigEntries(
    modules: JsonArray,
    applyModule: (JsonObject) -> Unit,
    onFailure: (index: Int, error: Throwable) -> Unit,
) {
    for ((index, element) in modules.withIndex()) {
        if (!element.isJsonObject) {
            onFailure(index, IllegalArgumentException("Module entry is not an object"))
            continue
        }

        runCatching { applyModule(element.asJsonObject) }
            .onFailure { onFailure(index, it) }
    }
}

/** Apply both current object-shaped and legacy array-shaped settings safely. */
internal fun applyModuleConfigSettings(
    settings: JsonElement?,
    applySetting: (key: String, value: JsonElement) -> Unit,
    onFailure: (key: String, error: Throwable) -> Unit,
) {
    fun apply(key: String, value: JsonElement) {
        runCatching { applySetting(key, value) }
            .onFailure { onFailure(key, it) }
    }

    when {
        settings == null -> Unit
        settings.isJsonArray -> settings.asJsonArray.forEach { element ->
            val entry = element.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.entrySet()
                ?.firstOrNull()
                ?: return@forEach
            apply(entry.key, entry.value)
        }

        settings.isJsonObject -> settings.asJsonObject.entrySet().forEach { (key, value) ->
            apply(key, value)
        }
    }
}

/**
 * This class handles loading and saving Modules and their settings.
 *
 * @author Stivais
 */
object Config {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveScheduled = AtomicBoolean(false)

    private val configFile = File(mc.gameDirectory, "config/cop/cop-config.json").apply {
        try {
            parentFile?.mkdirs()
            createNewFile()
        } catch (e: Exception) {
            println("Error initializing module config\n${e.message}")
            logger.error("Error initializing module config", e)
        }
    }

    init {
        EventBus.on<GameEvent.Unload> { save() }
    }

    fun load() {
        val text = try {
            configFile.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // A read failure does not prove that the bytes on disk are corrupt
            // (permissions and transient I/O errors are common examples), so
            // do not create a misleading recovery copy here.
            println("Error reading config.\n${e.message}")
            logger.error("Error reading module config", e)
            return
        }
        if (text.isBlank()) return

        val modules = try {
            parseModuleConfigRoot(text)
        } catch (e: Exception) {
            // Recovery is intentionally limited to document parsing/root
            // validation. Failures while applying a valid document belong to
            // one entry and must never label the whole file as corrupt.
            ConfigRecovery.backup(configFile)
            println("Error parsing config.\n${e.message}")
            logger.error("Error parsing module config", e)
            return
        }

        applyModuleConfigEntries(
            modules = modules,
            applyModule = ::loadModule,
            onFailure = { index, error ->
                logger.warn("Skipping invalid COP config module entry at index $index", error)
            },
        )
    }

    /** Load settings before enabling the module so onEnable observes restored values. */
    private fun loadModule(moduleObj: JsonObject) {
        val name = moduleObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val module = ModuleManager.getModuleByName(name) ?: return

        applyModuleConfigSettings(
            settings = moduleObj.get("settings"),
            applySetting = { key, value ->
                val setting = module.getSettingByName(key) as? Saving
                if (setting != null) setting.read(value)
            },
            onFailure = { key, error ->
                logger.warn("Ignoring invalid setting '$key' for module '$name'", error)
            },
        )

        val enabled = moduleObj.get("enabled")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
        if (enabled != null && enabled != module.enabled) module.toggle()
    }

    fun save() {
        // Packet-derived events can request a save from Netty's I/O thread.
        // Serialize all module/setting snapshots on Minecraft's client thread;
        // the atomic replace below then protects only the disk commit itself.
        if (!mc.isSameThread) {
            if (saveScheduled.compareAndSet(false, true)) {
                try {
                    mc.execute {
                        saveScheduled.set(false)
                        save()
                    }
                } catch (e: Exception) {
                    saveScheduled.set(false)
                    logger.error("Failed to schedule module config save", e)
                }
            }
            return
        }

        try {
            // reason doing this is better is that
            // using like a custom serializer leaves 'null' in settings that don't save
            // code looks hideous tho, but it fully works
            val jsonArray = JsonArray().apply {
                for (module in ModuleManager.modules) {
                    add(JsonObject().apply {
                        add("name", JsonPrimitive(module.name))
                        add("enabled", JsonPrimitive(module.enabled))
                        add("settings", JsonObject().apply {
                            for (setting in module.settings) {
                                if (setting is Saving) add(setting.jsonName, setting.write())
                            }
                        })
                    })
                }
            }
            writeAtomically(gson.toJson(jsonArray))
        } catch (e: Exception) {
            println("Error saving config.\n${e.message}")
            logger.error("Error saving config.", e)
        }
    }

    private fun writeAtomically(contents: String) {
        val target = configFile.toPath()
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${configFile.name}.", ".tmp")
        try {
            Files.writeString(temp, contents, StandardCharsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
