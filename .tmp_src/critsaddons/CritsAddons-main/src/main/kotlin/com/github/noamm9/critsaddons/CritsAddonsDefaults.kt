package com.github.noamm9.critsaddons

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.MOD_NAME
import java.io.File

object CritsAddonsDefaults {
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true

        copyIfMissing(
            resourcePath = "defaults/NoammAddons/config.json",
            target = File("config/$MOD_NAME/config.json")
        )
        copyIfMissing(
            resourcePath = "defaults/NoammAddons/secretRoutes.json",
            target = File("config/$MOD_NAME/secretRoutes.json")
        )
        copyIfMissing(
            resourcePath = "defaults/CritsAddons/fonts/BubbleLetters_Filled_TrueFix.ttf",
            target = File("config/CritsAddons/fonts/BubbleLetters_Filled_TrueFix.ttf")
        )
    }

    private fun copyIfMissing(resourcePath: String, target: File) {
        if (target.exists()) return

        val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return
        runCatching {
            target.parentFile?.mkdirs()
            stream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            NoammAddons.logger.info("Installed CritsAddons default file: ${target.path}")
        }.onFailure {
            NoammAddons.logger.warn("Failed to install CritsAddons default file: ${target.path}", it)
        }
    }
}
