package cop.module.impl.dungeon.cheats

import net.minecraft.sounds.SoundEvents
import cop.api.colour.Colour
import cop.api.events.KeyEvent
import cop.api.events.MouseEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.SoundUtils
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.ui.textPair

/**
 * Port of Hunchclient BonzoStaffHelperModule (dev.hunchclient.module.impl.dungeons) — full behaviour.
 *
 *   - S-Taps the player automatically just before the Bonzo Staff explosion hits so the boost is
 *     caught even when you're already sprinting at max speed. The tap is injected through
 *     KeyEvent.Input (same path COP's AutoClear uses), so no raw KeyMapping state is touched.
 *   - Experimental mode sets horizontal velocity to zero every tick while the staff is charging
 *     if you're on the ground — only applied on-ground so air trajectories are untouched.
 *   - HUD shows live tick countdown + the current phase (charging / TAP S / done).
 *   - Adaptive suggestions: prints timing advice in chat based on recent attempts.
 *
 * Settings mirror the Hunch original: explosion delay, S-tap duration, adaptive timing,
 * experimental mode.
 */
object BonzoStaffHelper : Module(
    "Bonzo Staff Helper",
    desc = "Auto S-tap & velocity cancel for Bonzo Staff boosts; catches the explosion even at max speed."
) {
    private val explosionDelay by slider("Explosion delay", 8, 1, 20, 1,
        desc = "Ticks between right-click and explosion (tune for your ping).", unit = "t")
    private val sTapDuration by slider("S-Tap duration", 4, 1, 10, 1,
        desc = "How many ticks we hold backward (S) right before the explosion.", unit = "t")
    private val adaptiveTiming by switch("Adaptive timing", true,
        desc = "Logs per-attempt stats and suggests better timings in chat.")
    private val experimentalMode by switch("Experimental mode",
        desc = "Cancels horizontal velocity while on ground until the explosion hits. Uses mana.")
    private val soundCue by switch("Sound cue", true,
        desc = "Plays a rising pling at the start of the S-tap window.")

    // Runtime state
    private var wasRightClicking = false
    private var waitingForExplosion = false
    private var ticksSinceClick = 0

    // Stats
    private var totalAttempts = 0
    private var successfulBoosts = 0
    private var averageBoostStrength = 0.0
    private var velocityBeforeStaff = 0.0
    private var maxVelocityAfterStaff = 0.0
    private var ticksAtMaxVelocity = 0

    private fun isHoldingBonzoStaff(): Boolean {
        val player = mc.player ?: return false
        if (player.mainHandItem.skyblockId == "BONZO_STAFF") return true
        if (player.offhandItem.skyblockId == "BONZO_STAFF") return true
        return false
    }

    init {
        on<WorldEvent.Change> {
            wasRightClicking = false
            waitingForExplosion = false
            ticksSinceClick = 0
        }

        // Detect the right-click that fires the staff. We use MouseEvent.Click (edge-triggered),
        // so this only fires once per press — matching Hunch's "isRightClicking && !wasRightClicking".
        on<MouseEvent.Click> {
            if (button != 1 || !state) return@on
            if (waitingForExplosion) return@on
            if (!isHoldingBonzoStaff()) return@on
            val player = mc.player ?: return@on

            waitingForExplosion = true
            ticksSinceClick = 0
            totalAttempts++

            velocityBeforeStaff = player.deltaMovement.horizontalDistance()
            maxVelocityAfterStaff = velocityBeforeStaff
            ticksAtMaxVelocity = 0
        }

        // Per-tick logic: track velocity, run experimental velocity cancel, play the cue sound,
        // and wrap up the attempt with stats when the explosion window has passed.
        on<TickEvent.End> {
            if (!waitingForExplosion) return@on
            val player = mc.player ?: run {
                waitingForExplosion = false
                return@on
            }

            ticksSinceClick++

            val currentVelocity = player.deltaMovement.horizontalDistance()
            if (currentVelocity > maxVelocityAfterStaff) {
                maxVelocityAfterStaff = currentVelocity
                ticksAtMaxVelocity = ticksSinceClick
            }

            val delay = explosionDelay
            val window = sTapDuration
            val windowStart = (delay - window).coerceAtLeast(0)

            // Experimental: zero horizontal velocity while on ground during the charge
            if (experimentalMode && ticksSinceClick <= delay && player.onGround()) {
                val dm = player.deltaMovement
                player.setDeltaMovement(0.0, dm.y, 0.0)
            }

            // Sound cue at S-tap window open
            if (soundCue && ticksSinceClick == windowStart) {
                SoundUtils.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.5f)
            }

            // Wrap up
            if (ticksSinceClick >= delay + 5) {
                waitingForExplosion = false

                val gain = maxVelocityAfterStaff - velocityBeforeStaff
                val success = gain > 0.5
                if (success) {
                    successfulBoosts++
                    averageBoostStrength = ((averageBoostStrength * (successfulBoosts - 1)) + gain) / successfulBoosts
                }

                if (adaptiveTiming) {
                    val rate = "%.1f%%".format(successfulBoosts * 100.0 / totalAttempts.coerceAtLeast(1))
                    val mark = if (success) "§a✓" else "§c✗"
                    modMessage("$mark §7Bonzo §f+${"%.2f".format(gain)} §8(peak t=$ticksAtMaxVelocity, rate $rate, avg ${"%.2f".format(averageBoostStrength)})")

                    if (totalAttempts >= 3) {
                        when {
                            ticksAtMaxVelocity in 1 until delay - 1 ->
                                modMessage("§7→ §etry delay §f${ticksAtMaxVelocity + 1}§e for a tighter catch")
                            gain < 0.3 ->
                                modMessage("§7→ §eweak boost — try raising S-tap duration")
                            else -> {}
                        }
                    }
                }
            }
        }

        // Force backward (S) input during the tap window. This is injected into the same MutableInput
        // that the vanilla movement code reads, so the server sees a genuine input flag flip.
        on<KeyEvent.Input> {
            if (!waitingForExplosion) return@on
            if (experimentalMode) return@on  // experimental mode uses velocity cancel instead of S-tap
            val delay = explosionDelay
            val window = sTapDuration
            val windowStart = (delay - window).coerceAtLeast(0)
            if (ticksSinceClick in windowStart..delay) {
                input.backward = true
                // Hunch only tapped S; clear forward to avoid conflicting inputs
                input.forward = false
            }
        }

        textHud(
            name = "Bonzo Staff Status",
            colour = Colour.PINK,
            toggleable = false
        ) {
            visibleIf { this@BonzoStaffHelper.enabled && waitingForExplosion }
            textPair(
                string = "Bonzo:",
                supplier = {
                    val delay = explosionDelay
                    val window = sTapDuration
                    val windowStart = (delay - window).coerceAtLeast(0)
                    when {
                        ticksSinceClick in windowStart..delay -> if (experimentalMode) "§bVEL-CANCEL" else "§cTAP S"
                        ticksSinceClick < windowStart -> "§e${(delay - ticksSinceClick).coerceAtLeast(0)}t"
                        else -> "§adone"
                    }
                },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()
    }
}
