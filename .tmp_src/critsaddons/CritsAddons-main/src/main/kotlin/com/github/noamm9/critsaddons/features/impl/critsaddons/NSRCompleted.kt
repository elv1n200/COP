package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D
import com.github.noamm9.utils.render.Render2D.width
import java.awt.Color
import java.util.Locale

object NSRCompleted : Feature(
    name = "NSR Completed",
    description = "Tracks completed Secret Route rooms and shows completion status."
) {
    private val hudSection = "hud"

    private val showHud by ToggleSetting("Show HUD", true).section(hudSection)
    private val showOutsideDungeon by ToggleSetting("Show Outside Dungeon", true).section(hudSection)
    private val maxRows by SliderSetting("Max Rows", 35, 5, 120, 1).section(hudSection)
    private val hudBackgroundColor by ColorSetting("HUD Background", Color(0, 0, 0, 120), true).section(hudSection)
    private val hudBorderColor by ColorSetting("HUD Border", Color(255, 255, 255, 150), true).section(hudSection).showIf { showHud.value }

    private val currentDungeonRooms = linkedSetOf<String>()

    private enum class RoomStatus(val rank: Int, val colorCode: String) {
        YELLOW(0, "&e"),
        GREEN(1, "&a"),
        RED(2, "&c")
    }

    private data class RoomStatusEntry(val name: String, val status: RoomStatus)

    private val hudElement = object : HudElement() {
        override val name: String = "NSR Completed"
        override val toggle get() = this@NSRCompleted.enabled && showHud.value
        override val shouldDraw get() = mc.player != null && (LocationUtils.inDungeon || showOutsideDungeon.value)

        override fun draw(ctx: net.minecraft.client.gui.GuiGraphics, example: Boolean): Pair<Float, Float> {
            val lines = if (example) {
                listOf(
                    "&bNSR Completed",
                    "&eCurrent Room",
                    "&aFinished Room",
                    "&cUnfinished Room"
                )
            } else {
                buildHudLines()
            }

            if (lines.isEmpty()) return 0f to 0f

            val padding = 4f
            val lineHeight = 9f
            val width = lines.maxOf { it.width().toFloat() } + padding * 2f
            val height = lines.size * lineHeight + padding * 2f

            Render2D.drawRect(ctx, 0f, 0f, width, height, hudBackgroundColor.value)
            Render2D.drawBorder(ctx, 0f, 0f, width, height, hudBorderColor.value, 1)
            lines.forEachIndexed { index, line ->
                Render2D.drawString(ctx, line, padding, padding + index * lineHeight)
            }

            return width to height
        }
    }.also(hudElements::add)

    override fun init() {
        register<DungeonEvent.RoomEvent.onEnter> {
            currentDungeonRooms.add(event.room.name)
        }

        register<WorldChangeEvent> {
            currentDungeonRooms.clear()
        }
    }

    private fun buildHudLines(): List<String> {
        val names = linkedSetOf<String>()
        names.addAll(SecretRoutes.getTrackedCompletionRoomNames())
        names.addAll(currentDungeonRooms)
        ScanUtils.currentRoom?.name?.let(names::add)

        if (names.isEmpty()) return listOf("&bNSR Completed", "&7No rooms tracked.")

        val dungeonNow = currentDungeonRooms.toMutableSet()
        ScanUtils.currentRoom?.name?.let(dungeonNow::add)

        val entries = names.map { roomName ->
            val completed = SecretRoutes.isRoomMarkedCompleted(roomName)
            val status = when {
                !completed && roomName in dungeonNow -> RoomStatus.YELLOW
                completed -> RoomStatus.GREEN
                else -> RoomStatus.RED
            }
            RoomStatusEntry(roomName, status)
        }.sortedWith(compareBy<RoomStatusEntry> { it.status.rank }.thenBy { it.name.lowercase(Locale.US) })

        val capped = entries.take(maxRows.value)
        val lines = mutableListOf("&bNSR Completed")
        capped.forEach { entry ->
            lines.add("${entry.status.colorCode}${entry.name}")
        }

        if (entries.size > capped.size) {
            lines.add("&7... +${entries.size - capped.size} more")
        }

        return lines
    }
}
