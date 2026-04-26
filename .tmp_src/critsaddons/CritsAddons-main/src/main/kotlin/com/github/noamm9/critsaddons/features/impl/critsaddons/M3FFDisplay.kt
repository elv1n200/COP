package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.render.Render2D
import com.github.noamm9.utils.render.Render2D.width
import java.awt.Color
import java.util.Locale

object M3FFDisplay : Feature(
    name = "M3 FF Display",
    description = "Shows a countdown for when to Fire Freeze in M3 boss."
) {
    private const val PROFESSOR_FIRE_FREEZE_LINE =
        "[BOSS] The Professor: Oh? You found my Guardians' one weakness?"
    private const val FIRE_FREEZE_DELAY_MS = 5000L
    private val HUD_BACKGROUND = Color(0, 0, 0, 120)
    private val HUD_BORDER = Color(255, 255, 255, 150)

    private var fireAtMs = 0L
    private var lastTriggerAtMs = 0L

    private val hudElement = object : HudElement() {
        override val name: String = "M3 FF Display"
        override val toggle get() = this@M3FFDisplay.enabled
        override val shouldDraw get() = mc.player != null && activeState() != null

        override fun draw(ctx: net.minecraft.client.gui.GuiGraphics, example: Boolean): Pair<Float, Float> {
            val lines = if (example) {
                listOf("&bM3 FF", "&eFire Freeze in 5.00")
            } else {
                activeState() ?: return 0f to 0f
            }

            val padding = 4f
            val lineHeight = 9f
            val width = lines.maxOf { it.width().toFloat() } + padding * 2f
            val height = lines.size * lineHeight + padding * 2f

            Render2D.drawRect(ctx, 0f, 0f, width, height, HUD_BACKGROUND)
            Render2D.drawBorder(ctx, 0f, 0f, width, height, HUD_BORDER, 1)
            lines.forEachIndexed { index, line ->
                Render2D.drawString(ctx, line, padding, padding + index * lineHeight)
            }

            return width to height
        }
    }.also(hudElements::add)

    override fun init() {
        register<ChatMessageEvent> {
            handleChat(event.unformattedText.removeFormatting())
        }

        register<WorldChangeEvent> {
            reset()
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun handleChat(message: String) {
        if (!enabled) return
        if (message != PROFESSOR_FIRE_FREEZE_LINE) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < 10_000L) return

        lastTriggerAtMs = now
        fireAtMs = now + FIRE_FREEZE_DELAY_MS
    }

    private fun activeState(): List<String>? {
        val fireAt = fireAtMs.takeIf { it > 0L } ?: return null
        val remaining = fireAt - System.currentTimeMillis()

        if (remaining <= 0L) {
            fireAtMs = 0L
            return null
        }

        return listOf(
            "&bM3 FF",
            "&eFire Freeze in ${formatSeconds(remaining)}"
        )
    }

    private fun formatSeconds(milliseconds: Long): String {
        return String.format(Locale.US, "%.2f", milliseconds / 1000.0)
    }

    private fun reset() {
        fireAtMs = 0L
        lastTriggerAtMs = 0L
    }
}
