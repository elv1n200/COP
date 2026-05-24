package cop.module.impl.dungeon.qol

import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.M7Phases
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleTask
import cop.utils.SoundUtils
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils
import kotlin.math.roundToInt

/**
 * Port of NoammAddons Ragnarock (com.github.noamm9.features.impl.dungeon.Ragnarock).
 * Reports strength gained when the Ragnarock Axe is fully charged, warns when the
 * buff gets cancelled by damage, and plays a pling melody at M7 P5 when the Wither
 * King taunts (signals "okay to rag now").
 */
object Ragnarock : Module(
    "Ragnarock",
    desc = "Ragnarock Axe helper: gain message, cancel alert, and M7 dragon rag cue."
) {
    private val alertCancelled by switch("Alert Cancelled", true,
        desc = "Shows a title when Ragnarock gets cancelled by a hit.")
    private val strengthGainedMessage by switch("Strength Gained", true,
        desc = "Sends a chat message with the gained strength amount.")
    private val m7Alert by switch("M7 Dragon Alert",
        desc = "Plays a rising pling melody when the Wither King taunts in P5.")

    private const val M7_RAG_MESSAGE = "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you."
    private val cancelRegex = Regex("Ragnarock was cancelled due to (?:being hit|taking damage)!")
    private val strengthRegex = Regex("Strength: \\+(\\d+)")

    private val m7PlingSteps = listOf(
        0 to 1.22f, 2 to 1.13f, 5 to 1.29f,
        8 to 1.60f, 10 to 1.60f, 13 to 1.72f,
        16 to 1.89f
    )

    init {
        on<PacketEvent.Received, ClientboundSoundPacket> {
            if (!strengthGainedMessage) return@on
            val sound = packet.sound.value()
            if (sound.location.path != "entity.wolf.death") return@on
            if (packet.pitch.toDouble() == 1.4920635) return@on
            val item = mc.player?.mainHandItem ?: return@on
            if (item.skyblockId != "RAGNAROCK_AXE") return@on

            val strengthLine = item.lore?.map { it.noControlCodes }
                ?.find { it.startsWith("Strength:") } ?: return@on
            val match = strengthRegex.find(strengthLine) ?: return@on
            val base = match.groupValues[1].toIntOrNull() ?: return@on
            modMessage("&fGained strength: &c${(base * 1.5).roundToInt()}")
        }

        on<ChatEvent.Receive> {
            val text = message.noControlCodes

            when {
                m7Alert && Dungeon.getF7Phase() == M7Phases.P5 && text == M7_RAG_MESSAGE -> {
                    PlayerUtils.setTitle("§drag", "", playSound = false, stayAlive = 30)
                    for ((delayTicks, pitch) in m7PlingSteps) {
                        scheduleTask(delayTicks) {
                            SoundUtils.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, pitch)
                        }
                    }
                }
                alertCancelled && text.matches(cancelRegex) -> {
                    PlayerUtils.setTitle("", "§cRagnarock Cancelled", playSound = false, stayAlive = 30)
                    SoundUtils.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
                }
            }
        }
    }
}
