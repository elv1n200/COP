package cop.config

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigLoadPipelineTest {
    @Test
    fun `root validation accepts arrays and rejects invalid documents`() {
        assertEquals(2, parseModuleConfigRoot("""[{"name":"one"},{"name":"two"}]""").size())

        assertFailsWith<IllegalArgumentException> {
            parseModuleConfigRoot("""{"name":"not-an-array"}""")
        }
        assertFailsWith<JsonSyntaxException> {
            parseModuleConfigRoot("[{broken-json")
        }
    }

    @Test
    fun `module application failure does not stop later entries`() {
        val modules = parseModuleConfigRoot(
            """[{"name":"first"},{"name":"broken-on-enable"},{"name":"last"}]""",
        )
        val applied = mutableListOf<String>()
        val failures = mutableListOf<Pair<Int, String>>()

        applyModuleConfigEntries(
            modules = modules,
            applyModule = { module ->
                val name = module.get("name").asString
                if (name == "broken-on-enable") error("simulated onEnable failure")
                applied += name
            },
            onFailure = { index, error -> failures += index to error.message.orEmpty() },
        )

        assertEquals(listOf("first", "last"), applied)
        assertEquals(listOf(1 to "simulated onEnable failure"), failures)
    }

    @Test
    fun `malformed module entry does not stop later entries`() {
        val modules = parseModuleConfigRoot("""[{"name":"first"},42,{"name":"last"}]""")
        val applied = mutableListOf<String>()
        val failedIndexes = mutableListOf<Int>()

        applyModuleConfigEntries(
            modules = modules,
            applyModule = { applied += it.get("name").asString },
            onFailure = { index, _ -> failedIndexes += index },
        )

        assertEquals(listOf("first", "last"), applied)
        assertEquals(listOf(1), failedIndexes)
    }

    @Test
    fun `setting application failure does not stop remaining settings`() {
        val settings = JsonParser.parseString("""{"bad":1,"good":2}""")
        val applied = mutableListOf<String>()
        val failures = mutableListOf<String>()

        applyModuleConfigSettings(
            settings = settings,
            applySetting = { key, _ ->
                if (key == "bad") error("invalid setting")
                applied += key
            },
            onFailure = { key, _ -> failures += key },
        )

        assertEquals(listOf("good"), applied)
        assertEquals(listOf("bad"), failures)
    }
}
