package cop.module.impl.misc

import cop.api.events.ChatEvent
import cop.config.ConfigList
import cop.config.configList
import cop.module.Module
import cop.utils.ChatUtils.literal
import cop.utils.ChatUtils.modMessage
import cop.utils.UserRegex

/**
 * Find/replace pass over incoming chat messages — useful for renaming nicks,
 * shortening verbose Hypixel prefixes, or replacing trigger phrases with
 * something easier to spot at a glance.
 *
 * Replacements live in `config/cop/visual_words.json` as an ordered list and
 * can be edited with the `/cop visualwords add|remove|list|clear` commands.
 * They apply on `ChatEvent.Receive` — the original message is cancelled and
 * a rewritten copy goes through `modMessage` so other COP listeners still see
 * the unmodified version on `Receive` (their cancel still wins; we only
 * intercept after no other module has cancelled).
 *
 * @author elvin
 */
object VisualWords : Module(
    "Visual Words",
    desc = "Replaces matching strings in incoming chat messages.",
) {
    private val regexMode by switch(
        "Regex mode", false,
        desc = "Treat the `match` field of every entry as a regular expression. " +
                "When off, plain text is used — case-insensitive substring match.",
    )
    private val caseSensitive by switch(
        "Case sensitive", false,
        desc = "Only matters when regex mode is off — when on, matches must agree on case.",
    )

    private val help by button(
        "Edit list", desc = "Print the chat command help for editing the replacement list.",
    ) {
        modMessage("&aVisual Words commands:")
        modMessage("  &7/cop visualwords add <match> <replacement>")
        modMessage("  &7/cop visualwords remove <match>")
        modMessage("  &7/cop visualwords list")
        modMessage("  &7/cop visualwords clear")
    }

    /** Pairs of (match-string, replacement-string), persisted to config. */
    val entries: ConfigList<Entry> by configList<Entry>("visual_words.json")

    data class Entry(val match: String, val replacement: String)

    init {
        on<ChatEvent.Receive> {
            if (entries.isEmpty()) return@on
            val rewritten = rewrite(message) ?: return@on
            cancel()
            // Re-emit through modMessage so the user sees the rewrite in chat.
            // We don't try to reconstruct the styled Component — the cost of
            // doing that perfectly across every Hypixel format is high enough
            // that plain coloured text is a fair tradeoff.
            modMessage(rewritten, prefix = literal(""))
        }
    }

    private fun rewrite(message: String): String? {
        var current: String = message
        var changed = false

        for (e in entries) {
            val before = current
            current = if (regexMode) {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                UserRegex.compile(e.match, opts)
                    ?.let { UserRegex.replace(it, current, e.replacement) }
                    ?: current
            } else {
                UserRegex.replaceLiteral(
                    input = current,
                    target = e.match,
                    replacement = e.replacement,
                    ignoreCase = !caseSensitive,
                ) ?: current
            }
            if (current != before) changed = true
        }
        return if (changed) current else null
    }
}
