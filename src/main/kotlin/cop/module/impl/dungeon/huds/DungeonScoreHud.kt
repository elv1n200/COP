package cop.module.impl.dungeon.huds

import cop.api.abobaui.dsl.px
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.skyblock.player.PlayerUtils
import net.minecraft.sounds.SoundEvents

/**
 * Thin local observer around COP's existing dungeon score model. NoammAddons'
 * score HUD/milestones were a behavioural reference; this implementation does
 * not broadcast messages or consume any remote score state.
 */
object DungeonScoreHud : Module(
    "Dungeon Score HUD",
    desc = "Shows COP's live dungeon score and optional local 270/300 milestones.",
) {
    private val showSecretTarget by switch(
        "Show secret target", true,
        desc = "Shows found secrets against COP's estimated amount needed for maximum exploration score.",
    )
    private val alert270 by switch("270 title", true)
    private val alert300 by switch("300 title", true)
    private val milestoneSound by switch("Milestone sound", true)

    private var reached270 = false
    private var reached300 = false
    private var observedDungeon = false

    @Suppress("unused")
    private val scoreHud by textHud("Dungeon score") {
        visibleIf { preview || Dungeon.inDungeons }
        column {
            val scoreRow = textSupplied(
                supplier = {
                    val score = if (preview) 300 else Dungeon.score
                    "§eScore: ${scoreColour(score)}$score"
                },
                colour = colour,
                font = font,
                size = 18.px,
            )
            scoreRow.shadow = shadow

            val secretsRow = textSupplied(
                supplier = {
                    val found = if (preview) 42 else Dungeon.secretCount
                    val target = if (preview) 42 else Dungeon.neededSecretsAmount
                    "§eSecrets: §b$found§7/§b$target"
                },
                colour = colour,
                font = font,
                size = 18.px,
            )
            secretsRow.shadow = shadow
            secretsRow.visibleIf { preview || showSecretTarget && Dungeon.neededSecretsAmount > 0 }
        }
    }.setting()

    init {
        on<TickEvent.End> {
            if (!Dungeon.inDungeons) {
                observedDungeon = false
                return@on
            }

            val score = Dungeon.score
            if (!observedDungeon) {
                observedDungeon = true
                reached270 = score >= 270
                reached300 = score >= 300
                return@on
            }

            if (!reached300 && score >= 300) {
                val fallbackTo270 = !reached270 && alert270
                reached270 = true
                reached300 = true
                when {
                    alert300 -> showMilestone(300, "§a")
                    fallbackTo270 -> showMilestone(270, "§e")
                }
                return@on
            }
            if (!reached270 && score >= 270) {
                reached270 = true
                if (alert270) showMilestone(270, "§e")
            }
        }

        on<WorldEvent.Change> { resetMilestones() }
    }

    override fun onEnable() {
        super.onEnable()
        resetMilestones()
    }

    private fun resetMilestones() {
        reached270 = false
        reached300 = false
        observedDungeon = false
    }

    private fun showMilestone(score: Int, colour: String) {
        PlayerUtils.setTitle(
            title = "$colour$score SCORE",
            playSound = milestoneSound,
            sound = SoundEvents.EXPERIENCE_ORB_PICKUP,
            volume = 1f,
            pitch = if (score >= 300) 1.2f else 1f,
            stayAlive = 35,
            fadeOut = 10,
        )
    }

    private fun scoreColour(score: Int): String = when {
        score >= 300 -> "§a"
        score >= 270 -> "§e"
        else -> "§c"
    }
}
