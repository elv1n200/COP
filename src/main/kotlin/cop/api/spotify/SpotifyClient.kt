package cop.api.spotify

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cop.CopMod
import cop.CopMod.mc
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * Background process that talks to Windows SMTC (System Media Transport Controls)
 * via a bundled PowerShell helper, and exposes the current Spotify session as a
 * volatile snapshot.
 *
 * Why a helper script:
 *   - SMTC lives in `Windows.Media.Control`, a WinRT namespace. The JVM has no
 *     native bindings, so we'd otherwise need JNA / a native binary. PowerShell
 *     (preinstalled on every Win10+ box) speaks WinRT directly, so a tiny .ps1
 *     gives us album art + position + duration + paused-state with zero compile
 *     step and zero ship-a-binary headache.
 *   - The script outputs newline-delimited JSON on stdout. We parse on a worker
 *     thread and write album art bytes to disk so the render thread can load the
 *     PNG via the existing NVG image pipeline.
 *
 * macOS / Linux equivalents (MPRIS / AppleScript) are not implemented — Spotify
 * desktop only really exists on Windows for our user base.
 *
 * @author elvin
 */
object SpotifyClient {

    private const val SCRIPT_RESOURCE = "/assets/cop/spotify/helper.ps1"
    private const val MAX_ART_BYTES = 5 * 1024 * 1024
    private const val MAX_ART_BASE64_CHARS = 4 * ((MAX_ART_BYTES + 2) / 3)
    private const val MAX_STDOUT_LINE_CHARS = MAX_ART_BASE64_CHARS + 4 * 1024
    private const val MAX_STDERR_LINE_CHARS = 16 * 1024
    private const val MAX_METADATA_CHARS = 512
    private const val MAX_SOURCE_CHARS = 512
    private const val MAX_ART_DIMENSION = 4096
    private const val MAX_ART_PIXELS = 16L * 1024 * 1024
    private const val MAX_TIMELINE_MS = 30L * 24 * 60 * 60 * 1000

    private val cacheDir: File = File(mc.gameDirectory, "config/cop/cache/spotify").apply {
        try { mkdirs() } catch (_: Exception) {}
    }

    private val scriptFile: File = File(cacheDir, "helper.ps1")

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Latest snapshot, replaced atomically when a state line arrives. */
    private val stateRef = AtomicReference(State.empty())
    val state: State get() = stateRef.get()

    /** Latest album-art file on disk, or `null` if no art is available. */
    @Volatile var artFile: File? = null
        private set
    @Volatile var artVersion: Int = 0
        private set

    @Volatile private var process: Process? = null
    @Volatile private var started = false
    @Volatile private var shouldRun = false
    @Volatile private var lifecycleGeneration = 0L

    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    /** Idempotent — first caller wins, later calls are no-ops while the helper is alive. */
    @Synchronized
    fun start() {
        if (!isWindows) return
        if (started) return
        lifecycleGeneration++
        val generation = lifecycleGeneration
        started = true
        shouldRun = true

        try {
            extractScript()
        } catch (e: Exception) {
            CopMod.logger.error("[Spotify] failed to extract helper script", e)
            started = false
            shouldRun = false
            return
        }

        spawn(generation)
    }

    @Synchronized
    fun stop() {
        lifecycleGeneration++
        shouldRun = false
        val previousProcess = process
        process = null
        previousProcess?.destroy()
        started = false
        stateRef.set(State.empty())
        clearAlbumArt()
    }

    private fun extractScript() {
        val res = SpotifyClient::class.java.getResourceAsStream(SCRIPT_RESOURCE)
            ?: throw IOException("helper script $SCRIPT_RESOURCE not found in jar")
        res.use { input ->
            Files.copy(input, scriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun spawn(generation: Long) {
        val powershell = try {
            resolvePowerShellExecutable()
        } catch (e: Exception) {
            CopMod.logger.error("[Spotify] trusted Windows PowerShell executable unavailable", e)
            failGeneration(generation)
            return
        }
        val pb = ProcessBuilder(
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", scriptFile.absolutePath
        )
        pb.redirectErrorStream(false)
        // Hide the console window even when run from a console-attached JVM.
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.redirectError(ProcessBuilder.Redirect.PIPE)

        val proc = synchronized(this) {
            if (!shouldRun || lifecycleGeneration != generation || process != null) return
            try {
                pb.start().also { process = it }
            } catch (e: Exception) {
                CopMod.logger.error("[Spotify] failed to spawn helper", e)
                started = false
                shouldRun = false
                return
            }
        }

        stdoutThread = Thread({ readStdout(proc, generation) }, "cop-spotify-stdout").also {
            it.isDaemon = true
            it.start()
        }
        stderrThread = Thread({ readStderr(proc, generation) }, "cop-spotify-stderr").also {
            it.isDaemon = true
            it.start()
        }

        // watcher: restart if the helper dies while we still want it running
        Thread(watcher@{
            val exitCode = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                return@watcher
            }
            val restart = synchronized(this) {
                if (!shouldRun || lifecycleGeneration != generation || process !== proc) {
                    false
                } else {
                    process = null
                    stateRef.set(State.empty())
                    clearAlbumArt()
                    true
                }
            }
            if (!restart) return@watcher

            CopMod.logger.warn("[Spotify] helper exited (code=$exitCode); restarting in 5s")
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@watcher
            }
            if (isGenerationAwaitingProcess(generation)) spawn(generation)
        }, "cop-spotify-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun readStdout(proc: Process, generation: Long) {
        BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
            var warnedOversizedLine = false
            while (true) {
                val result = try {
                    readLimitedLine(reader, MAX_STDOUT_LINE_CHARS)
                } catch (_: IOException) {
                    null
                } ?: break
                if (result.oversized) {
                    if (!warnedOversizedLine) {
                        warnedOversizedLine = true
                        CopMod.logger.warn("[Spotify] helper stdout line exceeded the safety limit; dropping it")
                    }
                    continue
                }
                val line = result.value
                if (line.isBlank()) continue
                if (isCurrentProcess(proc, generation)) handleLine(line, proc, generation)
            }
        }
    }

    private fun readStderr(proc: Process, generation: Long) {
        BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
            var warnedOversizedLine = false
            while (true) {
                val result = try {
                    readLimitedLine(reader, MAX_STDERR_LINE_CHARS)
                } catch (_: IOException) {
                    null
                } ?: break
                if (result.oversized) {
                    if (!warnedOversizedLine) {
                        warnedOversizedLine = true
                        CopMod.logger.warn("[Spotify] helper stderr line exceeded the safety limit; dropping it")
                    }
                    continue
                }
                val line = result.value
                if (line.isBlank()) continue
                if (isCurrentProcess(proc, generation)) CopMod.logger.warn("[Spotify helper] $line")
            }
        }
    }

    private data class LimitedLine(val value: String, val oversized: Boolean)

    /** Read and drain one line without ever allocating beyond [maxChars]. */
    private fun readLimitedLine(reader: BufferedReader, maxChars: Int): LimitedLine? {
        val out = StringBuilder(minOf(maxChars, 1024))
        var oversized = false
        var sawInput = false
        while (true) {
            val next = reader.read()
            if (next == -1) {
                if (!sawInput) return null
                return LimitedLine(out.toString(), oversized)
            }
            sawInput = true
            if (next == '\n'.code) return LimitedLine(out.toString(), oversized)
            if (next == '\r'.code) continue
            if (!oversized) {
                if (out.length >= maxChars) oversized = true else out.append(next.toChar())
            }
        }
    }

    private fun resolvePowerShellExecutable(): String {
        val systemRootValue = System.getenv("SystemRoot")?.trim().orEmpty()
        if (systemRootValue.isEmpty()) throw IOException("SystemRoot is not set")

        val systemRoot = Path.of(systemRootValue).toAbsolutePath().normalize()
        val executable = systemRoot
            .resolve("System32")
            .resolve("WindowsPowerShell")
            .resolve("v1.0")
            .resolve("powershell.exe")
            .normalize()
        if (!executable.startsWith(systemRoot) ||
            !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("expected PowerShell executable not found under SystemRoot")
        }
        return executable.toString()
    }

    private fun failGeneration(generation: Long) {
        synchronized(this) {
            if (lifecycleGeneration == generation && process == null) {
                started = false
                shouldRun = false
            }
        }
    }

    private fun isGenerationAwaitingProcess(generation: Long): Boolean = synchronized(this) {
        shouldRun && lifecycleGeneration == generation && process == null
    }

    private fun isCurrentProcess(proc: Process, generation: Long): Boolean = synchronized(this) {
        shouldRun && lifecycleGeneration == generation && process === proc
    }

    private inline fun withCurrentProcess(proc: Process, generation: Long, action: () -> Unit): Boolean =
        synchronized(this) {
            if (!shouldRun || lifecycleGeneration != generation || process !== proc) return@synchronized false
            action()
            true
        }

    private fun handleLine(line: String, proc: Process, generation: Long) {
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            CopMod.logger.warn("[Spotify] unparseable helper output: ${line.take(120)}")
            return
        }

        try {
            when (obj.get("type")?.asString) {
                "hello" -> CopMod.logger.info("[Spotify] helper ready (pid=${obj.get("pid")?.asInt})")
                "fatal" -> CopMod.logger.error("[Spotify] helper fatal: ${obj.get("reason")?.asString}")
                "art" -> handleArt(obj, proc, generation)
                "art-clear" -> handleArtClear(obj, proc, generation)
                "state" -> handleState(obj, proc, generation)
            }
        } catch (e: Exception) {
            CopMod.logger.warn("[Spotify] invalid helper message", e)
        }
    }

    private fun handleArt(obj: JsonObject, proc: Process, generation: Long) {
        if (!isCurrentProcess(proc, generation)) return
        val source = obj.get("source")?.asString?.take(MAX_SOURCE_CHARS) ?: return
        if (!isSpotifySource(source)) return
        val version = obj.get("version")?.asInt?.takeIf { it > 0 } ?: return
        val b64 = obj.get("b64")?.asString ?: run {
            clearAlbumArtIfCurrent(proc, generation)
            return
        }
        if (b64.length > MAX_ART_BASE64_CHARS) {
            CopMod.logger.warn("[Spotify] album art v$version exceeded the encoded size limit")
            clearAlbumArtIfCurrent(proc, generation)
            return
        }
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (e: Exception) {
            CopMod.logger.warn("[Spotify] bad base64 for art v$version", e)
            clearAlbumArtIfCurrent(proc, generation)
            return
        }
        if (bytes.isEmpty() || bytes.size > MAX_ART_BYTES || !hasSafeImageDimensions(bytes)) {
            CopMod.logger.warn("[Spotify] album art v$version failed size/dimension validation")
            clearAlbumArtIfCurrent(proc, generation)
            return
        }

        val out = cacheDir.toPath().resolve("art_${UUID.randomUUID()}.png")
        try {
            Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(bytes)
            }
        } catch (e: Exception) {
            CopMod.logger.warn("[Spotify] failed to write art file", e)
            clearAlbumArtIfCurrent(proc, generation)
            return
        }

        val oldArtFiles = try {
            cacheDir.listFiles { file ->
                file.name.startsWith("art_") && file.name.endsWith(".png") && file.toPath() != out
            }.orEmpty()
        } catch (_: Exception) {
            emptyArray()
        }
        if (!withCurrentProcess(proc, generation) {
                artFile = out.toFile()
                advanceArtVersion()
            }) {
            runCatching { Files.deleteIfExists(out) }
            return
        }

        // Best-effort cleanup of older art files.
        oldArtFiles.forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
    }

    private fun handleArtClear(obj: JsonObject, proc: Process, generation: Long) {
        val source = obj.get("source")?.asString?.take(MAX_SOURCE_CHARS) ?: return
        if (!isSpotifySource(source)) return
        val version = obj.get("version")?.asInt?.takeIf { it > 0 } ?: return
        clearAlbumArtIfCurrent(proc, generation)
    }

    private fun handleState(obj: JsonObject, proc: Process, generation: Long) {
        fun str(name: String, maxChars: Int = MAX_METADATA_CHARS) =
            obj.get(name)?.asString.orEmpty().take(maxChars)
        fun bool(name: String) = obj.get(name)?.asBoolean ?: false
        fun longv(name: String) = obj.get(name)?.asLong ?: 0L
        fun intv(name: String) = obj.get(name)?.asInt ?: 0

        val source = str("source", MAX_SOURCE_CHARS)
        val open = bool("open") && isSpotifySource(source)
        if (!open) {
            withCurrentProcess(proc, generation) {
                stateRef.set(State.empty())
                clearAlbumArt()
            }
            return
        }

        val duration = longv("durMs").coerceIn(0L, MAX_TIMELINE_MS)
        val position = longv("posMs").coerceIn(0L, if (duration > 0L) duration else MAX_TIMELINE_MS)

        val nextState = State(
                open = true,
                paused = bool("paused"),
                title = str("title"),
                artist = str("artist"),
                album = str("album"),
                positionMs = position,
                durationMs = duration,
                source = source,
                artVersion = intv("artVersion"),
                receivedAtMs = System.currentTimeMillis(),
            )
        withCurrentProcess(proc, generation) { stateRef.set(nextState) }
    }

    private fun isSpotifySource(source: String): Boolean {
        val normalized = source.trim().lowercase(Locale.ROOT)
        return normalized == "spotify" ||
            normalized == "spotify.exe" ||
            (normalized.startsWith("spotifyab.spotifymusic_") && normalized.endsWith("!spotify"))
    }

    private fun hasSafeImageDimensions(bytes: ByteArray): Boolean = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use false
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                width in 1..MAX_ART_DIMENSION &&
                    height in 1..MAX_ART_DIMENSION &&
                    width.toLong() * height.toLong() <= MAX_ART_PIXELS
            } finally {
                reader.dispose()
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun clearAlbumArtIfCurrent(proc: Process, generation: Long) {
        withCurrentProcess(proc, generation) { clearAlbumArt(forceRevision = true) }
    }

    @Synchronized
    private fun clearAlbumArt(forceRevision: Boolean = false) {
        val previous = artFile
        if (previous == null && !forceRevision) return
        artFile = null
        advanceArtVersion()
        if (previous != null) runCatching { Files.deleteIfExists(previous.toPath()) }
    }

    private fun advanceArtVersion() {
        artVersion = if (artVersion == Int.MAX_VALUE) 0 else artVersion + 1
    }

    /**
     * Snapshot of the Spotify session.
     *
     * Position values are extrapolated client-side: the helper polls SMTC at
     * ~2 Hz, so the raw `positionMs` is up to ~500 ms stale. We stamp each
     * snapshot with `receivedAtMs` and use [livePositionMs] / [progress] for
     * display so the progress bar advances at wall-clock speed instead of
     * jumping in 500 ms ticks.
     */
    data class State(
        val open: Boolean,
        val paused: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        val positionMs: Long,
        val durationMs: Long,
        val source: String,
        val artVersion: Int,
        val receivedAtMs: Long = 0L,
    ) {
        val playing: Boolean get() = open && !paused

        /** Position with elapsed-since-snapshot added when playing, clamped to duration. */
        val livePositionMs: Long get() {
            if (!playing || receivedAtMs <= 0L) return positionMs
            val elapsed = System.currentTimeMillis() - receivedAtMs
            val extrapolated = positionMs + elapsed.coerceAtLeast(0L)
            return if (durationMs > 0) extrapolated.coerceAtMost(durationMs) else extrapolated
        }

        val progress: Float get() =
            if (durationMs <= 0) 0f else (livePositionMs.toFloat() / durationMs).coerceIn(0f, 1f)

        companion object {
            fun empty() = State(false, false, "", "", "", 0L, 0L, "", 0, 0L)
        }
    }
}
