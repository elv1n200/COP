package cop.config

import cop.api.customtriggers.actions.TriggerAction
import cop.api.customtriggers.conditions.TriggerCondition
import com.google.gson.*
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import cop.utils.ChatUtils
import cop.utils.ChatUtils.modMessage
import cop.CopMod.logger
import cop.CopMod.mc
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

// https://github.com/Noamm9/CatgirlAddons/blob/main/src/main/kotlin/catgirlroutes/utils/ConfigSystem.kt
val configPath: File by lazy {
    val target = File(mc.gameDirectory, "config/cop").absoluteFile
    val legacy = File("config/cop").absoluteFile
    val migrationMarker = File(target, ".legacy-cwd-migration-v1")
    target.mkdirs()

    // Older builds resolved this path against the launcher's working directory.
    // Copy missing legacy files into the canonical game directory once, without
    // overwriting newer data or deleting the recoverable legacy copy.
    if (!migrationMarker.exists()) {
        val migration = runCatching {
            if (legacy.toPath().normalize() != target.toPath().normalize() && legacy.isDirectory) {
                legacy.walkTopDown().forEach { source ->
                    val destination = File(target, source.relativeTo(legacy).path)
                    if (source.isDirectory) destination.mkdirs()
                    else if (!destination.exists()) source.copyTo(destination)
                }
            }
        }
        migration.onSuccess {
            runCatching { migrationMarker.createNewFile() }
                .onFailure { logger.warn("Failed to mark legacy COP config migration complete", it) }
        }.onFailure { logger.warn("Failed to migrate legacy COP config directory", it) }
    }
    target
}
object ConfigSystem {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(typeAdapter<TriggerAction>())
        .registerTypeAdapterFactory(typeAdapter<TriggerCondition>())
        .setPrettyPrinting()
        .create()

    inline fun <reified T : Any> load(file: File, default: () -> T): T {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            val initial = default()
            save(file, initial)
            return initial
        }
        return try {
            file.reader().use { gson.fromJson(it, object : TypeToken<T>() {}.type) } ?: default()
        } catch (e: Exception) {
            ConfigRecovery.backup(file)
            logger.warn("Failed to load config file '${file.name}'; using defaults", e)
            default()
        }
    }

    fun save(file: File, data: Any) = runCatching {
        val target = file.toPath()
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${file.name}.", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { gson.toJson(data, it) }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }.onFailure {
        modMessage(
            ChatUtils.button(
                "&cError occurred while saving the config ${file.name} &7(click to copy)",
                command = "/copdev copy ${it.stackTraceToString()}",
                hoverText = "Click to copy"
            )
        )
        it.printStackTrace()
    }
}

class ConfigList<E>(
    private val list: MutableList<E>,
    private val onMutation: () -> Unit
) : AbstractMutableList<E>() {

    fun save() = onMutation()

    override val size: Int get() = list.size
    override fun get(index: Int): E = list[index]

    private inline fun <R> modify(block: () -> R): R {
        val result = block()
        onMutation()
        return result
    }

    override fun add(index: Int, element: E)    = modify { list.add(index, element) }
    override fun removeAt(index: Int): E        = modify { list.removeAt(index) }
    override fun set(index: Int, element: E): E = modify { list.set(index, element) }

    override fun addAll(index: Int, elements: Collection<E>) = modify { list.addAll(index, elements) }
    override fun addAll(elements: Collection<E>)             = modify { list.addAll(elements) }
    override fun removeAll(elements: Collection<E>)          = modify { list.removeAll(elements) }
    override fun retainAll(elements: Collection<E>)          = modify { list.retainAll(elements) }
    override fun clear()                                     = modify { list.clear() }
}

inline fun <reified T : Any> configList(name: String): ReadOnlyProperty<Any?, ConfigList<T>> {
    val file = File(configPath, name)
    val loaded = ConfigSystem.load<MutableList<T>>(file) { mutableListOf() }

    val wrapper = ConfigList(loaded) {
        ConfigSystem.save(file, loaded)
    }

    return ReadOnlyProperty { _, _ -> wrapper }
}

class ConfigMap<K, V>(
    private val map: MutableMap<K, V>,
    private val onMutation: () -> Unit
) : AbstractMutableMap<K, V>() {
    fun save() = onMutation()

    override val size: Int get() = map.size
    override val entries get() = map.entries

    private inline fun <R> modify(block: () -> R): R {
        val result = block()
        onMutation()
        return result
    }

    override fun put(key: K, value: V): V? = modify { map.put(key, value) }
    override fun remove(key: K): V? = modify { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = modify { map.putAll(from) }
    override fun clear() = modify { map.clear() }
}

inline fun <reified K : Any, reified V : Any> configMap(name: String): ReadOnlyProperty<Any?, ConfigMap<K, V>> {
    val file = File(configPath, name)
    val loaded = ConfigSystem.load<MutableMap<K, V>>(file) { mutableMapOf() }

    val wrapper = ConfigMap(loaded) {
        ConfigSystem.save(file, loaded)
    }

    return ReadOnlyProperty { _, _ -> wrapper }
}


interface TypeNamed

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TypeName(val value: String)

inline fun <reified T> typeAdapter(
    discriminator: String = "type"
): TypeAdapterFactory where T : Any, T : TypeNamed {
    if (!T::class.isSealed) {
        return object : TypeAdapterFactory {
            override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? = null
        }
    }

    val subtypesByName = T::class.sealedSubclasses.associate { subclass ->
        val typeName = subclass.java.getAnnotation(TypeName::class.java)?.value
            ?: error("seally subclass ${subclass.simpleName} gotta be annotated with @TypeName")
        typeName to subclass.java
    }

    return object : TypeAdapterFactory {
        override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
            if (type.rawType != T::class.java) return null

            return object : TypeAdapter<R>() {
                override fun write(out: JsonWriter, value: R) {
                    val json = gson.toJsonTree(value).asJsonObject
                    json.addProperty(discriminator, (value as TypeNamed).typeName)
                    Streams.write(json, out)
                }

                override fun read(reader: JsonReader): R {
                    val json = Streams.parse(reader).asJsonObject
                    val label = json.get(discriminator)?.asString
                        ?: error("no discriminator '$discriminator'")
                    val javaType = subtypesByName[label]
                        ?: error("unknow type label: $label")
                    @Suppress("UNCHECKED_CAST")
                    return gson.fromJson(json, javaType) as R
                }
            }.nullSafe()
        }
    }
}

inline fun <reified T> typedEntries(): List<Pair<String, () -> T>> where T : Any, T : TypeNamed {
    return T::class.sealedSubclasses
        .filter { !it.isAbstract }
        .map { kClass ->
            val name = kClass.findAnnotation<TypeName>()?.value ?: error("this should never happen")

            val instance = {
                runCatching {
                    kClass.createInstance()
                }.getOrNull() ?: error("no good. ensure all constructors have default params (x: Int = 0, etc)")
            }

            name to instance
        }
}

val TypeNamed.typeName: String
    get() = this::class.java.getAnnotation(TypeName::class.java)?.value
        ?: error("class ${this::class.simpleName} must be annotated with @TypeName")
