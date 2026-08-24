package cop.utils.skyblock.player

import cop.annotations.Init
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus.on
import java.util.EnumMap

/**
 * Small cross-module lease manager for invasive automation.
 *
 * A large client can have several helpers wanting to rotate, swap a hotbar slot,
 * click a container or steer movement during the same tick.  The server-facing
 * packet guards catch some of that, but by then one of the helpers has already
 * lost state.  Modules can acquire the channels they need before starting a
 * multi-tick action and cleanly decline when another owner is active.
 */
@Init
object AutomationCoordinator {
    enum class Channel {
        HOTBAR,
        INVENTORY,
        ROTATION,
        MOVEMENT,
        INTERACTION,
    }

    private data class Lease(val owner: String, var expiresAt: Long)

    private val leases = EnumMap<Channel, Lease>(Channel::class.java)

    init {
        on<WorldEvent.Change> { clear() }
        on<TickEvent.End> { purgeExpired(System.currentTimeMillis()) }
    }

    @Synchronized
    fun acquire(owner: String, durationMs: Long, vararg channels: Channel): Boolean {
        require(owner.isNotBlank()) { "automation lease owner must not be blank" }
        if (channels.isEmpty()) return true

        val now = System.currentTimeMillis()
        purgeExpired(now)
        if (channels.any { leases[it]?.owner != null && leases[it]?.owner != owner }) return false

        val expiry = now + durationMs.coerceAtLeast(1L)
        channels.forEach { channel -> leases[channel] = Lease(owner, expiry) }
        return true
    }

    @Synchronized
    fun extend(owner: String, durationMs: Long, vararg channels: Channel): Boolean {
        val now = System.currentTimeMillis()
        purgeExpired(now)
        if (channels.any { leases[it]?.owner != owner }) return false
        val expiry = now + durationMs.coerceAtLeast(1L)
        channels.forEach { leases[it]?.expiresAt = expiry }
        return true
    }

    @Synchronized
    fun release(owner: String, vararg channels: Channel) {
        if (channels.isEmpty()) {
            leases.entries.removeIf { it.value.owner == owner }
        } else {
            channels.forEach { channel ->
                if (leases[channel]?.owner == owner) leases.remove(channel)
            }
        }
    }

    @Synchronized
    fun owner(channel: Channel): String? {
        purgeExpired(System.currentTimeMillis())
        return leases[channel]?.owner
    }

    @Synchronized
    fun clear() = leases.clear()

    @Synchronized
    private fun purgeExpired(now: Long) {
        leases.entries.removeIf { it.value.expiresAt <= now }
    }
}
