package cop.module.impl.misc

import com.google.gson.JsonPrimitive
import cop.CopMod
import cop.api.events.GameEvent
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
        desc = "Hits the GitHub API once when the module enables.",
    )
    private val autoDownload by switch(
        "Auto download", false,
        desc = "Download new versions automatically and install them on the next Minecraft restart. " +
                "When off, the module only sends a chat notification with the release link.",
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

    // ---------------------------------------------------------------- runtime
    private val checkInFlight = AtomicBoolean(false)

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
            CurrentVersion.ofTag(currentModVersion),
            "cop",
        )
    }

    override fun onEnable() {
        super.onEnable()
        // Sweep stale `.autoupdates/cop/*` directories from any previous,
        // never-applied update so the disk doesn't slowly fill up.
        runCatching { context.cleanup() }
        if (checkOnLaunch) runCheck(reason = "on-enable")
    }

    init {
        // Re-check after a world join — covers the case where the user enabled
        // the module mid-session (so onEnable already fired before currentMod
        // was loaded) and wants the check to fire later.
        on<GameEvent.Load> { runCheck(reason = "game-load") }
    }

    private fun runCheck(reason: String) {
        if (!enabled) return
        if (!checkInFlight.compareAndSet(false, true)) return  // dedupe in-flight checks

        val stream = if (includePrereleases) "pre" else "full"
        CopMod.scope.launch(Dispatchers.IO) {
            try {
                val update = context.checkUpdate(stream).get()
                if (update.isUpdateAvailable) handleUpdateAvailable(update)
                else if (reason == "manual") modMessage("&aAuto Updater: already on the latest version (&f${currentModVersion}&a).")
            } catch (e: Exception) {
                CopMod.logger.warn("[AutoUpdater] check failed", e)
                if (reason == "manual") modMessage("&cAuto Updater: check failed — ${e.message ?: "unknown error"}")
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    private fun handleUpdateAvailable(update: PotentialUpdate) {
        val data = update.update
        val versionLabel = data.versionName ?: "?"
        val releaseUrl = (data as? GithubReleaseUpdateData)?.htmlUrl

        if (autoDownload) {
            CopMod.logger.info("[AutoUpdater] downloading update $versionLabel")
            update.launchUpdate().whenComplete { _, err ->
                if (err == null) {
                    modMessage("&aAuto Updater: downloaded &f$versionLabel&a — installs on next Minecraft restart.")
                } else {
                    CopMod.logger.warn("[AutoUpdater] download failed", err)
                    modMessage("&cAuto Updater: download failed — ${err.message ?: "unknown error"}")
                }
            }
        } else {
            // Notification-only path. Make the version label a clickable link to
            // the GitHub release page so the user can grab the jar by hand.
            val link = ChatUtils.literal("&b&n$versionLabel&r&a")
                .also { c ->
                    if (releaseUrl != null) {
                        c.style = Style.EMPTY.withClickEvent(ClickEvent.OpenUrl(URI.create(releaseUrl)))
                    }
                }
            val msg = ChatUtils.literal("&aAuto Updater: ").append(link).append(ChatUtils.literal("&a available"))
            ChatUtils.modMessage(msg)
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
                JsonPrimitive(release.tagName),
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
