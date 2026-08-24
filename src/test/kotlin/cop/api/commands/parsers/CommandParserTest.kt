package cop.api.commands.parsers

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import cop.api.commands.internal.GreedyString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandParserTest {
    class Recorder {
        var received: Pair<Int, String?>? = null

        fun valid(required: Int, optional: String?) {
            received = required to optional
        }

        @Suppress("UNUSED_PARAMETER")
        fun invalid(optional: String?, required: Int) = Unit
    }

    @Test
    fun `maps supported Kotlin types to Brigadier argument types`() {
        assertIs<IntegerArgumentType>(TypeParser.getBrigadierType(Int::class.java))
        assertIs<IntegerArgumentType>(TypeParser.getBrigadierType(Int::class.javaObjectType))
        assertIs<DoubleArgumentType>(TypeParser.getBrigadierType(Double::class.java))
        assertIs<FloatArgumentType>(TypeParser.getBrigadierType(Float::class.java))
        assertIs<BoolArgumentType>(TypeParser.getBrigadierType(Boolean::class.java))

        val string = assertIs<StringArgumentType>(TypeParser.getBrigadierType(String::class.java))
        val greedy = assertIs<StringArgumentType>(TypeParser.getBrigadierType(GreedyString::class.java))
        assertEquals(StringArgumentType.StringType.QUOTABLE_PHRASE, string.type)
        assertEquals(StringArgumentType.StringType.GREEDY_PHRASE, greedy.type)
    }

    @Test
    fun `rejects unsupported command argument types`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TypeParser.getBrigadierType(Long::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Unsupported command argument type"))
    }

    @Test
    fun `reflects and executes a command handler`() {
        val recorder = Recorder()
        val parser = ArgumentParser(recorder::valid)

        assertEquals(listOf("required", "optional"), parser.arguments.map { it.name })
        assertEquals(listOf(Int::class.java, String::class.java), parser.arguments.map { it.type })
        assertFalse(parser.arguments[0].isOptional)
        assertTrue(parser.arguments[1].isOptional)

        parser.execute(arrayOf<Any?>(7, "ready"))
        assertEquals(7 to "ready", recorder.received)
    }

    @Test
    fun `rejects required arguments after optional arguments`() {
        val recorder = Recorder()

        val error = assertFailsWith<IllegalArgumentException> {
            ArgumentParser(recorder::invalid)
        }

        assertTrue(error.message.orEmpty().contains("reqd args can't follow optional args"))
    }
}
