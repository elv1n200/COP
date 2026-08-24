package cop.module.impl.mining

import cop.api.abobaui.dsl.px
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.colour.Colour
import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.IslandArea
import cop.api.skyblock.Location.currentArea
import cop.module.Module
import cop.utils.StringUtils.toFixed
import cop.utils.WorldUtils
import cop.utils.skyblock.player.PlayerUtils

/**
 * Passive, local commission HUD based on Hypixel's tab list. Concept reference:
 * Quoi 26.1.x `CommissionDisplay`; parser, update cadence and UI are COP code.
 */
object CommissionDisplay : Module(
    "Commission Display",
    area = IslandArea.MiningIslands,
    desc = "Shows current mining commissions without keeping the tab list open.",
) {
    private val completionTitle by switch(
        "Completion title", true,
        desc = "Shows a local title when Hypixel reports a completed commission.",
    )

    private var commissions: List<CommissionEntry> = emptyList()
    private var updateTick = 0

    @Suppress("unused")
    private val commissionHud by textHud("Commission display") {
        visibleIf { preview || currentArea.isArea(Island.DwarvenMines, Island.CrystalHollows, Island.Mineshaft) }
        column {
            text(
                string = "Commissions:",
                colour = Colour.MINECRAFT_RED,
                font = font,
                size = 18.px,
            ).shadow = shadow

            repeat(5) { index ->
                val row = textSupplied(
                    supplier = {
                        if (preview) PREVIEW_LINES.getOrElse(index) { "" }
                        else commissions.getOrNull(index)?.format().orEmpty()
                    },
                    colour = colour,
                    font = font,
                    size = 18.px,
                )
                row.shadow = shadow
                row.visibleIf { preview || index < commissions.size }
            }

            val empty = textSupplied(
                supplier = { if (preview || commissions.isNotEmpty()) "" else "No commissions found" },
                colour = Colour.MINECRAFT_GRAY,
                font = font,
                size = 18.px,
            )
            empty.shadow = shadow
            empty.visibleIf { !preview && commissions.isEmpty() }
        }
    }.setting()

    init {
        on<TickEvent.End> {
            if (++updateTick < UPDATE_INTERVAL_TICKS) return@on
            updateTick = 0
            refresh()
        }

        on<ChatEvent.PacketClient> {
            if (!completionTitle) return@on
            val match = COMPLETION_MESSAGE.matchEntire(message) ?: return@on
            PlayerUtils.setTitle(
                title = "§6${match.groupValues[1]}",
                subtitle = "§aCommission complete!",
                fadeIn = 0,
                stayAlive = 40,
                fadeOut = 10,
            )
            refresh()
        }

        on<WorldEvent.Change> {
            commissions = emptyList()
            updateTick = 0
        }
    }

    override fun onEnable() {
        super.onEnable()
        refresh()
    }

    override fun onDisable() {
        super.onDisable()
        commissions = emptyList()
    }

    private fun refresh() {
        commissions = CommissionParser.parse(
            WorldUtils.tablist.mapNotNull { it.tabListDisplayName?.string },
        )
    }

    private fun CommissionEntry.format(): String {
        val progressColour = when {
            progress >= 100f -> "§a"
            progress >= 75f -> "§b"
            progress >= 50f -> "§e"
            progress >= 25f -> "§6"
            else -> "§c"
        }
        return "§7- §f$name: $progressColour${progress.toFixed(1).removeSuffix(".0")}%"
    }

    private val PREVIEW_LINES = listOf(
        "§7- §fMithril Miner: §e62.5%",
        "§7- §fGoblin Slayer: §a100%",
    )
    private val COMPLETION_MESSAGE = Regex(
        """^(.{1,80}) Commission Complete! Visit the King to claim your rewards!$""",
    )
    private const val UPDATE_INTERVAL_TICKS = 20
}
