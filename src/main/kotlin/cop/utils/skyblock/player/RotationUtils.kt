package cop.utils.skyblock.player

import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth.wrapDegrees
import cop.CopMod.mc
import cop.annotations.Init
import cop.api.animations.Animation
import cop.api.events.MouseEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus.on
import cop.utils.Direction

@Init
object RotationUtils {

    private var rotationTask: (LocalPlayer.() -> Boolean)? = null

    init {
        on<RenderEvent.World> {
            val player = mc.player ?: return@on

            while (rotationTask != null) {
                val current = rotationTask!!
                if (player.current()) {
                    if (rotationTask === current) {
                        rotationTask = null
                        break
                    }
                } else break
            }
        }

        on<MouseEvent.Move> {
            if (rotationTask != null) cancel()
        }

        on<WorldEvent.Change> {
            rotationTask = null
        }
    }

    fun rotationTask(task: (LocalPlayer.() -> Boolean)?): Boolean {
        val player = mc.player ?: return false
        rotationTask = task
        return if (task != null) player.task() else true
    }

    fun cancelRotationTask() {
        rotationTask = null
    }

    var LocalPlayer.yaw
        get() = wrapDegrees(this.yRot)
        set(v) {
            this.yRot = v
            this.yHeadRot = v
        }

    var LocalPlayer.pitch
        get() = this.xRot
        set(v) {
            this.xRot = v
        }

    fun LocalPlayer.rotate(yaw: Number = this.yaw, pitch: Number = this.pitch) {
        this.yaw = yaw.toFloat()
        this.pitch = pitch.toFloat()
    }

    fun LocalPlayer.rotate(dir: Direction) = this.rotate(dir.yaw, dir.pitch)

    fun LocalPlayer.rotateSmoothly(
        yaw: Float,
        pitch: Float,
        duration: Float,
        style: Animation.Style = Animation.Style.EaseOutQuint,
        onFinish: (() -> Unit)? = null
    ) {
        var initialised = false
        var startYaw = 0f
        var startPitch = 0f
        var deltaYaw = 0f
        var deltaPitch = 0f
        lateinit var anim: Animation

        rotationTask {
            if (!initialised) {
                startYaw = this.yaw
                startPitch = this.pitch
                deltaYaw = wrapDegrees(yaw - startYaw)
                deltaPitch = pitch - startPitch
                anim = Animation(duration, style).onFinish { onFinish?.invoke() }
                initialised = true
            }

            val progress = anim.get()

            this.rotate(
                startYaw + (deltaYaw * progress),
                startPitch + (deltaPitch * progress)
            )

            anim.finished
        }
    }

    fun LocalPlayer.rotateSmoothly(
        dir: Direction,
        duration: Float,
        style: Animation.Style = Animation.Style.EaseOutQuint,
        onFinish: (() -> Unit)? = null
    ) = this.rotateSmoothly(dir.yaw, dir.pitch, duration, style, onFinish)
}