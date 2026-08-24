package cop.module.impl.dungeon.qol

import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.SkyblockPlayer
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType
import cop.module.Module
import cop.utils.ChatUtils
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes

/** Fetches an Architect's First Draft after the local player fails a puzzle. */
object ArchitectDraft : Module(
    "Architect's Draft",
    area = Island.Dungeon,
    desc = "Fetches an Architect's First Draft after your own puzzle fail."
) {
    private val autoFetch by switch(
        "Auto fetch",
        true,
        desc = "Runs /gfs ARCHITECT_FIRST_DRAFT 1 after your own puzzle fail."
    )
    private val fetchDelay by slider(
        "Fetch delay",
        1500,
        500,
        5000,
        100,
        unit = "ms",
        desc = "Delay before fetching the draft."
    )
    private val announceUse by switch(
        "Announce reset",
        true,
        desc = "Announces a successful puzzle reset in party chat."
    )
    private val feedback by switch("Feedback", true)

    private var pendingFetch: Scheduler.Task? = null
    private var lastFailAt = 0L

    init {
        on<ChatEvent.PacketClient> {
            val raw = message.noControlCodes.trim()

            if (announceUse) {
                RESET_PATTERN.matchEntire(raw)?.groupValues?.getOrNull(1)?.let { puzzle ->
                    ChatUtils.command("pc Used Architect's Draft to reset $puzzle")
                }
            }

            if (!autoFetch || Dungeon.inBoss) return@on
            val roomType = Dungeon.currentRoom?.data?.type
            if (roomType != null && roomType != RoomType.PUZZLE) return@on

            val failedPlayer = FAIL_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.matchEntire(raw)?.groups?.get("player")?.value
            } ?: return@on
            val self = mc.player?.gameProfile?.name ?: return@on
            if (!failedPlayer.equals(self, ignoreCase = true)) return@on

            val now = System.currentTimeMillis()
            if (now - lastFailAt < 3_000L || pendingFetch != null) return@on
            lastFailAt = now

            val delayTicks = ((fetchDelay + 49) / 50).coerceAtLeast(1)
            pendingFetch = Scheduler.scheduleTaskHandle(delayTicks) {
                pendingFetch = null
                if (!enabled || !Dungeon.inClear || mc.player == null || !SkyblockPlayer.canUseCommands) {
                    if (feedback) modMessage("&cArchitect's Draft: command use is currently unavailable.")
                    return@scheduleTaskHandle
                }
                ChatUtils.command("gfs ARCHITECT_FIRST_DRAFT 1")
                if (feedback) modMessage("&aFetching an Architect's First Draft.")
            }
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun reset() {
        pendingFetch?.cancel()
        pendingFetch = null
        lastFailAt = 0L
    }

    private val RESET_PATTERN = Regex("^You used the Architect's First Draft to reset (.+)!$")
    private val FAIL_PATTERNS = listOf(
        Regex("^PUZZLE FAIL! (?<player>\\w{1,16}) .+$"),
        Regex(
            "^\\[STATUE] Oruo the Omniscient: (?<player>\\w{1,16}) chose the wrong answer! " +
                "I shall never forget this moment of misrememberance\\.$"
        )
    )
}
