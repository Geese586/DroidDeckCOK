package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
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
 */
object SteamClient {
    private const val TAG = "SteamClient"

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

    /**
     * Installs the client from a folder of packages the user fetched elsewhere, downloading only
     * the pieces that are missing from it. Returns null when it is in, or a message for the user.
     */
    fun installFromDir(context: Context, dir: File, onProgress: (String, Int) -> Unit): String? {
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
            installNow(context, dir, onProgress)
        } finally {
            installInProgress = false
        }
    }

    private fun installNow(context: Context, dir: File, onProgress: (String, Int) -> Unit): String? {
        onProgress("Preparing…", -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".steam-install-rt").apply { mkdirs() }
            val guest = mutableListOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                // The branch has to be the session's. The script records it in package/beta and the
                // session shim kills a client whose beta disagrees with the configured branch, so an
                // install made on the wrong one is a restart loop rather than a session.
                "BL_STEAM_CHANNEL=" + SessionPrefs.steamChannel(context),
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
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var total = 0
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
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
                }
            }
            val status = child.waitFor()
            if (status != 0) "Steam client installation failed (exit $status)"
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
