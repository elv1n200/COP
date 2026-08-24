package cop.utils

import java.util.regex.Pattern

/**
 * Guardrails for regular expressions supplied through config files or UI.
 *
 * Java's backtracking regex engine has no reliable in-process timeout. These
 * checks therefore reject the common catastrophic shapes up front, cap input
 * and output sizes, and turn syntax/replacement errors into a harmless miss.
 * This is intentionally conservative; built-in, developer-owned patterns do
 * not need to go through this helper.
 */
object UserRegex {
    const val MAX_PATTERN_CHARS = 512
    const val MAX_INPUT_CHARS = 8 * 1024
    const val MAX_REPLACEMENT_CHARS = 1024
    const val MAX_OUTPUT_CHARS = 16 * 1024
    private const val MAX_REPEAT_COUNT = 10_000

    fun compile(pattern: String, options: Set<RegexOption> = emptySet()): Regex? {
        if (!isAcceptable(pattern)) return null
        return try {
            Regex(pattern, options)
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    @JvmStatic
    fun compilePattern(pattern: String, flags: Int = 0): Pattern? {
        if (!isAcceptable(pattern)) return null
        return try {
            Pattern.compile(pattern, flags)
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    fun find(regex: Regex, input: String): MatchResult? {
        if (input.length > MAX_INPUT_CHARS) return null
        return try {
            regex.find(input)
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    @JvmStatic
    fun containsMatch(pattern: Pattern, input: String): Boolean {
        if (input.length > MAX_INPUT_CHARS) return false
        return try {
            pattern.matcher(input).find()
        } catch (_: RuntimeException) {
            false
        } catch (_: StackOverflowError) {
            false
        }
    }

    /** Regex replacement with Java-compatible `$1`/`${name}` expansion. */
    fun replace(regex: Regex, input: String, replacement: String): String? {
        if (input.length > MAX_INPUT_CHARS || replacement.length > MAX_REPLACEMENT_CHARS) return null
        return try {
            val matcher = regex.toPattern().matcher(input)
            val output = StringBuffer(minOf(input.length + 64, MAX_OUTPUT_CHARS))
            while (matcher.find()) {
                matcher.appendReplacement(output, replacement)
                if (output.length > MAX_OUTPUT_CHARS) return null
            }
            matcher.appendTail(output)
            output.toString().takeIf { it.length <= MAX_OUTPUT_CHARS }
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    /** Literal replacement that cannot explode output through an empty target. */
    fun replaceLiteral(input: String, target: String, replacement: String, ignoreCase: Boolean): String? {
        if (input.length > MAX_INPUT_CHARS || target.isEmpty() ||
            target.length > MAX_PATTERN_CHARS || replacement.length > MAX_REPLACEMENT_CHARS) return null

        val first = input.indexOf(target, startIndex = 0, ignoreCase = ignoreCase)
        if (first < 0) return input

        val output = StringBuilder(minOf(input.length + replacement.length, MAX_OUTPUT_CHARS))
        var consumed = 0
        var matchAt = first
        while (matchAt >= 0) {
            val added = matchAt - consumed + replacement.length
            if (output.length + added > MAX_OUTPUT_CHARS) return null
            output.append(input, consumed, matchAt)
            output.append(replacement)
            consumed = matchAt + target.length
            matchAt = input.indexOf(target, startIndex = consumed, ignoreCase = ignoreCase)
        }
        if (output.length + input.length - consumed > MAX_OUTPUT_CHARS) return null
        output.append(input, consumed, input.length)
        return output.toString()
    }

    @JvmStatic
    fun isAcceptable(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_CHARS || pattern.indexOf('\u0000') >= 0) return false
        return !hasObviousBacktrackingRisk(pattern)
    }

    private data class GroupFrame(
        val contentStart: Int,
        val atomic: Boolean,
        var containsVariableRepeat: Boolean = false,
    )

    private data class Atom(
        val groupContent: String? = null,
        val containsVariableRepeat: Boolean = false,
        val atomic: Boolean = false,
    )

    private data class BraceQuantifier(
        val endIndex: Int,
        val variable: Boolean,
        val repeatsAtom: Boolean,
        val tooLarge: Boolean,
    )

    private fun hasObviousBacktrackingRisk(pattern: String): Boolean {
        val groups = ArrayDeque<GroupFrame>()
        var lastAtom: Atom? = null
        var inClass = false
        var escaped = false
        var index = 0

        while (index < pattern.length) {
            val ch = pattern[index]
            if (escaped) {
                escaped = false
                lastAtom = Atom()
                index++
                continue
            }
            if (ch == '\\') {
                escaped = true
                index++
                continue
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false
                    lastAtom = Atom()
                }
                index++
                continue
            }
            if (ch == '[') {
                inClass = true
                index++
                continue
            }

            when (ch) {
                '(' -> {
                    val prefixLength = groupPrefixLength(pattern, index)
                    groups.addLast(
                        GroupFrame(
                            contentStart = index + 1 + prefixLength,
                            atomic = pattern.startsWith("(?>", index),
                        )
                    )
                    lastAtom = null
                }

                ')' -> {
                    val frame = groups.removeLastOrNull()
                    if (frame != null) {
                        groups.lastOrNull()?.let {
                            it.containsVariableRepeat = it.containsVariableRepeat ||
                                (frame.containsVariableRepeat && !frame.atomic)
                        }
                        lastAtom = Atom(
                            groupContent = pattern.substring(frame.contentStart.coerceAtMost(index), index),
                            containsVariableRepeat = frame.containsVariableRepeat && !frame.atomic,
                            atomic = frame.atomic,
                        )
                    } else {
                        lastAtom = null
                    }
                }

                '*', '+' -> {
                    val possessive = pattern.getOrNull(index + 1) == '+'
                    val atom = lastAtom
                    if (!possessive && atom != null && !atom.atomic) {
                        if (atom.containsVariableRepeat) return true
                        if (atom.groupContent?.let(::hasAmbiguousAlternation) == true) return true
                        groups.lastOrNull()?.containsVariableRepeat = true
                    }
                    if (possessive) index++ // consume the quantifier suffix
                }

                '?' -> {
                    val previous = pattern.getOrNull(index - 1)
                    val isGroupPrefix = previous == '('
                    val isQuantifierSuffix = previous == '*' || previous == '+' ||
                        previous == '?' || previous == '}'
                    if (!isGroupPrefix && !isQuantifierSuffix) {
                        val possessive = pattern.getOrNull(index + 1) == '+'
                        val atom = lastAtom
                        if (!possessive && atom != null && !atom.atomic) {
                            groups.lastOrNull()?.containsVariableRepeat = true
                        }
                        if (possessive) index++
                    }
                }

                '{' -> {
                    val quantifier = parseBraceQuantifier(pattern, index)
                    if (quantifier != null) {
                        if (quantifier.tooLarge) return true
                        val possessive = pattern.getOrNull(quantifier.endIndex + 1) == '+'
                        val atom = lastAtom
                        if (quantifier.repeatsAtom && !possessive && atom != null && !atom.atomic) {
                            if (atom.containsVariableRepeat) return true
                            if (atom.groupContent?.let(::hasAmbiguousAlternation) == true) return true
                        }
                        if (quantifier.variable && !possessive && atom != null && !atom.atomic) {
                            groups.lastOrNull()?.containsVariableRepeat = true
                        }
                        index = quantifier.endIndex + if (possessive) 1 else 0
                    } else {
                        lastAtom = Atom()
                    }
                }

                '|', '^', '$' -> lastAtom = null
                else -> lastAtom = Atom()
            }
            index++
        }
        return false
    }

    private fun groupPrefixLength(pattern: String, openIndex: Int): Int {
        if (pattern.getOrNull(openIndex + 1) != '?') return 0
        if (pattern.startsWith("(?<=", openIndex) || pattern.startsWith("(?<!", openIndex)) return 3
        if (pattern.startsWith("(?:", openIndex) || pattern.startsWith("(?=", openIndex) ||
            pattern.startsWith("(?!", openIndex) || pattern.startsWith("(?>", openIndex)) return 2
        if (pattern.startsWith("(?<", openIndex)) {
            val end = pattern.indexOf('>', startIndex = openIndex + 3)
            if (end >= 0) return end - openIndex
        }
        val colon = pattern.indexOf(':', startIndex = openIndex + 2)
        if (colon >= 0) {
            val flags = pattern.substring(openIndex + 2, colon)
            if (flags.isNotEmpty() && flags.all { it.isLetter() || it == '-' }) return colon - openIndex
        }
        return 0
    }

    private fun parseBraceQuantifier(pattern: String, start: Int): BraceQuantifier? {
        val end = pattern.indexOf('}', startIndex = start + 1)
        if (end < 0 || end - start > 24) return null
        val body = pattern.substring(start + 1, end)
        val comma = body.indexOf(',')
        return if (comma < 0) {
            val count = body.toIntOrNull() ?: return null
            BraceQuantifier(
                endIndex = end,
                variable = false,
                repeatsAtom = count > 1,
                tooLarge = count > MAX_REPEAT_COUNT,
            )
        } else {
            if (body.indexOf(',', startIndex = comma + 1) >= 0) return null
            val min = body.substring(0, comma).toIntOrNull() ?: return null
            val maxText = body.substring(comma + 1)
            val max = maxText.toIntOrNull()
            if (maxText.isNotEmpty() && max == null) return null
            BraceQuantifier(
                endIndex = end,
                variable = maxText.isEmpty() || max != min,
                repeatsAtom = max == null || max > 1,
                tooLarge = min > MAX_REPEAT_COUNT || (max ?: 0) > MAX_REPEAT_COUNT,
            )
        }
    }

    private fun hasAmbiguousAlternation(content: String): Boolean {
        val alternatives = splitTopLevelAlternatives(content)
        if (alternatives.size < 2) return false
        if (alternatives.any { it.isEmpty() }) return true

        val literal = alternatives.map(::literalAlternative)
        for (left in literal.indices) {
            for (right in left + 1 until literal.size) {
                val a = literal[left]
                val b = literal[right]
                if (a != null && b != null && (a.startsWith(b) || b.startsWith(a))) return true

                val aPrefix = literalPrefix(alternatives[left])
                val bPrefix = literalPrefix(alternatives[right])
                if (aPrefix.isEmpty() || bPrefix.isEmpty() ||
                    aPrefix.startsWith(bPrefix) || bPrefix.startsWith(aPrefix)) return true
            }
        }
        return false
    }

    private fun splitTopLevelAlternatives(content: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inClass = false
        var escaped = false
        var start = 0
        for (index in content.indices) {
            val ch = content[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (inClass) {
                if (ch == ']') inClass = false
                continue
            }
            when (ch) {
                '[' -> inClass = true
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                '|' -> if (depth == 0) {
                    result += content.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (result.isEmpty()) return emptyList()
        result += content.substring(start)
        return result
    }

    private fun literalAlternative(alternative: String): String? {
        val result = StringBuilder(alternative.length)
        var escaped = false
        for (ch in alternative) {
            if (escaped) {
                if (ch.isLetterOrDigit()) return null
                result.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch in ".^$*+?{[()") return null
            result.append(ch)
        }
        return if (escaped) null else result.toString()
    }

    private fun literalPrefix(alternative: String): String {
        val result = StringBuilder()
        var escaped = false
        for (ch in alternative) {
            if (escaped) {
                if (ch.isLetterOrDigit()) break
                result.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch in ".^$*+?{[()") break
            result.append(ch)
        }
        return result.toString()
    }
}
