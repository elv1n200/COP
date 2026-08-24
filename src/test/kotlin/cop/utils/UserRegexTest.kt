package cop.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserRegexTest {
    @Test
    fun `invalid patterns are rejected without throwing`() {
        assertNull(UserRegex.compile("["))
        assertNull(UserRegex.compilePattern("(?<unfinished"))
    }

    @Test
    fun `obvious catastrophic backtracking shapes are rejected`() {
        assertNull(UserRegex.compile("(a+)+$"))
        assertNull(UserRegex.compile("(a|aa)+$"))
        assertNull(UserRegex.compile("(a?)+$"))
        assertNull(UserRegex.compile("(a{1,3})+$"))
    }

    @Test
    fun `ordinary groups and replacement expansion keep Java semantics`() {
        val regex = assertNotNull(UserRegex.compile("(?<word>foo|bar)-(\\d+)"))

        assertEquals(
            "foo:42",
            UserRegex.replace(regex, "foo-42", "${'$'}{word}:${'$'}2"),
        )
        assertNotNull(UserRegex.compile("(a++)+"), "possessive quantifiers must not be mistaken for nesting")
    }

    @Test
    fun `oversized inputs are not evaluated`() {
        val kotlinRegex = assertNotNull(UserRegex.compile("a"))
        val javaPattern = assertNotNull(UserRegex.compilePattern("a"))
        val oversized = "a".repeat(UserRegex.MAX_INPUT_CHARS + 1)

        assertNull(UserRegex.find(kotlinRegex, oversized))
        assertFalse(UserRegex.containsMatch(javaPattern, oversized))
        assertNull(UserRegex.replace(kotlinRegex, oversized, "b"))
    }

    @Test
    fun `replacement output amplification is bounded`() {
        val regex = assertNotNull(UserRegex.compile("a"))
        val input = "a".repeat(UserRegex.MAX_INPUT_CHARS)

        assertNull(UserRegex.replace(regex, input, "bbb"))
        assertNull(UserRegex.replaceLiteral(input, "a", "bbb", ignoreCase = false))
    }
}
