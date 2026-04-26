package cop.api.spotify

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cop.CopMod
import cop.CopMod.mc
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

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
 */
object SpotifyClient {

    private const val SCRIPT_RESOURCE = "/assets/cop/spotify/helper.ps1"

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

    private var process: Process? = null
    @Volatile private var started = false
    @Volatile private var shouldRun = false

    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    /** Idempotent — first caller wins, later calls are no-ops while the helper is alive. */
    @Synchronized
    fun start() {
        if (!isWindows) return
        if (started) return
        started = true
        shouldRun = true

        try {
            extractScript()
        } catch (e: Exception) {
            CopMod.logger.error("[Spotify] failed to extract helper script", e)
            started = false
            return
        }

        spawn()
    }

    @Synchronized
    fun stop() {
        shouldRun = false
        process?.destroy()
        process = null
        started = false
    }

    private fun extractScript() {
        val res = SpotifyClient::class.java.getResourceAsStream(SCRIPT_RESOURCE)
            ?: throw IOException("helper script $SCRIPT_RESOURCE not found in jar")
        res.use { input ->
            Files.copy(input, scriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun spawn() {
        val pb = ProcessBuilder(
            "powershell.exe",
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

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            CopMod.logger.error("[Spotify] failed to spawn helper", e)
            return
        }

        process = proc

        stdoutThread = Thread({ readStdout(proc) }, "cop-spotify-stdout").also {
            it.isDaemon = true
            it.start()
        }
        stderrThread = Thread({ readStderr(proc) }, "cop-spotify-stderr").also {
            it.isDaemon = true
            it.start()
        }

        // watcher: restart if the helper dies while we still want it running
        Thread({
            try { proc.waitFor() } catch (_: InterruptedException) {}
            if (shouldRun) {
                CopMod.logger.warn("[Spotify] helper exited (code=${proc.exitValue()}); restarting in 5s")
                stateRef.set(State.empty())
                Thread.sleep(5_000)
                if (shouldRun) spawn()
            }
        }, "cop-spotify-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun readStdout(proc: Process) {
        BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                if (line.isBlank()) continue
                handleLine(line)
            }
        }
    }

    private fun readStderr(proc: Process) {
        BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                if (line.isBlank()) continue
                CopMod.logger.warn("[Spotify helper] $line")
            }
        }
    }

    private fun handleLine(line: String) {
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            CopMod.logger.warn("[Spotify] unparseable helper output: ${line.take(120)}")
            return
        }

        when (obj.get("type")?.asString) {
            "hello" -> CopMod.logger.info("[Spotify] helper ready (pid=${obj.get("pid")?.asInt})")
            "fatal" -> CopMod.logger.error("[Spotify] helper fatal: ${obj.get("reason")?.asString}")
            "art" -> handleArt(obj)
            "state" -> handleState(obj)
        }
    }

    private fun handleArt(obj: JsonObject) {
        val version = obj.get("version")?.asInt ?: return
        val b64 = obj.get("b64")?.asString ?: return
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (e: Exception) {
            CopMod.logger.warn("[Spotify] bad base64 for art v$version", e)
            return
        }

        val out = File(cacheDir, "art_v$version.png")
        try {
            out.writeBytes(bytes)
        } catch (e: Exception) {
            CopMod.logger.warn("[Spotify] failed to write art file", e)
            return
        }

        // Best-effort cleanup of older art files.
        try {
            cacheDir.listFiles { f -> f.name.startsWith("art_v") && f.name.endsWith(".png") && f != out }
                ?.forEach { it.delete() }
        } catch (_: Exception) {}

        artFile = out
        artVersion = version
    }

    private fun handleState(obj: JsonObject) {
        fun str(name: String) = obj.get(name)?.asString.orEmpty()
        fun bool(name: String) = obj.get(name)?.asBoolean ?: false
        fun longv(name: String) = obj.get(name)?.asLong ?: 0L
        fun intv(name: String) = obj.get(name)?.asInt ?: 0

        stateRef.set(
            State(
                open = bool("open"),
                paused = bool("paused"),
                title = str("title"),
                artist = str("artist"),
                album = str("album"),
                positionMs = longv("posMs"),
                durationMs = longv("durMs"),
                source = str("source"),
                artVersion = intv("artVersion"),
                receivedAtMs = System.currentTimeMillis(),
            )
        )
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
