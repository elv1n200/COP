package cop.module.impl.dungeon.huds

import cop.api.skyblock.dungeon.odonscanning.DungeonMapData
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomState
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType

/** Immutable input for [RoomAlertTracker]. */
internal data class RoomAlertObservation(
    val components: Set<DungeonMapData.Cell>,
    val state: RoomState,
    val type: RoomType,
    val secretCount: Int,
) {
    val isAlertable: Boolean
        get() = type != RoomType.ENTRANCE && type != RoomType.BLOOD && type != RoomType.FAIRY
}

internal enum class RoomAlertKind {
    CLEARED,
    SECRETS_DONE,
}

/**
 * Pure state machine which turns noisy room-state observations into at most
 * one alert of each kind per room and dungeon run.
 *
 * Room identity is based on overlapping map components rather than object
 * identity. This keeps an already-delivered alert attached to a room when the
 * world scanner discovers and merges another component later in the run.
 */
internal class RoomAlertTracker {
    private var activeComponents: Set<DungeonMapData.Cell> = emptySet()
    private var activeState: RoomState? = null
    private val deliveredCells = RoomAlertKind.entries.associateWith {
        linkedSetOf<DungeonMapData.Cell>()
    }.toMutableMap()

    fun observe(observation: RoomAlertObservation?): RoomAlertKind? {
        if (observation == null || observation.components.isEmpty()) {
            leaveRoom()
            return null
        }

        val sameRoom = activeState != null && activeComponents.any { it in observation.components }
        if (!sameRoom) {
            activeComponents = observation.components.toSet()
            activeState = observation.state
            rememberTerminalState(observation.state, activeComponents)
            return null
        }

        val previousState = activeState
        val previousComponents = activeComponents
        activeComponents = previousComponents + observation.components
        activeState = observation.state

        // Carry delivered markers onto components discovered after the alert.
        RoomAlertKind.entries.forEach { kind ->
            val delivered = deliveredCells.getValue(kind)
            if (previousComponents.any { it in delivered }) delivered += activeComponents
        }

        if (previousState == observation.state) return null

        val kind = when (observation.state) {
            RoomState.CLEARED -> RoomAlertKind.CLEARED
            RoomState.GREEN -> RoomAlertKind.SECRETS_DONE
            else -> null
        }
        val alreadyDelivered = kind != null && activeComponents.any {
            it in deliveredCells.getValue(kind)
        }

        // GREEN semantically includes CLEARED. Recording both also prevents a
        // partial packet which temporarily regresses GREEN -> CLEARED from
        // producing a late duplicate clear alert.
        rememberTerminalState(observation.state, activeComponents)

        if (kind == null || alreadyDelivered || !observation.isAlertable) return null
        if (kind == RoomAlertKind.SECRETS_DONE && observation.secretCount <= 0) return null
        return kind
    }

    fun reset() {
        leaveRoom()
        deliveredCells.values.forEach(MutableSet<DungeonMapData.Cell>::clear)
    }

    private fun leaveRoom() {
        activeComponents = emptySet()
        activeState = null
    }

    private fun rememberTerminalState(
        state: RoomState,
        components: Set<DungeonMapData.Cell>,
    ) {
        when (state) {
            RoomState.CLEARED -> deliveredCells.getValue(RoomAlertKind.CLEARED) += components
            RoomState.GREEN -> {
                deliveredCells.getValue(RoomAlertKind.CLEARED) += components
                deliveredCells.getValue(RoomAlertKind.SECRETS_DONE) += components
            }

            else -> Unit
        }
    }
}
