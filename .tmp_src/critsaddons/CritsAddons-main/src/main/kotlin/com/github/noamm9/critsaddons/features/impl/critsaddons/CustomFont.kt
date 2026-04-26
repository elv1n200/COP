package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.critsaddons.mixins.MixinFontSetAccessor
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.TextInputSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import com.mojang.blaze3d.platform.TextureUtil
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.providers.FreeTypeUtil
import net.minecraft.resources.ResourceLocation
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import java.awt.Desktop
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Collections

object CustomFont : Feature(
    name = "Custom Font",
    description = "Replaces Minecraft text glyphs with a selected TTF/OTF font."
) {
    private const val SETTINGS_SECTION = "font"
    private val fontsFolder = File("config/CritsAddons/fonts")

    private val fontFile by TextInputSetting("Font File", "")
        .section(SETTINGS_SECTION)
        .withDescription("A .ttf/.otf file name inside config/CritsAddons/fonts, or a full path.")

    private val fontSize by SliderSetting("Font Size", 11.0, 6.0, 32.0, 0.5)
        .section(SETTINGS_SECTION)
        .withDescription("TrueType font size. Vanilla default is 11.")

    private val oversample by SliderSetting("Oversample", 2.0, 1.0, 8.0, 0.5)
        .section(SETTINGS_SECTION)
        .withDescription("Higher values render smoother text, but use more texture memory.")

    private val reloadFontButton by ButtonSetting("Reload Font") {
        reloadMinecraftFonts()
    }.section(SETTINGS_SECTION)
        .withDescription("Reloads Minecraft resources so the selected font is applied.")

    private val openFontsFolderButton by ButtonSetting("Open Fonts Folder") {
        openFontsFolder()
    }.section(SETTINGS_SECTION)
        .withDescription("Opens config/CritsAddons/fonts. Put your .ttf/.otf files here.")

    @Volatile
    private var customProvider: TrueTypeGlyphProvider? = null

    @Volatile
    private var lastLoadedKey: FontKey? = null

    @Volatile
    private var pendingReload = false

    override fun init() {
        register<TickEvent.Start> {
            if (!pendingReload) return@register
            if (mc.level == null && mc.screen == null) return@register

            pendingReload = false
            reloadMinecraftFonts()
        }
    }

    override fun onEnable() {
        super.onEnable()
        pendingReload = true
    }

    override fun onDisable() {
        super.onDisable()
        closeCustomProvider()
        pendingReload = true
    }

    @JvmStatic
    fun applyToFontSets(fontSets: Map<ResourceLocation, FontSet>) {
        if (!enabled) {
            closeCustomProvider()
            return
        }

        val key = currentFontKey() ?: return
        val provider = getOrLoadProvider(key) ?: return
        val customConditional = GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)

        fontSets.values.forEach { fontSet ->
            val originalProviders = (fontSet as MixinFontSetAccessor)
               .`critsaddons$getAllProviders`()
                .filterNot { it.provider() === provider }

            fontSet.reload(listOf(customConditional) + originalProviders, Collections.emptySet())
        }
    }

    private fun currentFontKey(): FontKey? {
        val file = resolveFontFile(fontFile.value.trim())
        if (file == null) {
            if (fontFile.value.isNotBlank()) ChatUtils.modMessage("&cCustom Font: only .ttf and .otf files are supported.")
            return null
        }

        if (!file.isFile) {
            if (fontFile.value.isNotBlank()) ChatUtils.modMessage("&cCustom Font: file not found: &e${file.path}")
            return null
        }

        return FontKey(file.absoluteFile.normalize(), fontSize.value.toFloat(), oversample.value.toFloat())
    }

    private fun resolveFontFile(raw: String): File? {
        if (raw.isBlank()) return null
        val file = File(raw)
        val resolved = if (file.isAbsolute) file else File(fontsFolder, raw)
        val extension = resolved.extension.lowercase()
        if (extension != "ttf" && extension != "otf") return null
        return resolved
    }

    @Synchronized
    private fun getOrLoadProvider(key: FontKey): TrueTypeGlyphProvider? {
        customProvider?.takeIf { lastLoadedKey == key }?.let { return it }

        closeCustomProvider()
        return runCatching {
            loadTrueTypeProvider(key)
        }.onSuccess {
            customProvider = it
            lastLoadedKey = key
            ChatUtils.modMessage("&aCustom Font loaded: &e${key.file.name}")
        }.onFailure {
            ChatUtils.modMessage("&cCustom Font failed to load: &e${it.message ?: it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun loadTrueTypeProvider(key: FontKey): TrueTypeGlyphProvider {
        val input = FileInputStream(key.file)
        val fontBuffer: ByteBuffer = input.use { TextureUtil.readResource(it) }.also { it.flip() }
        var face: FT_Face? = null

        try {
            synchronized(FreeTypeUtil.LIBRARY_LOCK) {
                MemoryStack.stackPush().use { stack ->
                    val pointer = stack.mallocPointer(1)
                    FreeTypeUtil.assertError(
                        FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), fontBuffer, 0L, pointer),
                        "Initializing custom font face"
                    )
                    face = FT_Face.create(pointer.get())
                }

                val loadedFace = face ?: throw IllegalStateException("Custom font face was not created.")
                val format = FreeType.FT_Get_Font_Format(loadedFace)
                if (format != "TrueType") {
                    throw IllegalArgumentException("Unsupported font format: $format")
                }

                FreeTypeUtil.assertError(
                    FreeType.FT_Select_Charmap(loadedFace, FreeType.FT_ENCODING_UNICODE),
                    "Find unicode charmap"
                )

                return TrueTypeGlyphProvider(
                    fontBuffer,
                    loadedFace,
                    key.size,
                    key.oversample,
                    0.0f,
                    0.0f,
                    ""
                )
            }
        } catch (throwable: Throwable) {
            synchronized(FreeTypeUtil.LIBRARY_LOCK) {
                face?.let { FreeType.FT_Done_Face(it) }
            }
            MemoryUtil.memFree(fontBuffer)
            throw throwable
        }
    }

    @Synchronized
    private fun closeCustomProvider() {
        customProvider?.close()
        customProvider = null
        lastLoadedKey = null
    }

    private fun reloadMinecraftFonts() {
        fontsFolder.mkdirs()
        runCatching {
            mc.reloadResourcePacks()
        }.onFailure {
            pendingReload = true
        }
    }

    private fun openFontsFolder() {
        fontsFolder.mkdirs()
        runCatching {
            Desktop.getDesktop().open(fontsFolder)
        }.onFailure {
            ChatUtils.modMessage("&cCustom Font: could not open folder. Path: &e${fontsFolder.absolutePath}")
        }
    }

    private data class FontKey(
        val file: File,
        val size: Float,
        val oversample: Float
    )
}
