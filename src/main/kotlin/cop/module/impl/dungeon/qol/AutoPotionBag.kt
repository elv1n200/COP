package cop.module.impl.dungeon.qol

import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.ChatUtils.command
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes

/**
 * Adapted from AutoPotionBag by ChickenWing_King
 * (de.chickenwingking.autopotionbag) — original hardcoded the author's IGN; this
 * uses the local player's account name so it works for anyone.
 *
 * When "<your IGN> is now ready!" shows up in chat, waits a short delay and runs
 * /potionbag to re-apply potion effects. A lock prevents re-triggering until the
 * command has been sent.
 */
object AutoPotionBag : Module(
    "Auto Potion Bag",
    desc = "Runs /potionbag automatically when your potion effects are ready again."
) {
    private val delay by slider("Delay", 2.0f, 0.0f, 5.0f, 0.1f, desc = "Seconds to wait before running /potionbag.", unit = "s")

    private var locked = false

    init {
        on<WorldEvent.Change> { locked = false }

        on<ChatEvent.Packet> {
            if (locked) return@on
            val name = mc.player?.gameProfile?.name ?: return@on
            val plain = message.noControlCodes
            if (!plain.contains(name) || !plain.contains("is now ready!")) return@on

            locked = true
            scheduleTask((delay * 20f).toInt()) {
                command("potionbag")
                locked = false
            }
        }
    }
}
