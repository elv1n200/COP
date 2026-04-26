package com.github.noamm9.critsaddons.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.MathUtils.calcYawPitch
import com.github.noamm9.utils.MathUtils.interpolateYaw
import com.github.noamm9.utils.MathUtils.lerp
import com.github.noamm9.utils.PlayerUtils.rotate
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.min

object BlockAimUtils {
    private fun easeInOutCubic(progress: Double): Double {
        return if (progress < 0.5) {
            4.0 * progress * progress * progress
        } else {
            1.0 - Math.pow(-2.0 * progress + 2.0, 3.0) / 2.0
        }
    }

    fun blockCenter(pos: BlockPos, yOffset: Double = 0.5): Vec3 {
        return Vec3(pos.x + 0.5, pos.y + yOffset, pos.z + 0.5)
    }

    fun blockFaceCenter(pos: BlockPos, face: Direction): Vec3 {
        return blockCenter(pos).add(face.stepX * 0.5, face.stepY * 0.5, face.stepZ * 0.5)
    }

    suspend fun aimAt(target: Vec3, time: Long, block: suspend () -> Unit = {}) {
        val player = mc.player ?: return
        val (yaw, pitch) = calcYawPitch(target)

        val currentYaw = MathUtils.normalizeYaw(player.yRot)
        val currentPitch = MathUtils.normalizePitch(player.xRot)
        val targetYaw = MathUtils.normalizeYaw(yaw)
        val targetPitch = MathUtils.normalizePitch(pitch)
        val tolerance = 1f

        if (abs(currentYaw - targetYaw) <= tolerance && abs(currentPitch - targetPitch) <= tolerance) {
            block()
            return
        }

        val startTime = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = if (time <= 0) 1.0 else min(elapsed.toDouble() / time, 1.0)

            if (progress >= 1.0) {
                rotate(targetYaw, targetPitch)
                block()
                break
            }

            val easedProgress = easeInOutCubic(progress).toFloat()
            val newYaw = interpolateYaw(currentYaw, targetYaw, easedProgress)
            val newPitch = lerp(currentPitch, targetPitch, easedProgress).toFloat()
            rotate(newYaw, newPitch)
            delay(1)
        }
    }
}
