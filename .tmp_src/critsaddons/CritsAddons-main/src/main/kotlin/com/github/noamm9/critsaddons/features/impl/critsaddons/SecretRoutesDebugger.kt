package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.NoammAddons
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils

object SecretRoutesDebugger : Feature(
    description = "Debug output controls for Secret Routes playback and silent packet flow.",
    name = "Secret Routes Debugger",
    toggled = true
) {
    private val debuggerEnabled by ToggleSetting("Enable Debugger", false)
        .section("general")
        .withDescription("Master toggle for Secret Routes debug output.")
    private val chatOutput by ToggleSetting("Chat Output", true)
        .section("general")
        .showIf { debuggerEnabled.value }
        .withDescription("Print debug lines in chat.")
    private val logOutput by ToggleSetting("Log Output", true)
        .showIf { debuggerEnabled.value }
        .withDescription("Print debug lines in latest.log.")

    private val planDebug by ToggleSetting("Plan Matching", true)
        .section("channels")
        .showIf { debuggerEnabled.value }
    private val autoStartDebug by ToggleSetting("Auto Start", true)
        .showIf { debuggerEnabled.value }
    private val stepDebug by ToggleSetting("Step Execution", true)
        .showIf { debuggerEnabled.value }
    private val etherwarpDebug by ToggleSetting("Etherwarp", true)
        .showIf { debuggerEnabled.value }
    private val packetDebug by ToggleSetting("Packet Payloads", true)
        .showIf { debuggerEnabled.value }
    private val manaDebug by ToggleSetting("Mana Wait", false)
        .showIf { debuggerEnabled.value }
    private val waitDebug by ToggleSetting("Wait Steps", false)
        .showIf { debuggerEnabled.value }
    private val failureDebug by ToggleSetting("Failures", true)
        .showIf { debuggerEnabled.value }
    private val recordingDebug by ToggleSetting("Recording", false)
        .showIf { debuggerEnabled.value }

    override fun init() {}

    fun plan(message: () -> String) = emit(planDebug.value, "PLAN", message)
    fun autoStart(message: () -> String) = emit(autoStartDebug.value, "AUTO", message)
    fun step(message: () -> String) = emit(stepDebug.value, "STEP", message)
    fun etherwarp(message: () -> String) = emit(etherwarpDebug.value, "ETHER", message)
    fun packet(message: () -> String) = emit(packetDebug.value, "PACKET", message)
    fun mana(message: () -> String) = emit(manaDebug.value, "MANA", message)
    fun waitStep(message: () -> String) = emit(waitDebug.value, "WAIT", message)
    fun failure(message: () -> String) = emit(failureDebug.value, "FAIL", message)
    fun recording(message: () -> String) = emit(recordingDebug.value, "REC", message)

    private fun emit(channelEnabled: Boolean, tag: String, message: () -> String) {
        if (!debuggerEnabled.value || !channelEnabled) return
        val text = message()
        val line = "[SecretRoutes:$tag] $text"
        if (logOutput.value) {
            NoammAddons.logger.info(line)
        }
        if (chatOutput.value) {
            ChatUtils.modMessage("&8[&bSRDBG&8:&3$tag&8] &7$text")
        }
    }
}
