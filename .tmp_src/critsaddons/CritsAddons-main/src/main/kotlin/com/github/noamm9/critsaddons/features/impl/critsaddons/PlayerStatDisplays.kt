package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.ActionBarMessageEvent
import com.github.noamm9.critsaddons.CritsAddonsDefaults
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.utils.ActionBarParser
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.render.Render2D
import com.github.noamm9.utils.render.Render2D.width
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color
import java.util.Locale

object StatDisplay : Feature(
    name = "Stat Display",
    description = "Displays health, mana, defense, EHP, and speed HUD elements."
) {
    private const val BARS_SECTION = "Bars"
    private const val NUMBERS_SECTION = "Numbers"
    private const val HIDING_SECTION = "Hiding"
    private const val STYLE_SECTION = "Style"

    private val showHealthBar by ToggleSetting("Health Bar", true).section(BARS_SECTION)
    private val healthBarColor by ColorSetting("Health Bar Color", Color(220, 45, 45, 190), true)
        .showIf { showHealthBar.value }
    private val showManaBar by ToggleSetting("Mana Bar", true)
    private val manaBarColor by ColorSetting("Mana Bar Color", Color(45, 175, 255, 190), true)
        .showIf { showManaBar.value }

    private val showManaNumber by ToggleSetting("Mana Number", true).section(NUMBERS_SECTION)
    private val manaNumberColor by ColorSetting("Mana Number Color", Color(85, 255, 255), true)
        .showIf { showManaNumber.value }
    private val showOverflowManaNumber by ToggleSetting("Overflow Mana Number", true)
    private val overflowManaNumberColor by ColorSetting("Overflow Mana Color", Color(30, 190, 255), true)
        .showIf { showOverflowManaNumber.value }
    private val showHealthNumber by ToggleSetting("Health Number", true)
    private val healthNumberColor by ColorSetting("Health Number Color", Color(255, 85, 85), true)
        .showIf { showHealthNumber.value }
    private val showEhpHealthNumber by ToggleSetting("EHP Health Number", true)
    private val ehpHealthNumberColor by ColorSetting("EHP Health Color", Color(85, 255, 85), true)
        .showIf { showEhpHealthNumber.value }
    private val showDefensiveNumber by ToggleSetting("Defensive Number", true)
    private val defensiveNumberColor by ColorSetting("Defensive Number Color", Color(70, 220, 70), true)
        .showIf { showDefensiveNumber.value }
    private val showSpeedNumber by ToggleSetting("Speed Number", true)
    private val speedNumberColor by ColorSetting("Speed Number Color", Color(255, 255, 255), true)
        .showIf { showSpeedNumber.value }

    private val hideDefaultStats by ToggleSetting("Hide Default Stats", false).section(HIDING_SECTION)
    private val hideExperienceBar by ToggleSetting("Hide Experience Bar", false)

    private val backgroundColor by ColorSetting("Background Color", Color(0, 0, 0, 120), true).section(STYLE_SECTION)
    private val borderColor by ColorSetting("Bar Border Color", Color(0, 0, 0, 230), true)

    private data class NumberDisplay(
        val name: String,
        val enabled: () -> Boolean,
        val color: () -> Color,
        val icon: String,
        val previewValue: String,
        val text: () -> String,
    )

    private data class BarDisplay(
        val name: String,
        val enabled: () -> Boolean,
        val color: () -> Color,
        val previewCurrent: Int,
        val previewMax: Int,
        val current: () -> Int,
        val max: () -> Int,
    )

    private val barDisplays = listOf(
        BarDisplay(
            name = "Health Bar",
            enabled = { showHealthBar.value },
            color = { healthBarColor.value },
            previewCurrent = 3_200,
            previewMax = 4_000,
            current = { ActionBarParser.currentHealth },
            max = { ActionBarParser.maxHealth },
        ),
        BarDisplay(
            name = "Mana Bar",
            enabled = { showManaBar.value },
            color = { manaBarColor.value },
            previewCurrent = 1_700,
            previewMax = 2_400,
            current = { ActionBarParser.currentMana },
            max = { ActionBarParser.maxMana },
        ),
    )

    private val numberDisplays = listOf(
        NumberDisplay(
            name = "Mana Number",
            enabled = { showManaNumber.value },
            color = { manaNumberColor.value },
            icon = "✎",
            previewValue = "1,700/2,400",
            text = { "${formatNumber(ActionBarParser.currentMana)}/${formatNumber(ActionBarParser.maxMana)}" },
        ),
        NumberDisplay(
            name = "Overflow Mana Number",
            enabled = { showOverflowManaNumber.value },
            color = { overflowManaNumberColor.value },
            icon = "ʬ",
            previewValue = "1,200",
            text = { formatNumber(ActionBarParser.overflowMana) },
        ),
        NumberDisplay(
            name = "Health Number",
            enabled = { showHealthNumber.value },
            color = { healthNumberColor.value },
            icon = "❤",
            previewValue = "3,200/4,000",
            text = { "${formatNumber(ActionBarParser.currentHealth)}/${formatNumber(ActionBarParser.maxHealth)}" },
        ),
        NumberDisplay(
            name = "EHP Health Number",
            enabled = { showEhpHealthNumber.value },
            color = { ehpHealthNumberColor.value },
            icon = "❤",
            previewValue = "215,000",
            text = { formatNumber(ActionBarParser.effectiveHP) },
        ),
        NumberDisplay(
            name = "Defensive Number",
            enabled = { showDefensiveNumber.value },
            color = { defensiveNumberColor.value },
            icon = "❈",
            previewValue = "1,450",
            text = { formatNumber(ActionBarParser.currentDefense) },
        ),
        NumberDisplay(
            name = "Speed Number",
            enabled = { showSpeedNumber.value },
            color = { speedNumberColor.value },
            icon = "✦",
            previewValue = "400",
            text = { formatNumber(ActionBarParser.currentSpeed) },
        ),
    )

    override fun init() {
        register<ActionBarMessageEvent> {
            if (!shouldHideDefaultStats()) return@register

            val cleaned = removeDefaultStats(event.message)
            if (cleaned.removeFormatting().isBlank()) {
                event.cancel()
            } else {
                event.message = cleaned
            }
        }
    }

    init {
        CritsAddonsDefaults.install()

        barDisplays.forEach { display ->
            object : HudElement() {
                override val name: String = display.name
                override val toggle get() = this@StatDisplay.enabled && display.enabled()
                override val shouldDraw get() = mc.player != null

                override fun draw(ctx: GuiGraphics, example: Boolean): Pair<Float, Float> {
                    return drawBar(ctx, display, example)
                }
            }.also(hudElements::add)
        }

        numberDisplays.forEach { display ->
            object : HudElement() {
                override val name: String = display.name
                override val toggle get() = this@StatDisplay.enabled && display.enabled()
                override val shouldDraw get() = mc.player != null

                override fun draw(ctx: GuiGraphics, example: Boolean): Pair<Float, Float> {
                    return drawNumber(ctx, display, example)
                }
            }.also(hudElements::add)
        }
    }

    private fun drawNumber(ctx: GuiGraphics, display: NumberDisplay, example: Boolean): Pair<Float, Float> {
        val valueText = if (example) display.previewValue else display.text()
        val text = "${display.icon} $valueText"
        val padding = 3f
        val width = text.width().toFloat() + padding * 2f
        val height = 9f + padding * 2f

        Render2D.drawRect(ctx, 0f, 0f, width, height, backgroundColor.value)
        Render2D.drawString(ctx, text, padding, padding, display.color())

        return width to height
    }

    private fun drawBar(ctx: GuiGraphics, display: BarDisplay, example: Boolean): Pair<Float, Float> {
        val currentValue = if (example) display.previewCurrent else display.current()
        val maxValue = if (example) display.previewMax else display.max()
        val ratio = if (maxValue <= 0) 0f else (currentValue.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        val fillWidth = INNER_TRACK_WIDTH * ratio
        val fillColor = barFillColor(display.color(), backgroundColor.value)

        drawDiamondBarBackground(ctx)
        if (fillWidth > 0f) {
            Render2D.drawRect(ctx, INNER_TRACK_X, BAR_INSET, fillWidth, INNER_BAR_HEIGHT, fillColor)
            drawDiamond(ctx, DIAMOND_RADIUS, DIAMOND_RADIUS, fillColor, inner = true)
            if (ratio >= 0.99f) {
                drawDiamond(ctx, BAR_WIDTH - DIAMOND_RADIUS - 1f, DIAMOND_RADIUS, fillColor, inner = true)
            }
        }

        return BAR_WIDTH to BAR_HEIGHT
    }

    private fun drawDiamondBarBackground(ctx: GuiGraphics) {
        Render2D.drawRect(ctx, TRACK_X, 0f, TRACK_WIDTH, BAR_HEIGHT, borderColor.value)
        drawDiamond(ctx, DIAMOND_RADIUS, DIAMOND_RADIUS, borderColor.value, inner = false)
        drawDiamond(ctx, BAR_WIDTH - DIAMOND_RADIUS - 1f, DIAMOND_RADIUS, borderColor.value, inner = false)
        Render2D.drawRect(ctx, INNER_TRACK_X, BAR_INSET, INNER_TRACK_WIDTH, INNER_BAR_HEIGHT, backgroundColor.value)
    }

    private fun drawDiamond(ctx: GuiGraphics, centerX: Float, centerY: Float, color: Color, inner: Boolean) {
        val profile = if (inner) INNER_DIAMOND_PROFILE else OUTER_DIAMOND_PROFILE
        val verticalOffset = (DIAMOND_SIZE - profile.size) / 2f

        profile.forEachIndexed { row, halfWidth ->
            val y = centerY - DIAMOND_RADIUS + verticalOffset + row
            val x = centerX - halfWidth
            Render2D.drawRect(ctx, x, y, halfWidth * 2f + 1f, 1f, color)
        }
    }

    private fun shouldHideDefaultStats(): Boolean {
        return enabled && hideDefaultStats.value
    }

    @JvmStatic
    fun shouldHideExperienceBar(): Boolean {
        return enabled && hideExperienceBar.value
    }

    private fun removeDefaultStats(message: String): String {
        var cleaned = message
        ACTION_BAR_STAT_PATTERNS.forEach { pattern ->
            cleaned = cleaned.replace(pattern, " ")
        }
        return cleaned
            .replace(Regex("(?:§r|§[0-9a-fk-or])*\\s{2,}"), " ")
            .trim()
    }

    private fun formatNumber(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun barFillColor(color: Color, background: Color): Color {
        val alpha = color.alpha / 255.0
        val inverse = 1.0 - alpha
        return Color(
            (color.red * alpha + background.red * inverse).toInt().coerceIn(0, 255),
            (color.green * alpha + background.green * inverse).toInt().coerceIn(0, 255),
            (color.blue * alpha + background.blue * inverse).toInt().coerceIn(0, 255),
            255,
        )
    }

    private const val BAR_WIDTH = 96f
    private const val BAR_HEIGHT = 9f
    private const val BAR_INSET = 1f
    private const val INNER_BAR_HEIGHT = BAR_HEIGHT - BAR_INSET * 2f
    private const val DIAMOND_SIZE = 9
    private const val DIAMOND_RADIUS_INT = DIAMOND_SIZE / 2
    private const val DIAMOND_RADIUS = 4f
    private const val TRACK_X = DIAMOND_RADIUS
    private const val TRACK_WIDTH = BAR_WIDTH - DIAMOND_RADIUS * 2f
    private const val INNER_TRACK_X = TRACK_X + BAR_INSET
    private const val INNER_TRACK_WIDTH = TRACK_WIDTH - BAR_INSET * 2f
    private val OUTER_DIAMOND_PROFILE = intArrayOf(0, 1, 2, 3, 4, 3, 2, 1, 0)
    private val INNER_DIAMOND_PROFILE = intArrayOf(0, 1, 2, 3, 2, 1, 0)
    private val ACTION_BAR_STAT_PATTERNS = listOf(
        Regex("(?:§[0-9a-fk-or])*[\\d,]+(?:\\.[\\d]+)?(?:\\s*/\\s*(?:§[0-9a-fk-or])*)?[\\d,]*(?:§[0-9a-fk-or])*\\s*❤", RegexOption.IGNORE_CASE),
        Regex("(?:§[0-9a-fk-or])*[\\d,]+(?:§[0-9a-fk-or])*\\s*❈(?:§[0-9a-fk-or])*\\s*Defense", RegexOption.IGNORE_CASE),
        Regex("(?:§[0-9a-fk-or])*[\\d,]+(?:\\s*/\\s*(?:§[0-9a-fk-or])*)?[\\d,]+(?:§[0-9a-fk-or])*\\s*✎", RegexOption.IGNORE_CASE),
        Regex("(?:§[0-9a-fk-or])*[\\d,]+(?:§[0-9a-fk-or])*\\s*ʬ", RegexOption.IGNORE_CASE),
    )
}
