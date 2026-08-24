package cop.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

private sealed interface ExampleConfig : TypeNamed

@TypeName("counter")
private data class CounterConfig(val value: Int) : ExampleConfig

@TypeName("label")
private data class LabelConfig(val value: String) : ExampleConfig

class ConfigSystemPureTest {
    @Test
    fun `config list notifies once per mutation`() {
        val backing = mutableListOf("alpha", "beta")
        var saves = 0
        val values = ConfigList(backing) { saves++ }

        assertEquals("alpha", values[0])
        assertEquals(0, saves)

        values.add("gamma")
        values[0] = "updated"
        values.removeAt(1)
        values.addAll(listOf("delta", "epsilon"))

        assertEquals(listOf("updated", "gamma", "delta", "epsilon"), backing)
        assertEquals(4, saves)

        values.save()
        assertEquals(5, saves)
    }

    @Test
    fun `config map notifies once per mutation`() {
        val backing = mutableMapOf("alpha" to 1)
        var saves = 0
        val values = ConfigMap(backing) { saves++ }

        assertEquals(1, values["alpha"])
        assertEquals(0, saves)

        values["beta"] = 2
        values.putAll(mapOf("gamma" to 3, "delta" to 4))
        values.remove("alpha")
        values.clear()

        assertEquals(emptyMap(), backing)
        assertEquals(4, saves)

        values.save()
        assertEquals(5, saves)
    }

    @Test
    fun `polymorphic config adapter round-trips its discriminator`() {
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(typeAdapter<ExampleConfig>())
            .create()
        val expected: ExampleConfig = CounterConfig(42)

        val encoded = gson.toJson(expected, ExampleConfig::class.java)
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals("counter", json.get("type").asString)
        assertEquals(expected, gson.fromJson(encoded, ExampleConfig::class.java))

        val second: ExampleConfig = LabelConfig("safe")
        val secondEncoded = gson.toJson(second, ExampleConfig::class.java)
        assertEquals(second, gson.fromJson(secondEncoded, ExampleConfig::class.java))
    }

    @Test
    fun `polymorphic config adapter rejects unknown discriminators`() {
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(typeAdapter<ExampleConfig>())
            .create()

        assertFails {
            gson.fromJson("""{"type":"missing","value":1}""", ExampleConfig::class.java)
        }
    }
}
