package cop.module.impl.dungeon.huds

import cop.api.skyblock.dungeon.odonscanning.DungeonMapData
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomState
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomAlertDataTest {
    private val first = setOf(DungeonMapData.Cell(1, 2))
    private val second = setOf(DungeonMapData.Cell(4, 3))

    @Test
    fun `first observation is a baseline even when room is already terminal`() {
        val clearedTracker = RoomAlertTracker()
        val greenTracker = RoomAlertTracker()

        assertNull(clearedTracker.observe(observation(first, RoomState.CLEARED)))
        assertNull(greenTracker.observe(observation(first, RoomState.GREEN)))
    }

    @Test
    fun `clear and secrets transitions are each delivered once`() {
        val tracker = RoomAlertTracker()

        assertNull(tracker.observe(observation(first, RoomState.DISCOVERED)))
        assertEquals(RoomAlertKind.CLEARED, tracker.observe(observation(first, RoomState.CLEARED)))
        assertNull(tracker.observe(observation(first, RoomState.CLEARED)))
        assertEquals(RoomAlertKind.SECRETS_DONE, tracker.observe(observation(first, RoomState.GREEN)))
        assertNull(tracker.observe(observation(first, RoomState.DISCOVERED)))
        assertNull(tracker.observe(observation(first, RoomState.GREEN)))
        assertNull(tracker.observe(observation(first, RoomState.CLEARED)))
    }

    @Test
    fun `entering a different room never reports its existing state`() {
        val tracker = RoomAlertTracker()

        tracker.observe(observation(first, RoomState.DISCOVERED))
        assertNull(tracker.observe(observation(second, RoomState.GREEN)))
        assertNull(tracker.observe(null))
        assertNull(tracker.observe(observation(first, RoomState.CLEARED)))
    }

    @Test
    fun `late component merge preserves delivered markers`() {
        val tracker = RoomAlertTracker()
        val merged = first + DungeonMapData.Cell(2, 2)

        tracker.observe(observation(first, RoomState.DISCOVERED))
        assertEquals(RoomAlertKind.CLEARED, tracker.observe(observation(first, RoomState.CLEARED)))
        assertNull(tracker.observe(observation(merged, RoomState.DISCOVERED)))
        assertNull(tracker.observe(observation(merged, RoomState.CLEARED)))
    }

    @Test
    fun `non gameplay rooms and green rooms without secrets stay silent`() {
        val tracker = RoomAlertTracker()

        tracker.observe(observation(first, RoomState.DISCOVERED, type = RoomType.ENTRANCE))
        assertNull(tracker.observe(observation(first, RoomState.CLEARED, type = RoomType.ENTRANCE)))

        tracker.observe(observation(second, RoomState.DISCOVERED, secrets = 0))
        assertNull(tracker.observe(observation(second, RoomState.GREEN, secrets = 0)))
    }

    @Test
    fun `reset starts a fresh dungeon without alerting on its baseline`() {
        val tracker = RoomAlertTracker()

        tracker.observe(observation(first, RoomState.DISCOVERED))
        assertEquals(RoomAlertKind.CLEARED, tracker.observe(observation(first, RoomState.CLEARED)))

        tracker.reset()
        assertNull(tracker.observe(observation(first, RoomState.DISCOVERED)))
        assertEquals(RoomAlertKind.CLEARED, tracker.observe(observation(first, RoomState.CLEARED)))
    }

    private fun observation(
        components: Set<DungeonMapData.Cell>,
        state: RoomState,
        type: RoomType = RoomType.NORMAL,
        secrets: Int = 3,
    ) = RoomAlertObservation(components, state, type, secrets)
}
