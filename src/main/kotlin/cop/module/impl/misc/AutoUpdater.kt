package cop.module.impl.misc

import com.google.gson.JsonPrimitive
import cop.CopMod
import cop.api.events.ServerEvent
import cop.api.input.CatKeys
import cop.module.Module
import cop.utils.ChatUtils
import cop.utils.ChatUtils.modMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.nea.libautoupdate.CurrentVersion
import moe.nea.libautoupdate.GithubReleaseUpdateData
import moe.nea.libautoupdate.GithubReleaseUpdateSource
import moe.nea.libautoupdate.PotentialUpdate
import moe.nea.libautoupdate.UpdateContext
import moe.nea.libautoupdate.UpdateData
import moe.nea.libautoupdate.UpdateTarget
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Style
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub-release-based self-updater built on `moe.nea:libautoupdate`.
 *
 * The library handles the awkward bits we don't want to write ourselves:
 *   - Querying the GitHub releases API and picking the newest tag for the
 *     selected stream (`full` / `pre`).
 *   - Downloading the chosen jar to `.autoupdates/cop/<uuid>/next.jar` and
 *     verifying it.
 *   - Registering an exit hook that runs a small launcher jar after the JVM
 *     shuts down, swapping the current jar for the downloaded one. This is
 *     how it dodges the "can't delete a loaded jar on Windows" problem.
 *
 * We override [findAsset] so a release containing both `cop-…+mc1.21.10.jar`
 * and `cop-…+mc1.21.11.jar` picks the one matching the player's actual MC
 * version — the default `findAsset` just grabs the first jar and would happily
 * download a wrong-version build.
 *
 * @author elvin
 */
object AutoUpdater : Module(
    "Auto Updater",
    desc = "Checks GitHub for new COP releases and (optionally) installs them on the next restart.",
) {
    // ---------------------------------------------------------------- settings
    private val checkOnLaunch by switch(
        "Check on launch", true,
        desc = "Hits the GitHub API once per Minecraft session, the first time you join a " +
            "Hypixel server. Other servers (singleplayer, lobby tests, etc.) never trigger " +
            "a check — the mod is Hypixel-only so there's no point updating elsewhere.",
    )
    private val showPopup by switch(
        "Show popup screen", true,
        desc = "Open a modal screen with Update / Remind / Skip buttons when an update is detected. " +
                "When off, the module only writes a chat notification with the release link.",
    )
    private val autoDownload by switch(
        "Auto download", false,
        desc = "Skip the popup and download new versions immediately, installing them on the next " +
                "Minecraft restart.",
    )
    private val includePrereleases by switch(
        "Include pre-releases", false,
        desc = "Use the `pre` stream — pulls in beta tags as well as stable releases.",
    )
    private val owner by textInput(
        "GitHub owner", "elv1n200",
        desc = "First half of `<owner>/<repo>` on github.com.", length = 39,
    )
    private val repo by textInput(
        "GitHub repository", "COP",
        desc = "Second half of `<owner>/<repo>` on github.com.", length = 100,
    )
    private val checkKey = keybind(
        "Check now", CatKeys.KEY_NONE,
        desc = "Trigger an update check manually.",
    ).onPress { runCheck(reason = "manual") }.also { register(it) }

    /**
     * Persisted in `cop-config.json` as part of the module's settings, hidden
     * from the ClickGui — stores the version tag the user chose to permanently
     * skip via the popup screen. The popup re-appears once the latest tag on
     * GitHub is *different* from this value (i.e. another release came out).
     */
    private var skippedVersion by textInput(
        "(internal) skipped version", "",
        desc = "Set automatically by the update popup's Skip button.",
    ).hide()

    // ---------------------------------------------------------------- runtime
    private val checkInFlight = AtomicBoolean(false)
    /** "Remind me later" — transient suppression for the rest of this session. */
    @Volatile private var remindLaterFor: String? = null
    /** True once we've kicked off (or queued) an automatic check in this MC
     *  session — prevents the "(x4)" spam from re-checking on every lobby hop
     *  or dungeon entry. Reset only by restarting the game. Manual checks via
     *  the keybind are unaffected. */
    @Volatile private var autoCheckFiredThisSession = false

    /** Bare mod version reported in `fabric.mod.json` — e.g. `1.0.0`. */
    private val currentModVersion: String by lazy {
        FabricLoader.getInstance().getModContainer(CopMod.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("0.0.0")
    }

    /** Active Minecraft version — used to pick the matching jar from a release. */
    private val currentMcVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("minecraft")
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    }

    /**
     * Lazily built so it isn't constructed if the user never enables the
     * module. `UpdateTarget.deleteAndSaveInTheSameFolder` resolves the jar
     * containing CopMod at build time — in dev (`./gradlew runClient`) the
     * "jar" is a `build/classes/.../cop` directory and the swap is a no-op,
     * which is the right behaviour for development.
     */
    private val context: UpdateContext by lazy {
        UpdateContext(
            McAwareGithubReleaseSource(owner, repo, currentMcVersion),
            UpdateTarget.deleteAndSaveInTheSameFolder(CopMod::class.java),
            // Strip leading 'v' so the local tag compares apples-to-apples
            // with GitHub's tag-name (some releases are tagged '1.2.0',
            // some 'v1.3.0' — the comparison is otherwise a plain string
            // equality and would always think 'update available' on the
            // versions tagged with a prefix). The matching strip on the
            // remote side lives in McAwareGithubReleaseSource.findAsset.
            CurrentVersion.ofTag(currentModVersion.removePrefix("v")),
            "cop",
        )
    }

    override fun onEnable() {
        super.onEnable()
        // Sweep stale `.autoupdates/cop/*` directories from any previous,
        // never-applied update so the disk doesn't slowly fill up.
        runCatching { context.cleanup() }
        // No check here — we wait for a Hypixel server-connect (or, if the
        // user enabled mid-session while already on Hypixel, the next reload).
    }

    init {
        // Fire the launch check exactly once per Minecraft session, the first
        // time we connect to a Hypixel server. Previous design used
        // GameEvent.Load which fires on every world swap (lobby hop, dungeon
        // entry, garden tp, …) producing four-plus "already on the latest
        // version" messages per play session. ServerEvent.Connect fires only
        // on actual server connections; the IP check skips singleplayer +
        // non-Hypixel test servers where an update would be pointless.
        on<ServerEvent.Connect> {
            if (autoCheckFiredThisSession) return@on
            if (!checkOnLaunch) return@on
            if (!ip.contains("hypixel", ignoreCase = true)) return@on
            autoCheckFiredThisSession = true
            runCheck(reason = "hypixel-join")
        }
    }

    private fun runCheck(reason: String) {
        if (!enabled) return
        if (!checkInFlight.compareAndSet(false, true)) return  // dedupe in-flight checks

        val stream = if (includePrereleases) "pre" else "full"
        CopMod.scope.launch(Dispatchers.IO) {
            try {
                val update = context.checkUpdate(stream).get()
                val remoteVersion = update.update.versionName.orEmpty()
                // libautoupdate's isUpdateAvailable is a plain string inequality
                // check — fires "update available" any time the remote tag differs,
                // including when the local build is *ahead* of the latest published
                // release (which happens any time we're testing a local build before
                // tagging it). Re-gate on our own component-wise comparison so the
                // popup only appears for an actually-newer remote.
                val reallyNewer = update.isUpdateAvailable && isStrictlyNewer(remoteVersion, currentModVersion)
                if (reallyNewer) {
                    handleUpdateAvailable(update)
                } else if (reason == "manual") {
                    val cmp = compareSemver(remoteVersion, currentModVersion)
                    val msg = when {
                        cmp == 0 -> "&aAuto Updater: already on the latest version (&f${currentModVersion}&a)."
                        cmp < 0  -> "&aAuto Updater: local build (&f${currentModVersion}&a) is ahead of the latest release (&f${remoteVersion}&a)."
                        else     -> "&aAuto Updater: already on the latest version (&f${currentModVersion}&a)."
                    }
                    modMessage(msg)
                }
            } catch (e: Exception) {
                CopMod.logger.warn("[AutoUpdater] check failed", e)
                if (reason == "manual") modMessage("&cAuto Updater: check failed — ${e.message ?: "unknown error"}")
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    /** Strict component-wise version comparison after stripping a leading 'v'.
     *  Returns true only if [remote] is unambiguously newer than [local]. If
     *  either side can't be parsed as a dotted-int version, defaults to false
     *  (no popup) since a downgrade prompt is worse than a missed update. */
    private fun isStrictlyNewer(remote: String, local: String): Boolean {
        val r = parseSemver(remote) ?: return false
        val l = parseSemver(local) ?: return false
        return compareLists(r, l) > 0
    }

    /** Same parse + compare, returning -1/0/1; or 0 if either side won't parse. */
    private fun compareSemver(a: String, b: String): Int {
        val pa = parseSemver(a) ?: return 0
        val pb = parseSemver(b) ?: return 0
        return compareLists(pa, pb)
    }

    private fun parseSemver(s: String): List<Int>? {
        val trimmed = s.trim().removePrefix("v")
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('.').map { chunk ->
            // Tolerate suffixes like "1.3.2-pre1" → take leading digits only.
            chunk.takeWhile { it.isDigit() }.toIntOrNull()
        }
        return if (parts.any { it == null }) null else parts.filterNotNull()
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun handleUpdateAvailable(update: PotentialUpdate) {
        val data = update.update
        val versionLabel = data.versionName ?: "?"

        // Suppression checks: "skip" persists across sessions, "remind later"
        // only for this session.
        if (versionLabel == skippedVersion) {
            CopMod.logger.info("[AutoUpdater] update $versionLabel skipped via persistent setting")
            return
        }
        if (versionLabel == remindLaterFor) return

        if (autoDownload) {
            beginDownload(update, versionLabel)
            return
        }

        if (showPopup) {
            // Switch to the render thread before opening a Screen — Mojang's
            // setScreen isn't safe off-thread.
            Minecraft.getInstance().execute {
                val mc = Minecraft.getInstance()
                val parent = mc.screen
                mc.setScreen(
                    UpdateScreen(
                        parent = parent,
                        currentVersion = currentModVersion,
                        newVersion = versionLabel,
                        onUpdate = { beginDownload(update, versionLabel) },
                        onRemind = {
                            remindLaterFor = versionLabel
                            modMessage("&eAuto Updater: will remind on next launch.")
                        },
                        onSkip = {
                            skippedVersion = versionLabel
                            modMessage("&eAuto Updater: skipped &f$versionLabel&e (won't ask again until a newer release).")
                        },
                    )
                )
            }
        } else {
            chatNotify(update, versionLabel)
        }
    }

    private fun chatNotify(update: PotentialUpdate, versionLabel: String) {
        val releaseUrl = (update.update as? GithubReleaseUpdateData)?.htmlUrl
        val link = ChatUtils.literal("&b&n$versionLabel&r&a")
            .also { c ->
                if (releaseUrl != null) {
                    c.style = Style.EMPTY.withClickEvent(ClickEvent.OpenUrl(URI.create(releaseUrl)))
                }
            }
        val msg = ChatUtils.literal("&aAuto Updater: ").append(link).append(ChatUtils.literal("&a available"))
        ChatUtils.modMessage(msg)
    }

    private fun beginDownload(update: PotentialUpdate, versionLabel: String) {
        CopMod.logger.info("[AutoUpdater] downloading update $versionLabel")
        modMessage("&eAuto Updater: downloading &f$versionLabel&e…")
        update.launchUpdate().whenComplete { _, err ->
            if (err == null) {
                modMessage("&aAuto Updater: downloaded &f$versionLabel&a — installs on next Minecraft restart.")
            } else {
                CopMod.logger.warn("[AutoUpdater] download failed", err)
                modMessage("&cAuto Updater: download failed — ${err.message ?: "unknown error"}")
            }
        }
    }

    /**
     * GitHub source that picks the asset whose filename contains `mc<MC_VERSION>`,
     * so a multi-platform release (one jar per supported MC version) downloads
     * the right one for whichever client is running.
     */
    private class McAwareGithubReleaseSource(
        owner: String,
        repository: String,
        private val mcVersion: String,
    ) : GithubReleaseUpdateSource(owner, repository) {
        private val mcTag = "mc$mcVersion"

        override fun findAsset(release: GithubRelease): UpdateData? {
            val assets = release.assets ?: return null
            val match = assets.firstOrNull { a ->
                a.browserDownloadUrl != null
                    && a.name?.endsWith(".jar") == true
                    && a.name.contains(mcTag, ignoreCase = true)
            } ?: return super.findAsset(release)  // fall back to "first jar" if no MC-specific build

            return GithubReleaseUpdateData(
                release.name ?: release.tagName,
                // Strip the optional leading 'v' so e.g. tag 'v1.3.0'
                // compares equal to the local mod_version '1.3.0'. Mirror
                // of the strip in AutoUpdater.context.
                JsonPrimitive(release.tagName.removePrefix("v")),
                null,
                match.browserDownloadUrl,
                release.body,
                release.targetCommitish,
                release.created_at,
                release.publishedAt,
                release.htmlUrl,
            )
        }
    }
}
