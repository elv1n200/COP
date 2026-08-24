package cop.module.impl.dungeon.huds

import cop.api.events.DungeonEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.odonscanning.DungeonMapData
import cop.api.skyblock.dungeon.odonscanning.MapRenderer
import cop.api.skyblock.dungeon.odonscanning.ScanUtils
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.SoundUtils
import cop.utils.skyblock.player.PlayerUtils

/** Passive, local-only alerts for state changes in the player's current room. */
object RoomAlerts : Module(
    "Room Alerts",
    desc = "Shows local alerts when the current dungeon room is cleared or all of its secrets are done.",
) {
    private val clearedAlerts by switch(
        "Room cleared", true,
        desc = "Shows a title when the current room changes to a white check.",
    )
    private val clearedTitle by textInput(
        "Cleared title", "&aRoom Cleared!", length = 48,
    ).childOf(::clearedAlerts)

    private val secretsAlerts by switch(
        "Secrets done", true,
        desc = "Shows a title when a room with secrets changes to a green check.",
    )
    private val secretsTitle by textInput(
        "Secrets title", "&aSecrets Done!", length = 48,
    ).childOf(::secretsAlerts)

    private val titleDuration by slider(
        "Title duration", 35, 10, 100, 5,
        desc = "How long the alert remains visible.", unit = "ticks",
    )
    private val playSound by switch("Play sound", true)
    private val alertSound by selector(
        "Alert sound",
        SoundUtils.SoundSetting.Pling,
        SoundUtils.SoundSetting.entries.filterNot { it == SoundUtils.SoundSetting.Custom },
        desc = "Sound played with enabled room alerts.",
    ).childOf(::playSound)
    private val alertVolume by slider(
        "Alert volume", 0.75f, 0.1f, 2f, 0.05f,
    ).childOf(::playSound)
    private val alertPitch by slider(
        "Alert pitch", 1f, 0.1f, 2f, 0.05f,
    ).childOf(::playSound)

    private val tracker = RoomAlertTracker()
    private var observingClear = false

    init {
        on<TickEvent.End> {
            if (!Dungeon.inClear) {
                if (observingClear) resetSession()
                return@on
            }

            if (!observingClear) {
                tracker.reset()
                observingClear = true
            }

            tracker.observe(currentObservation())?.let(::showAlert)
        }

        on<DungeonEvent.Start> { resetSession() }
        on<WorldEvent.Change> { resetSession() }
    }

    override fun onEnable() {
        resetSession()
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
        resetSession()
    }

    private fun currentObservation(): RoomAlertObservation? {
        val room = ScanUtils.currentRoom ?: return null
        val fromMap = MapRenderer.snapshotFor(room)
        val components = fromMap?.components ?: room.roomComponents.mapTo(linkedSetOf()) {
            DungeonMapData.Cell(it.placement.x / 20, it.placement.z / 20)
        }
        if (components.isEmpty()) return null

        return RoomAlertObservation(
            components = components,
            state = fromMap?.state ?: room.state,
            type = room.data.type,
            secretCount = room.data.secrets,
        )
    }

    private fun showAlert(kind: RoomAlertKind) {
        val title = when (kind) {
            RoomAlertKind.CLEARED -> if (clearedAlerts) clearedTitle else return
            RoomAlertKind.SECRETS_DONE -> if (secretsAlerts) secretsTitle else return
        }

        PlayerUtils.setTitle(
            title = title,
            playSound = false,
            stayAlive = titleDuration,
            fadeOut = 10,
        )
        if (playSound) SoundUtils.play(alertSound.selected.sound, alertVolume, alertPitch)
    }

    private fun resetSession() {
        tracker.reset()
        observingClear = false
    }
}
