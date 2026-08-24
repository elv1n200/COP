package cop.module.impl.player

import net.minecraft.client.KeyMapping
import cop.api.events.KeyEvent
import cop.mixins.accessors.KeyMappingAccessor
import cop.module.Module

/**
 * Port of Athen `SnapTap` (xyz.aerii.athen.modules.impl.general.SnapTap).
 *
 * Counter-Strike-style opposing-key override: when you tap the opposite WASD key while
 * already holding one, the previously-held key is auto-released for the duration of the
 * overlap and automatically re-engaged when you let go of the new one. Useful for
 * sharper A↔D strafe switches and W↔S walk-back adjustments.
 */
object SnapTap : Module(
    "Snap-Tap",
    desc = "Counter-strafe: tapping the opposite WASD key instantly releases the one you're holding."
) {
    private val active = HashSet<Int>(4)
    private var pairs: List<Pair>? = null

    private data class Pair(val curr: KeyMapping, val oppo: KeyMapping) {
        val key: Int get() = (curr as KeyMappingAccessor).key.value
        val opp: Int get() = (oppo as KeyMappingAccessor).key.value
    }

    private fun resolvePairs(): List<Pair>? {
        if (pairs != null) return pairs
        val options = mc.options
        pairs = listOf(
            Pair(options.keyLeft, options.keyRight),
            Pair(options.keyRight, options.keyLeft),
            Pair(options.keyUp, options.keyDown),
            Pair(options.keyDown, options.keyUp),
        )
        return pairs
    }

    init {
        on<KeyEvent.Press> {
            if (mc.screen != null) return@on
            if (active.add(key)) key.pair(false)
        }

        on<KeyEvent.Release> {
            if (mc.screen != null) {
                active.clear()
                return@on
            }
            if (active.remove(key)) key.pair(true)
        }
    }

    private fun Int.pair(restore: Boolean) {
        val pairs = resolvePairs() ?: return
        for (p in pairs) {
            if (p.key != this) continue
            if (p.opp !in active) return
            p.oppo.isDown = restore
            return
        }
    }
}
