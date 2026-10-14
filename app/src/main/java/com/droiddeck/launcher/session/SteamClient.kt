package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.runtime.GuestOutput
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Valve's own arm64 Steam client. The app cannot ship it - it is not redistributable - so a Steam
 * session downloads it from Valve's CDN the first time it runs. On a network that cannot reach that
 * CDN quickly, the same packages can be fetched elsewhere (tools/steam-client-fetch.py) and
 * installed from a folder here.
 *
 * The folder is handed to the guest untouched: droiddeck-steam-install reads Valve's manifest,
 * takes every component it finds in the folder and downloads only the rest, checking each one -
 * local copy or download - against the sha256 the manifest publishes. Nothing is verified on this
 * side, because that manifest is the only thing that can vouch for a file and it lives inside the
 * image, where the script that consumes it also lives.
 *
 * A caller may put the script into its own offline mode ([installFromDir]'s `localOnly`): it then
 * takes the manifest out of the folder and installs from it alone, fetching nothing. The
 * first-time install from a carried folder - the path that exists for a device which cannot reach
 * Valve at all - is the one that does.
 */
object SteamClient {
    private const val TAG = "SteamClient"

    /** Where the client installer's own output goes: the folder the session log share carries. */
    private const val LOG_NAME = "first-install-client"

    /** A component's file name as Valve's manifest writes it. */
    private val COMPONENT = Regex(""".*\.zip\.[0-9a-f]{40}$""")

    /** The client unpacks to a little under 1 GB, and one component's zip sits beside it while it does. */
    private const val NEED_BYTES = 2L * 1024 * 1024 * 1024

    private val STEP = Regex("""\((\d+)/(\d+)\)""")

    @Volatile
    var installInProgress = false
        private set

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")

    /** ~/.local/share/Steam inside the runtime, where the client unpacks itself. */
    fun root(context: Context) = File(home(context), ".local/share/Steam")

    /**
     * Whether the client is in the runtime. These are the two files droiddeck-steam-install tests
     * before it decides there is nothing to do, so the offer this drives is shown exactly when the
     * script would actually install something.
     */
    fun installed(context: Context): Boolean =
        File(root(context), "steamrtarm64/steam").isFile &&
            File(root(context), "package/droiddeck-installed").isFile

    /**
     * Everything [dir] holds that the install can use: one file per component, plus Valve's
     * manifest when the folder was filled by steam-client-fetch.py.
     */
    fun packageCount(dir: File): Int = dir.listFiles()
        ?.count { it.isFile && (COMPONENT.matches(it.name) || it.name.startsWith("steam_client_")) }
        ?: 0

    /** Whether [dir] holds Valve's manifest - the file a local-only install reads instead of the CDN's. */
    fun hasManifest(dir: File): Boolean = dir.listFiles()?.any { it.isFile && isManifest(it.name) } ?: false

    /**
     * Whether [name] is that manifest rather than one of the component zips beside it. The folder
     * carries both and the script tells them apart the same way: a component is `<name>.zip.<sha1>`
     * - Valve's own naming - and everything else the manifest is called.
     */
    internal fun isManifest(name: String): Boolean = name.startsWith("steam_client_") && !COMPONENT.matches(name)

    /** The install's own summary of how much a stale folder still has to fetch (DroidDeckCOK). */
    private val SUMMARY = Regex("""Steam client: (\d+) components? to fetch \((?:about (\d+) MB), (\d+) of (\d+) in the folder\)""")

    /**
     * Installs the client from a folder of packages the user fetched elsewhere, downloading only
     * the pieces that are missing from it - unless [localOnly], which downloads nothing at all and
     * takes the folder as it stands. [cdn] pins one of Valve's edges (a [SteamCdn] choice other
     * than [SteamCdn.AUTO]); empty leaves the script's own order. Returns null when it is in, or
     * a message for the user.
     */
    fun installFromDir(context: Context, dir: File, localOnly: Boolean = false, cdn: String = "",
                       onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "A Steam client install is already running"
        if (SessionState.running) return "Stop the active session before installing the Steam client"
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        if (!dir.isDirectory) return "That folder cannot be read"
        if (packageCount(dir) == 0) return "That folder holds no Steam client packages"
        // A run that runs out of room stops part-way and has to start over, so it is refused up
        // front with a number instead. The client is the largest single thing the runtime holds.
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES) {
            return "At least 2 GB free is required to install the Steam client"
        }

        installInProgress = true
        return try {
            installNow(context, dir, localOnly, cdn, onProgress)
        } finally {
            installInProgress = false
        }
    }

    private fun installNow(context: Context, dir: File, localOnly: Boolean, cdn: String,
                           onProgress: (String, Int) -> Unit): String? {
        onProgress("Preparing…", -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".steam-install-rt").apply { mkdirs() }
            val guest = mutableListOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                // No BL_STEAM_CHANNEL: upstream 0.4.0 dropped the branch setting and both guest
                // scripts now pin steamdeck_publicbeta themselves (droiddeck-session forces it on
                // the command line, droiddeck-steam-install records it in package/beta). Leaving
                // it out keeps an install made here on the same branch the session expects, which
                // is what the shim's beta check compares.
                "BL_STEAM_LOCAL_DIR=" + dir.absolutePath,
                "/usr/local/bin/droiddeck-steam-install",
            )
            // The guest opens the packages itself, so the folder they are in has to be visible
            // inside. The app's own directories are bound into every command; a folder anywhere
            // else - which is where a picked one lives - is not, so it is bound at the same path.
            val extraBinds = listOf(dir.absolutePath)
            val command = LinuxRuntime.command(context, null, runtimeDir, null, extraBinds, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            // As everywhere else proot is run from the app: the install used to skip this, and a
            // kernel that mishandles proot's seccomp filter leaves the child - and the install -
            // waiting for ever (see SessionPrefs.prootNoSeccomp).
            if (SessionPrefs.prootNoSeccomp(context)) hostEnv["PROOT_NO_SECCOMP"] = "1"
            // The script's own offline switch: it then reads Valve's manifest out of the folder and
            // refuses to download anything, which is what the first-time install from a carried
            // folder wants - the device that path exists for cannot reach Valve's CDN at all, and an
            // install that quietly waited on it would look like a hang. Given only when the folder
            // actually carries that manifest, because the script refuses a local-only run without
            // one outright (see droiddeck-steam-install).
            if (localOnly && hasManifest(dir)) hostEnv["BL_STEAM_LOCAL_ONLY"] = "1"
            // One of Valve's two edges, pinned by the user's choice in Setup. Empty (automatic)
            // sets nothing: the script's own case treats the variable's absence as its two-edge
            // order, and an empty value would be read as a host to use alone - a failed install.
            SteamCdn.guestValue(cdn).takeIf { it.isNotEmpty() }?.let { hostEnv["BL_STEAM_CDN"] = it }
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var total = 0
            // The output is read with a deadline (GuestOutput): the client's installer is the
            // longest guest command in the install, so if its proot child wedges this is where a
            // run would otherwise sit for ever on one line of the progress bar.
            val result = GuestOutput.collect(context, child, LOG_NAME, "droiddeck-steam-install") { line ->
                Log.i(TAG, line)
                val step = STEP.find(line)
                if (step != null) {
                    val done = step.groupValues[1].toIntOrNull() ?: 0
                    total = step.groupValues[2].toIntOrNull() ?: total
                    val percent = if (total > 0) done * 100 / total else -1
                    onProgress("Installing the Steam client ($done/$total)", percent)
                } else if (line.contains("manifest", ignoreCase = true)) {
                    onProgress("Reading Valve's manifest…", -1)
                }
                // The summary the script writes before the first component moves: on a stale
                // folder the work left is the components Valve replaced, and saying how many
                // - against how many the folder already covers - is the difference between a
                // wait that makes sense and one that looks like it never ends.
                val summary = SUMMARY.find(line)
                if (summary != null) {
                    onProgress(
                        "Fetching ${summary.groupValues[1]} components " +
                            "(about ${summary.groupValues[2]} MB); " +
                            "${summary.groupValues[3]} of ${summary.groupValues[4]} come from the folder",
                        -1,
                    )
                }
            }
            if (result.timedOut) {
                "Steam client installation stopped answering (no output for ${result.silentFor / 60_000 + 1} min) and was stopped"
            } else if (result.status != 0) "Steam client installation failed (exit ${result.status})"
            else {
                onProgress("Steam client ready", 100)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install from $dir", e)
            e.message ?: "Could not install the Steam client"
        }
    }
}
