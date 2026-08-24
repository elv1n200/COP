package cop.module.impl.misc.automation

import cop.api.commands.internal.GreedyString
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.SkyblockPlayer
import cop.config.configList
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.command
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.PartyUtils

/**
 * Fail-open party rules: COP only sends a kick while the local player is
 * already confirmed as leader. It never asks another mod/player for transfer.
 */
object PartyAutoKick : Module(
    "Party Auto Kick",
    desc = "Kicks explicitly listed users or selected Party Finder classes while you are party leader.",
) {
    private val classHeader by text("Party Finder class rules")
    private val kickHealer by switch("Kick Healer", false).childOf(::classHeader)
    private val kickMage by switch("Kick Mage", false).childOf(::classHeader)
    private val kickBerserk by switch("Kick Berserk", false).childOf(::classHeader)
    private val kickArcher by switch("Kick Archer", false).childOf(::classHeader)
    private val kickTank by switch("Kick Tank", false).childOf(::classHeader)
    private val kickBelowLevel by switch(
        "Minimum class level",
        false,
        desc = "Kicks Party Finder joins below the configured class level.",
    ).childOf(::classHeader)
    private val minimumLevel by slider("Required level", 30, 1, 60, 1).childOf(::kickBelowLevel)

    private val kickSkyblockerMessages by switch(
        "Kick Skyblocker tag",
        false,
        desc = "Kicks a party member who sends a message beginning with [Skyblocker].",
    )
    private val kickDelay by slider("Kick delay", 10, 2, 60, 1, unit = "t")
    private val customNames by configList<String>("party_auto_kick_names.json")
    private val pending = linkedMapOf<String, PendingKick>()
    private var tickCounter = 0L
    private var nextCommandTick = 0L

    init {
        val autoKick = command.sub("autokick").description("Manages Party Auto Kick's explicit name list.")
        autoKick.sub("add") { name: GreedyString -> addName(name.string) }
        autoKick.sub("remove") { name: GreedyString -> removeName(name.string) }
            .suggests { customNames.toList() }
        autoKick.sub("list") { listNames() }
        autoKick.sub("clear") {
            val count = customNames.size
            customNames.clear()
            modMessage("&aCleared &f$count&a Party Auto Kick names.")
        }

        on<ChatEvent.PacketClient> {
            val clean = message.noControlCodes

            PARTY_FINDER_JOIN.find(clean)?.let { match ->
                val name = match.groupValues[1]
                val clazz = match.groupValues[2]
                val level = match.groupValues[3].toIntOrNull() ?: return@let
                if (shouldKickClass(clazz) || kickBelowLevel && level < minimumLevel || isCustomName(name)) {
                    requestKick(name)
                }
                return@on
            }

            PARTY_JOIN.find(clean)?.groupValues?.getOrNull(1)?.let { name ->
                if (isCustomName(name)) requestKick(name)
                return@on
            }

            if (kickSkyblockerMessages) {
                PARTY_CHAT.find(clean)?.let { match ->
                    if (match.groupValues[2].startsWith("[Skyblocker] ")) requestKick(match.groupValues[1])
                }
            }
        }

        on<TickEvent.End> {
            tickCounter++
            if (pending.isEmpty()) return@on
            pending.entries.removeIf { it.value.expiresAt <= tickCounter }
            if (tickCounter < nextCommandTick) return@on
            val due = pending.entries.firstOrNull { it.value.dueAt <= tickCounter } ?: return@on

            val name = due.key
            if (!enabled) return@on
            if (name.equals(player.gameProfile.name, true) || PartyUtils.members.none { it.equals(name, true) }) {
                pending.remove(name)
                return@on
            }
            if (!PartyUtils.isLeader() || !SkyblockPlayer.canUseCommands) {
                due.value.dueAt = tickCounter + RETRY_DELAY_TICKS
                return@on
            }

            command("party kick $name")
            pending.remove(name)
            nextCommandTick = tickCounter + COMMAND_DELAY_TICKS
        }

        on<WorldEvent.Change> {
            pending.clear()
            tickCounter = 0L
            nextCommandTick = 0L
        }
    }

    override fun onDisable() {
        pending.clear()
        nextCommandTick = 0L
        super.onDisable()
    }

    private fun requestKick(name: String) {
        val clean = name.trim().takeIf(NAME::matches) ?: return
        if (clean.equals(mc.player?.gameProfile?.name, true)) return
        if (pending.keys.any { it.equals(clean, true) }) return
        val dueAt = tickCounter + kickDelay.toLong()
        pending[clean] = PendingKick(dueAt, dueAt + PENDING_TTL_TICKS)
    }

    private fun shouldKickClass(clazz: String): Boolean = when (clazz.lowercase()) {
        "healer" -> kickHealer
        "mage" -> kickMage
        "berserk" -> kickBerserk
        "archer" -> kickArcher
        "tank" -> kickTank
        else -> false
    }

    private fun addName(raw: String) {
        val name = raw.trim()
        if (!NAME.matches(name)) return modMessage("&cMinecraft names must contain 1-16 letters, numbers or underscores.")
        if (isCustomName(name)) return modMessage("&e$name &7is already listed.")
        customNames.add(name)
        modMessage("&aAdded &f$name&a to Party Auto Kick.")
    }

    private fun removeName(raw: String) {
        val name = raw.trim()
        val matches = customNames.filter { it.equals(name, true) }
        if (matches.isEmpty()) return modMessage("&e$name &7is not listed.")
        customNames.removeAll(matches)
        modMessage("&aRemoved &f$name&a from Party Auto Kick.")
    }

    private fun listNames() {
        if (customNames.isEmpty()) return modMessage("&cParty Auto Kick's name list is empty.")
        modMessage(customNames.sortedBy(String::lowercase).joinToString("\n") { "&7- &f$it" })
    }

    private fun isCustomName(name: String): Boolean = customNames.any { it.equals(name, true) }

    private val NAME = Regex("[A-Za-z0-9_]{1,16}")
    private val PARTY_FINDER_JOIN = Regex("^Party Finder > (\\w{1,16}) joined the dungeon group! \\((\\w+) Level (\\d+)\\)$")
    private val PARTY_JOIN = Regex("^(?:\\[[^]]*?] ?)?(\\w{1,16}) joined the party\\.$")
    private val PARTY_CHAT = Regex("^Party > (?:\\[[^]]*?] ?)?(\\w{1,16}): (.+)$")

    private data class PendingKick(var dueAt: Long, val expiresAt: Long)

    private const val RETRY_DELAY_TICKS = 5L
    private const val COMMAND_DELAY_TICKS = 10L
    private const val PENDING_TTL_TICKS = 200L
}
