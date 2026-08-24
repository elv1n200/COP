package cop.module.impl.dungeon.qol

import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Floor
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.PartyUtils

/** Requeues the completed dungeon after the result header appears in chat. */
object AutoRequeue : Module(
    "Auto Requeue",
    area = Island.Dungeon,
    desc = "Requeues the same dungeon floor after a completed run."
) {
    private val requeueCommand by selector(
        "Command",
        "Join instance",
        listOf("Join instance", "Instance requeue"),
        desc = "Join instance targets the captured floor; instance requeue asks the server to repeat it."
    )
    private val delaySeconds by slider("Delay", 5, 1, 15, 1, unit = "s")

    private val partyChecks by switch(
        "Party checks",
        true,
        desc = "Validates the party before scheduling and again before requeueing."
    )
    private val requireLeader by switch("Require leader", true).childOf(::partyChecks)
    private val requireFullParty by switch(
        "Require five players",
        false,
        desc = "Cancels if fewer than five dungeon players are known."
    ).childOf(::partyChecks)
    private val feedback by switch("Feedback", true)

    private var pending: Scheduler.Task? = null
    private var lastRunEndAt = 0L

    init {
        on<ChatEvent.PacketClient> {
            if (!RUN_END_PATTERN.matches(message.noControlCodes)) return@on

            val now = System.currentTimeMillis()
            if (pending != null || now - lastRunEndAt < 10_000L) return@on
            val floor = Dungeon.floor ?: return@on feedback("Could not determine the completed floor.")
            if (!partyValid()) return@on

            lastRunEndAt = now
            val scheduledWorld = mc.level ?: return@on
            pending = Scheduler.scheduleTaskHandle(delaySeconds * 20) {
                pending = null
                if (!enabled || mc.level !== scheduledWorld || mc.player == null) return@scheduleTaskHandle
                if (!partyValid()) return@scheduleTaskHandle

                when (requeueCommand.selected) {
                    "Instance requeue" -> ChatUtils.command("instancerequeue")
                    else -> ChatUtils.command("joininstance ${floor.instanceId()}")
                }
                feedback("Requeue command sent for ${floor.name}.", success = true)
            }

            feedback("Requeueing ${floor.name} in ${delaySeconds}s.", success = true)
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun partyValid(): Boolean {
        if (!partyChecks) return true

        val knownPartySize = maxOf(PartyUtils.members.size, Dungeon.dungeonTeammates.size)
        if (!PartyUtils.isInParty && knownPartySize <= 1) {
            feedback("Cancelled: you are not in a party.")
            return false
        }
        if (requireFullParty && knownPartySize < 5) {
            feedback("Cancelled: only $knownPartySize/5 players are known.")
            return false
        }
        if (requireLeader && !PartyUtils.isLeader()) {
            feedback("Cancelled: you are not the party leader.")
            return false
        }
        return true
    }

    private fun feedback(message: String, success: Boolean = false) {
        if (!feedback) return
        modMessage("${if (success) "&a" else "&c"}Auto Requeue: &f$message")
    }

    private fun reset() {
        pending?.cancel()
        pending = null
        lastRunEndAt = 0L
    }

    private fun Floor.instanceId(): String {
        if (this == Floor.E) return "catacombs_entrance"
        val number = listOf("one", "two", "three", "four", "five", "six", "seven")[floorNumber - 1]
        return "${if (isMM) "master_" else ""}catacombs_floor_$number"
    }

    private val RUN_END_PATTERN =
        Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Floor .{1,3}|Entrance)\\s*$")
}
