package cop.module.impl.misc.economy

import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Location
import cop.api.skyblock.SkyblockPlayer
import cop.module.Module
import cop.utils.ChatUtils.command
import cop.utils.StringUtils.noControlCodes

object EscrowFix : Module(
    "Escrow Fix",
    desc = "Reopens the Auction House or Bazaar when an escrow response closes it.",
) {
    private val cooldown by slider(
        "Retry cooldown",
        1_000L,
        500L,
        3_000L,
        100L,
        unit = "ms",
        desc = "Prevents retry loops if Hypixel returns repeated errors.",
    )

    private var lastRetryAt = 0L

    init {
        on<WorldEvent.Change> { lastRetryAt = 0L }

        on<ChatEvent.PacketClient> {
            if (!Location.inSkyblock || !SkyblockPlayer.canUseCommands) return@on
            val clean = message.noControlCodes.trim()
            val retry = when {
                clean in AUCTION_MESSAGES -> "ah"
                BAZAAR_REFUND.matches(clean) -> "bz"
                else -> return@on
            }

            val now = System.currentTimeMillis()
            if (now - lastRetryAt < cooldown) return@on
            lastRetryAt = now
            command(retry)
        }
    }

    private val AUCTION_MESSAGES = setOf(
        "There was an error with the auction house! (AUCTION_EXPIRED_OR_NOT_FOUND)",
        "There was an error with the auction house! (INVALID_BID)",
    )
    private val BAZAAR_REFUND = Regex("^Escrow refunded [\\d,]+ coins for Bazaar Instant Buy Submit!$")
}
