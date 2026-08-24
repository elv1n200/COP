package cop.module.impl.player.cheats

import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Location
import cop.module.Module
import cop.utils.getLook
import cop.utils.predictTransmission
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.traverseVoxels
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * Keeps the local view direction when Hypixel acknowledges a teleport and can
 * render the camera at a locally predicted landing position while that
 * acknowledgement is in flight. The player entity itself is never moved by
 * the prediction, so a failed prediction cannot desynchronise movement.
 */
object NoRotate : Module(
    "No Rotate",
    desc = "Prevents teleport acknowledgements from snapping the camera and optionally predicts the landing camera.",
) {
    private val preserveEtherwarp by switch("No rotate: Etherwarp", true)
    private val preserveTransmission by switch("No rotate: Transmission", true)
    private val preserveWitherImpact by switch("No rotate: Wither Impact", true)
    private val preserveCorrections by switch(
        "No rotate: Other corrections",
        false,
        desc = "Also preserves your view for non-teleport server position corrections.",
    )

    private val zeroPingEtherwarp by switch(
        "Zero-ping camera: Etherwarp",
        false,
        desc = "Immediately renders the camera at the predicted Etherwarp landing position.",
    )
    private val zeroPingTransmission by switch(
        "Zero-ping camera: Transmission",
        false,
        desc = "Immediately renders the camera at the predicted AOTE/AOTV landing position.",
    )
    private val zeroPingWitherImpact by switch(
        "Zero-ping camera: Wither Impact",
        false,
        desc = "Immediately renders the camera at the predicted Wither Impact landing position.",
    )
    private val predictionTimeout by slider(
        "Prediction timeout",
        650,
        250,
        1_500,
        50,
        unit = "ms",
        desc = "Maximum time a locally predicted camera position remains active.",
    )

    @Volatile
    private var pending: PendingTeleport? = null

    init {
        on<PacketEvent.Sent, ServerboundUseItemPacket> {
            if (!Location.inSkyblock || packet.hand == InteractionHand.OFF_HAND) return@on
            recordUse(player.getItemInHand(packet.hand), packet.yRot, packet.xRot)
        }

        on<PacketEvent.Sent, ServerboundUseItemOnPacket> {
            if (!Location.inSkyblock || packet.hand == InteractionHand.OFF_HAND) return@on
            // Use-on-block packets do not carry rotation. The local view is the
            // exact rotation vanilla used to produce the hit result.
            recordUse(player.getItemInHand(packet.hand), player.yRot, player.xRot)
        }

        on<TickEvent.End> {
            pending?.takeIf { it.expiresAt <= System.currentTimeMillis() }?.let { pending = null }
        }

        on<WorldEvent.Change> { pending = null }
    }

    override fun onDisable() {
        pending = null
        super.onDisable()
    }

    private fun recordUse(stack: ItemStack, yaw: Float, pitch: Float) {
        val info = teleportInfo(stack) ?: return
        if (!preserve(info.kind) && !zeroPing(info.kind)) return

        val landing = if (zeroPing(info.kind)) predictLanding(info, yaw, pitch) else null
        pending = PendingTeleport(info.kind, landing, System.currentTimeMillis() + predictionTimeout)
    }

    private fun teleportInfo(stack: ItemStack): TeleportInfo? {
        val id = stack.skyblockId?.uppercase() ?: return null
        val lore = stack.lore.orEmpty()
        val loreText = lore.joinToString("\n")

        val hasEthermerge = stack.extraAttributes?.getInt("ethermerge")?.orElse(0) == 1
        if (player.isShiftKeyDown && (hasEthermerge || id == "ETHERWARP_CONDUIT")) {
            val distance = lore.firstNotNullOfOrNull { ETHERWARP_DISTANCE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?: 60.0
            return TeleportInfo(TeleportKind.ETHERWARP, distance)
        }

        if (id in TRANSMISSION_ITEMS) {
            val fallback = if (id == "ASPECT_OF_THE_END") 8.0 else 12.0
            val distance = lore.firstNotNullOfOrNull { TRANSMISSION_DISTANCE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?: fallback
            return TeleportInfo(TeleportKind.TRANSMISSION, distance)
        }

        if (id in WITHER_BLADES && loreText.contains("Wither Impact", ignoreCase = true)) {
            return TeleportInfo(TeleportKind.WITHER_IMPACT, 10.0)
        }

        return null
    }

    private fun predictLanding(info: TeleportInfo, yaw: Float, pitch: Float): Vec3? {
        val p = mc.player ?: return null
        val look = getLook(yaw, pitch)

        return when (info.kind) {
            TeleportKind.ETHERWARP -> {
                val eye = p.eyePosition
                val hit = traverseVoxels(eye, eye.add(look.scale(info.distance)), etherwarp = true)
                val pos = hit.pos?.takeIf { hit.succeeded } ?: return null
                val state = hit.state ?: return null
                val top = runCatching {
                    state.getCollisionShape(level, pos).max(Direction.Axis.Y)
                }.getOrDefault(1.0)
                Vec3(pos.x + 0.5, pos.y + max(1.0, top) + 0.05, pos.z + 0.5)
            }

            TeleportKind.TRANSMISSION,
            TeleportKind.WITHER_IMPACT -> {
                val hit = predictTransmission(p.x, p.y, p.z, look.x, look.y, look.z, info.distance)
                hit.pos?.takeIf { hit.succeeded }?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            }
        }
    }

    private fun preserve(kind: TeleportKind): Boolean = when (kind) {
        TeleportKind.ETHERWARP -> preserveEtherwarp
        TeleportKind.TRANSMISSION -> preserveTransmission
        TeleportKind.WITHER_IMPACT -> preserveWitherImpact
    }

    private fun zeroPing(kind: TeleportKind): Boolean = when (kind) {
        TeleportKind.ETHERWARP -> zeroPingEtherwarp
        TeleportKind.TRANSMISSION -> zeroPingTransmission
        TeleportKind.WITHER_IMPACT -> zeroPingWitherImpact
    }

    @JvmStatic
    fun shouldPreserveRotation(): Boolean {
        if (!enabled || !Location.inSkyblock) return false
        val current = pending
        if (current != null && current.expiresAt > System.currentTimeMillis()) return preserve(current.kind)
        return preserveCorrections
    }

    /** First-person camera position used by [cop.mixins.CameraMixin]. */
    @JvmStatic
    fun predictedCameraPosition(): Vec3? {
        if (!enabled) return null
        val current = pending ?: return null
        if (current.expiresAt <= System.currentTimeMillis()) {
            pending = null
            return null
        }
        val feet = current.landing ?: return null
        val eyeHeight = mc.player?.eyeHeight?.toDouble() ?: return null
        return feet.add(0.0, eyeHeight, 0.0)
    }

    /** Called after vanilla has accepted the authoritative teleport position. */
    @JvmStatic
    fun onTeleportApplied() {
        pending = null
    }

    private data class PendingTeleport(
        val kind: TeleportKind,
        val landing: Vec3?,
        val expiresAt: Long,
    )

    private data class TeleportInfo(val kind: TeleportKind, val distance: Double)
    private enum class TeleportKind { ETHERWARP, TRANSMISSION, WITHER_IMPACT }

    private val ETHERWARP_DISTANCE = Regex("Etherwarp(?: up to)? (\\d+) blocks", RegexOption.IGNORE_CASE)
    private val TRANSMISSION_DISTANCE = Regex("Teleport (\\d+) blocks", RegexOption.IGNORE_CASE)
    private val TRANSMISSION_ITEMS = setOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID")
    private val WITHER_BLADES = setOf("NECRON_BLADE", "HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
}
