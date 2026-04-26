package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import kotlin.math.abs
import kotlin.math.sign

object Zoom : Feature(
    name = "Zoom",
    description = "Hold a key to zoom, scroll to change zoom amount, and smooth camera turning while zoomed."
) {
    private const val SETTINGS_SECTION = "zoom"
    private const val MIN_ZOOM = 1.0
    private const val MAX_ZOOM = 50.0
    private const val MIN_SMOOTH_ALPHA = 0.02

    private val zoomKeybind by KeybindSetting("Zoom Keybind")
        .section(SETTINGS_SECTION)
        .withDescription("Hold this key to zoom in.")

    private val zoomAmount by SliderSetting("Zoom Amount", 4.0, 1.0, 20.0, 0.1)
        .section(SETTINGS_SECTION)
        .withDescription("Base zoom multiplier. Higher = more zoom.")

    private val rotationSmoothness by SliderSetting("Rotation Smoothness", 40, 0, 100, 1)
        .section(SETTINGS_SECTION)
        .withDescription("0 = normal mouse turn. 100 = very slow smoothed turn while zoomed.")

    private var zoomHeld = false
    private var scrollZoomOffset = 0.0
    private var targetZoomDivisor = 1.0
    private var currentZoomDivisor = 1.0

    override fun init() {
        register<TickEvent.Start> {
            val player = mc.player
            val shouldZoom = enabled &&
                player != null &&
                mc.screen == null &&
                zoomKeybind.isDown()

            zoomHeld = shouldZoom
            targetZoomDivisor = if (shouldZoom) effectiveZoomDivisor() else 1.0

            if (shouldZoom) {
                // Entering/holding zoom should feel immediate.
                currentZoomDivisor = targetZoomDivisor
            } else {
                val blend = 0.45
                currentZoomDivisor += (targetZoomDivisor - currentZoomDivisor) * blend
                if (abs(currentZoomDivisor - targetZoomDivisor) < 0.001) {
                    currentZoomDivisor = targetZoomDivisor
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        zoomHeld = false
        scrollZoomOffset = 0.0
        targetZoomDivisor = 1.0
        currentZoomDivisor = 1.0
    }

    @JvmStatic
    fun modifyFov(baseFov: Float): Float {
        if (!enabled) return baseFov
        val divisor = currentZoomDivisor.coerceAtLeast(MIN_ZOOM)
        return (baseFov / divisor.toFloat()).coerceIn(1.0f, 179.0f)
    }

    @JvmStatic
    fun consumeScroll(horizontal: Double, vertical: Double): Boolean {
        if (!enabled || !zoomHeld || mc.screen != null) return false

        val delta = if (abs(vertical) >= abs(horizontal)) vertical else -horizontal
        if (delta == 0.0) return true

        val stepped = sign(delta) * 0.25
        val minOffset = MIN_ZOOM - zoomAmount.value
        val maxOffset = MAX_ZOOM - zoomAmount.value
        scrollZoomOffset = (scrollZoomOffset + stepped).coerceIn(minOffset, maxOffset)
        targetZoomDivisor = effectiveZoomDivisor()
        return true
    }

    @JvmStatic
    fun isRotationSmoothingActive(): Boolean {
        return enabled && zoomHeld && rotationSmoothness.value > 0
    }

    @JvmStatic
    fun getRotationSmoothingAlpha(): Double {
        val normalized = (rotationSmoothness.value / 100.0).coerceIn(0.0, 1.0)
        return (1.0 - (normalized * (1.0 - MIN_SMOOTH_ALPHA))).coerceIn(MIN_SMOOTH_ALPHA, 1.0)
    }

    private fun effectiveZoomDivisor(): Double {
        return (zoomAmount.value + scrollZoomOffset).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
