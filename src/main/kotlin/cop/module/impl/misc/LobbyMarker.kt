package cop.module.impl.misc

import cop.api.events.TickEvent
import cop.api.skyblock.Island
import cop.api.skyblock.Location
import cop.module.Module
import cop.utils.ChatUtils.modMessage

/**
 * Tracks the Hypixel mini-server identifiers (e.g. `mini42A`) the player has
 * visited in the current session and alerts when one is re-entered. Mainly
 * useful in Crystal Hollows where players `/swap` repeatedly looking for a
 * fresh route, and want to know they've already mined this lobby out.
 *
 * @author elvin
 */
object LobbyMarker : Module(
    "Lobby Marker",
    desc = "Alerts when you re-enter a Hypixel lobby you've already been in this session.",
) {
    private val onlyCrystalHollows by switch(
        "Only in Crystal Hollows", true,
        desc = "Limit the warning to Crystal Hollows lobbies (the typical /swap-grind scenario).",
    )

    private val seen = mutableSetOf<String>()
    private var lastServer: String? = null

    init {
        on<TickEvent.End> {
            val current = Location.currentServer ?: return@on
            if (current == lastServer) return@on
            lastServer = current

            if (onlyCrystalHollows && Location.currentArea != Island.CrystalHollows) return@on

            if (!seen.add(current)) {
                modMessage("&eLobby Marker: you've been in &f$current&e this session before.")
            }
        }
    }
}
