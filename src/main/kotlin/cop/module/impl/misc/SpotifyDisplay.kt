package cop.module.impl.misc

import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.Bounding
import cop.api.abobaui.dsl.at
import cop.api.abobaui.dsl.constrain
import cop.api.abobaui.dsl.minus
import cop.api.abobaui.dsl.plus
import cop.api.abobaui.dsl.px
import cop.api.abobaui.dsl.radius
import cop.api.abobaui.dsl.size
import cop.api.abobaui.elements.Element
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.colour.Colour
import cop.api.colour.colour
import cop.api.colour.withAlpha
import cop.api.spotify.SpotifyClient
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ui.rendering.Image
import cop.utils.ui.rendering.NVGRenderer
import cop.utils.ui.rendering.NVGRenderer.minecraftFont
import cop.utils.ui.watch
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied

/**
 * Spotify Now-Playing HUD.
 *
 * Real album art + real progress + real position/duration, sourced from
 * **Windows SMTC** (`Windows.Media.Control`) via a bundled PowerShell helper
 * — no Spotify account/login needed because SMTC is the same OS API that
 * Windows itself uses for the lock-screen / hardware media-key controls.
 *
 * See [SpotifyClient] for the helper-process plumbing and helper.ps1 for the
 * WinRT side.
 *
 * @author elvin
 */
object SpotifyDisplay : Module(
    "Spotify Display",
    desc = "Shows the song you're currently listening to on Spotify (Windows only)."
) {
    // ---- settings ----
    private val hideWhenClosed by switch(
        "Hide when closed", true,
        desc = "Hides the HUD when Spotify isn't running."
    )

    private val style by selector(
        "Style", Style.Fancy, Style.entries.toList(),
        desc = "Visual style of the display."
    )

    private val showAlbumArt by switch(
        "Show album art", true,
        desc = "Shows the album art (real cover from Spotify when available)."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val showProgressBar by switch(
        "Show progress bar", true,
        desc = "Shows playback progress with elapsed / total time."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val showAlbumName by switch(
        "Show album name", false,
        desc = "Shows the album under the artist name."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val artSize by slider(
        "Art size", 36, 16, 96, 1, unit = "px",
        desc = "Size of the album art square."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val accentColour by colourPicker(
        "Accent colour", Colour.RGB(30, 215, 96),
        desc = "Color used for the progress bar fill and accents."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val bgColour by colourPicker(
        "Background colour", Colour.RGB(18, 18, 18).withAlpha(0.85f), allowAlpha = true,
        desc = "Background panel color."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val outlineColour by colourPicker(
        "Outline colour", Colour.RGB(40, 40, 40).withAlpha(0.9f), allowAlpha = true,
        desc = "Outline color of the panel."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val titleColour by colourPicker(
        "Title colour", Colour.WHITE,
        desc = "Color of the song title text."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val artistColour by colourPicker(
        "Artist colour", Colour.RGB(180, 180, 180),
        desc = "Color of the artist text."
    ).childOf(::style) { it.selected == Style.Fancy }

    private val maxLength by slider(
        "Max characters", 32, 8, 80, 1,
        desc = "Truncate song / artist text past this length."
    )

    private val prefix by textInput(
        "Prefix", "&2Spotify &7> &r", length = 32,
        desc = "Prefix for legacy text style."
    ).childOf(::style) { it.selected == Style.Legacy }

    private val format by textInput(
        "Format", "&a%ARTIST% &7- &b%SONG%", length = 64,
        desc = "Format string for legacy style. Supports %ARTIST%, %SONG%, %ALBUM%, %TIME%."
    ).childOf(::style) { it.selected == Style.Legacy }

    // ---- runtime helpers ----
    private fun trim(s: String, max: Int = maxLength): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun shouldRender(): Boolean {
        if (!enabled) return false
        if (inPreview) return true
        if (!SpotifyClient.isWindows) return false
        if (!hideWhenClosed) return true
        return SpotifyClient.state.open
    }

    private fun titleText(): String {
        if (inPreview) return trim("Never Gonna Give You Up")
        val s = SpotifyClient.state
        return when {
            !SpotifyClient.isWindows -> "Not on Windows"
            !s.open -> "Spotify Not Running"
            s.title.isBlank() -> "—"
            else -> trim(s.title)
        }
    }

    private fun artistText(): String {
        if (inPreview) return trim("Rick Astley")
        val s = SpotifyClient.state
        return when {
            !SpotifyClient.isWindows -> "Spotify integration unavailable"
            !s.open -> "Helper offline"
            s.paused -> "Paused"
            s.artist.isBlank() -> "Unknown artist"
            else -> trim(s.artist)
        }
    }

    private fun albumText(): String {
        if (inPreview) return trim("Whenever You Need Somebody")
        val s = SpotifyClient.state
        return if (s.album.isBlank()) "" else trim(s.album)
    }

    private fun timeText(): String {
        val s = if (inPreview) SpotifyClient.State(true, false, "", "", "", 73_000, 213_000, "", 0)
                else SpotifyClient.state
        return "${fmtTime(s.livePositionMs)} / ${fmtTime(s.durationMs)}"
    }

    private fun progress(): Float = if (inPreview) 0.34f else SpotifyClient.state.progress

    private fun titleColourReactive(): Colour = colour {
        val s = SpotifyClient.state
        when {
            inPreview -> titleColour.rgb
            !SpotifyClient.isWindows || !s.open -> Colour.MINECRAFT_RED.rgb
            s.paused -> Colour.MINECRAFT_GOLD.rgb
            else -> titleColour.rgb
        }
    }

    private fun legacyLine(): String {
        val s = if (inPreview) SpotifyClient.State(true, false, "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 73_000, 213_000, "", 0)
                else SpotifyClient.state
        return prefix + format
            .replace("%ARTIST%", trim(s.artist.ifBlank { "—" }))
            .replace("%SONG%", trim(s.title.ifBlank { "—" }))
            .replace("%ALBUM%", trim(s.album.ifBlank { "—" }))
            .replace("%TIME%", "${fmtTime(s.livePositionMs)}/${fmtTime(s.durationMs)}")
    }

    @Volatile private var inPreview: Boolean = false

    override fun onEnable() {
        super.onEnable()
        SpotifyClient.start()
    }

    override fun onDisable() {
        super.onDisable()
        // Stop the PowerShell helper so a disabled module truly costs nothing —
        // makes the user-facing toggle behave like an actual on/off switch
        // instead of a HUD-visibility flag.
        SpotifyClient.stop()
    }

    // ---- HUD ----
    private val hud by hud("Spotify display", toggleable = false) {
        inPreview = this.preview
        watch({ style.selected }) { rebuildHuds() }
        watch({ showProgressBar }) { rebuildHuds() }
        watch({ showAlbumName }) { rebuildHuds() }
        watch({ showAlbumArt }) { rebuildHuds() }
        watch({ artSize }) { rebuildHuds() }

        if (style.selected == Style.Legacy) buildLegacy() else buildFancy()
    }
        .withSettings(
            ::hideWhenClosed, ::style,
            ::showAlbumArt, ::showProgressBar, ::showAlbumName, ::artSize,
            ::accentColour, ::bgColour, ::outlineColour, ::titleColour, ::artistColour,
            ::maxLength, ::prefix, ::format
        )
        .setting()

    private fun cop.utils.ui.hud.Hud.Scope.buildLegacy() {
        textSupplied(
            supplier = { legacyLine() },
            colour = titleColourReactive(),
            font = minecraftFont,
            size = 18.px
        ).element.also { /* shadow not exposed via supplier; default false */ }

        operation {
            element.enabled = shouldRender()
            false
        }
    }

    private fun cop.utils.ui.hud.Hud.Scope.buildFancy() {
        val art = artSize.toFloat()
        val textColumnWidth = 180f

        block(
            size(Bounding + 18.px, Bounding + 14.px),
            colour = colour { bgColour.rgb },
            radius = 8.radius()
        ) {
            outline(
                colour = colour { outlineColour.rgb },
                thickness = 1.px
            )

            row(
                constraints = constrain(x = 9.px, y = 7.px),
                gap = 9.px
            ) {
                if (showAlbumArt) {
                    // Custom element: draws live album art if available, otherwise an
                    // accent-colored placeholder with a music-note glyph.
                    SpotifyArtElement(
                        constrain(y = Centre, w = art.px, h = art.px),
                        accent = { accentColour },
                        radius = 4f
                    ).also { it.add() }
                }

                column(gap = 3.px) {
                    // Title
                    textSupplied(
                        supplier = { titleText() },
                        font = minecraftFont,
                        colour = titleColourReactive(),
                        size = 16.px
                    )

                    // Artist
                    textSupplied(
                        supplier = { artistText() },
                        font = minecraftFont,
                        colour = colour { artistColour.rgb },
                        size = 12.px
                    )

                    // Album (optional)
                    if (showAlbumName) {
                        textSupplied(
                            supplier = { albumText() },
                            font = minecraftFont,
                            colour = colour { artistColour.rgb.darken() },
                            size = 11.px
                        )
                    }

                    if (showProgressBar) {
                        // Progress bar track + fill + time text
                        val trackWidth = textColumnWidth
                        val trackHeight = 3f

                        block(
                            constrain(w = trackWidth.px, h = trackHeight.px),
                            colour = colour { Colour.RGB(60, 60, 60).withAlpha(0.85f).rgb },
                            radius = (trackHeight / 2f).radius()
                        ) {
                            // fill
                            object : Element(
                                constrain(w = (trackWidth).px, h = trackHeight.px),
                                colour = null
                            ) {
                                init { usingCtx = false }
                                override fun drawNvg() {
                                    val w = trackWidth * progress()
                                    if (w <= 0f) return
                                    val r = trackHeight / 2f
                                    val rgb = accentColour.rgb
                                    NVGRenderer.rect(x, y, w, height, rgb, cop.utils.ui.data.Radii(r, r, r, r))
                                }
                            }.add()
                        }

                        textSupplied(
                            supplier = { timeText() },
                            font = minecraftFont,
                            colour = colour { artistColour.rgb },
                            size = 10.px
                        )
                    }
                }
            }
        }

        operation {
            element.enabled = shouldRender()
            false
        }
    }

    private fun Int.darken(factor: Float = 0.7f): Int {
        val a = (this ushr 24) and 0xFF
        val r = (((this ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((this ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((this and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    enum class Style(val displayName: String) {
        Fancy("Fancy"),
        Legacy("Legacy text");
        override fun toString(): String = displayName
    }

    /**
     * Custom element that lazy-loads and draws the current Spotify album art.
     *
     * Image creation is deferred to the render thread (drawNvg runs there) so we
     * don't try to call into NVG / OpenGL from the helper-stdout reader thread.
     * When SpotifyClient.artVersion advances, we drop the previous NVG image and
     * pick up the new file.
     */
    private class SpotifyArtElement(
        constraints: Constraints,
        val accent: () -> Colour,
        val radius: Float,
    ) : Element(constraints, null) {

        init { usingCtx = false }

        private var loadedVersion: Int = -1
        private var loadedImage: Image? = null

        override fun drawNvg() {
            val targetVersion = SpotifyClient.artVersion
            val targetFile = SpotifyClient.artFile
            if (targetVersion != loadedVersion) {
                // Drop old image (if any), load new one.
                loadedImage?.let { runCatching { NVGRenderer.deleteImage(it) } }
                loadedImage = if (targetFile != null && targetFile.exists()) {
                    runCatching { NVGRenderer.createImage(targetFile.absolutePath) }
                        .onFailure { /* keep null on failure, fall back to placeholder */ }
                        .getOrNull()
                } else null
                loadedVersion = targetVersion
            }

            val img = loadedImage
            if (img != null) {
                NVGRenderer.image(img, x, y, width, height,
                    cop.utils.ui.data.Radii(radius, radius, radius, radius), null)
            } else {
                // Placeholder: accent-coloured rounded square with a small note glyph.
                val r = radius
                NVGRenderer.rect(x, y, width, height, accent().rgb,
                    cop.utils.ui.data.Radii(r, r, r, r))
            }
        }
    }
}
