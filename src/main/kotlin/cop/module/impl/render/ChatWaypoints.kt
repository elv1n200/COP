package cop.module.impl.render

import cop.api.colour.Colour
import cop.api.events.ChatEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Location
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.StringUtils.noControlCodes
import cop.utils.StringUtils.toFixed
import cop.utils.render.drawFilledBox
import cop.utils.render.drawText
import cop.utils.render.drawWireFrameBox
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Creates short-lived, local waypoints from coordinates posted in Hypixel
 * party/public chat. Concept reference: Quoi 26.1.x `Waypoints`; parsing,
 * validation, bounded storage and rendering are implemented for COP.
 */
object ChatWaypoints : Module(
    "Chat Waypoints",
    desc = "Creates temporary local waypoints from x/y/z coordinates in chat.",
) {
    private val partyChat by switch(
        "Party chat", true,
        desc = "Accept coordinate messages from party chat.",
    )
    private val partyDuration by slider(
        "Party duration", 120, 5, 600, 5, unit = "s",
        desc = "How long party waypoints remain visible.",
    ).childOf(::partyChat)
    private val partyColour by colourPicker(
        "Party colour", Colour.RGB(85, 125, 255, 0.67f), allowAlpha = true,
    ).childOf(::partyChat)

    private val publicChat by switch(
        "Public chat", false,
        desc = "Accept coordinate messages from normal public chat. Off by default to avoid spam.",
    )
    private val publicDuration by slider(
        "Public duration", 60, 5, 600, 5, unit = "s",
        desc = "How long public-chat waypoints remain visible.",
    ).childOf(::publicChat)
    private val publicColour by colourPicker(
        "Public colour", Colour.RGB(85, 220, 220, 0.67f), allowAlpha = true,
    ).childOf(::publicChat)

    private val includeOwnMessages by switch(
        "Own messages", true,
        desc = "Also create a waypoint when your own coordinate message is echoed by the server.",
    )
    private val clearOnArrival by switch(
        "Clear on arrival", true,
        desc = "Remove a waypoint after approaching it.",
    )
    private val arrivalRadius by slider(
        "Arrival radius", 8, 2, 32, 1, unit = "m",
    ).childOf(::clearOnArrival)
    private val depthCheck by switch(
        "Depth check", false,
        desc = "Hide waypoint geometry behind blocks.",
    )

    private val waypoints = mutableListOf<Waypoint>()

    init {
        on<ChatEvent.PacketClient> {
            if (!Location.onHypixel) return@on
            val parsed = ChatWaypointParser.parse(message.noControlCodes) ?: return@on
            if (parsed.source == ChatWaypointSource.PARTY && !partyChat) return@on
            if (parsed.source == ChatWaypointSource.PUBLIC && !publicChat) return@on
            if (!includeOwnMessages && parsed.sender.equals(mc.player?.name?.string, ignoreCase = true)) return@on

            addWaypoint(parsed)
        }

        on<RenderEvent.World> {
            val player = mc.player ?: return@on
            val now = System.currentTimeMillis()
            val iterator = waypoints.iterator()
            while (iterator.hasNext()) {
                val waypoint = iterator.next()
                val distance = player.position().distanceTo(waypoint.center)
                if (now >= waypoint.expiresAt || clearOnArrival && distance <= arrivalRadius) {
                    iterator.remove()
                    continue
                }

                val box = AABB(waypoint.pos).inflate(0.01)
                ctx.drawFilledBox(box, waypoint.colour, depthCheck)
                ctx.drawWireFrameBox(box, waypoint.colour, 3f, depthCheck)
                ctx.drawText(
                    Component.literal("${waypoint.name} §7(${distance.toFixed(1)}m)"),
                    waypoint.center.add(0.0, 1.15, 0.0),
                    shadow = true,
                    scale = 0.8f,
                    depth = depthCheck,
                )
            }
        }

        on<WorldEvent.Change> { waypoints.clear() }
    }

    override fun onDisable() {
        super.onDisable()
        waypoints.clear()
    }

    private fun addWaypoint(parsed: ParsedChatWaypoint) {
        val now = System.currentTimeMillis()
        val pos = BlockPos(parsed.x, parsed.y, parsed.z)

        // One live marker per sender/source; a newer message replaces the old
        // coordinate. Exact-position duplicates from other senders are folded.
        waypoints.removeIf {
            it.pos == pos || it.source == parsed.source && it.name.equals(parsed.sender, ignoreCase = true)
        }
        while (waypoints.size >= MAX_WAYPOINTS) waypoints.removeFirst()

        val durationSeconds = when (parsed.source) {
            ChatWaypointSource.PARTY -> partyDuration
            ChatWaypointSource.PUBLIC -> publicDuration
        }
        val colour = when (parsed.source) {
            ChatWaypointSource.PARTY -> partyColour
            ChatWaypointSource.PUBLIC -> publicColour
        }
        waypoints += Waypoint(
            name = parsed.sender,
            source = parsed.source,
            pos = pos,
            expiresAt = now + durationSeconds * 1_000L,
            colour = colour,
        )
    }

    private data class Waypoint(
        val name: String,
        val source: ChatWaypointSource,
        val pos: BlockPos,
        val expiresAt: Long,
        val colour: Colour,
    ) {
        val center: Vec3 get() = Vec3.atCenterOf(pos)
    }

    private const val MAX_WAYPOINTS = 32
}
