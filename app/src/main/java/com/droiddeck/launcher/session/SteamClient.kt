package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.R
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
 * Valve at all - is the one that does, and a folder it cannot run in is refused before the guest is
 * started rather than quietly turned into a download (see [hasManifest] and [manifestComponents]).
 *
 * Nothing downloads on that path, at any point: the folder's manifest is the whole list this step
 * installs from, and a component of it that the folder is short of is a message rather than a
 * fetch - the download for it belongs to the session, which starts with the same folder and no
 * offline switch and therefore takes what is there and fetches the rest (see SessionService).
 */
object SteamClient {
    private const val TAG = "SteamClient"

    /** Where the client installer's own output goes: the folder the session log share carries. */
    private const val LOG_NAME = "first-install-client"

    /** A component's file name as Valve's manifest writes it. */
    private val COMPONENT = Regex(""".*\.zip\.[0-9a-f]{40}$""")

    /** The client unpacks to a little under 1 GB, and one component's zip sits beside it while it does. */
    private const val NEED_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * How long the installer may say nothing at all before it is taken for wedged and killed.
     *
     * Ten minutes is what [GuestOutput] gives every other guest command, and it is the wrong number
     * for this one. These are 344 MB of packages sha256-verified and unpacked inside proot: a device
     * that takes ten minutes over the lot is behaving normally, and giving up on it there would turn
     * a slow install into a failed one - which is the opposite of what the deadline is for, so it
     * has to be longer than the honest case rather than shorter. Half an hour, which is also the
     * budget the install's own screen warns against (FirstLocalInstall.CLIENT_STALL_MS): the step
     * may be slow, and slow is not stuck.
     */
    internal const val SILENCE_MS = 30L * 60 * 1000

    /**
     * The component counter the script prints: "(5/24, 105 MB, from <host>)" on a download, and
     * "(1/1)" when the component comes out of the folder instead.
     *
     * <p>Deliberately left unclosed by the pattern: the download's counter carries the component's
     * size and the edge it is coming from before its own bracket, so requiring that bracket here
     * matched the local-copy line alone - the one shape an install that has a network never prints.
     * The count and the percentage were therefore never seen on a downloaded component at all.
     */
    private val STEP = Regex("""\((\d+)/(\d+)""")

    /**
     * A "== STEP" line and what it says: the phase in the script's own words. This is the stage
     * where the counter above is not the whole story - of which the manifest fetch is the one that
     * matters, a file with no component count in a couple of hundred kilobytes that used to print
     * nothing at all, leaving the screen on "Preparing…" for as long as it took (DroidDeckCOK).
     *
     * <p>The retry line ("… (again, from <host>)") is deliberately excluded, see [RETRY].
     */
    private val STEP_MESSAGE = Regex("""^== STEP \S+ (.*)$""")

    /** What the retry line is recognised by, so the line above can leave it alone. */
    private const val RETRY = "(again, from "

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
     * The branch both sides install from. The script pins `steamdeck_publicbeta` itself - it has no
     * BL_STEAM_CHANNEL from this app, which leaves it out on purpose (see [installNow]) - so this is
     * the channel the folder's manifest has to be for. See [manifestName].
     */
    internal const val CHANNEL = "steamdeck_publicbeta"

    /**
     * Valve's manifest as the script looks it up inside the folder: `steam_client_<channel>_linuxarm64`.
     * The name matters to both sides - the script tests the folder for exactly this file and reads
     * no other, and the app has to judge the folder by the same one.
     */
    internal fun manifestName(): String = "steam_client_${CHANNEL}_linuxarm64"

    /**
     * Whether [dir] holds the manifest of that name - the one file a local-only install reads
     * instead of the CDN's, and the only thing that can vouch for the components lying beside it.
     *
     * That exact name rather than "a file whose name starts with steam_client_": the script looks
     * the folder's manifest up by name, so a copy taken from the other branch, or the same file
     * saved as `….txt` by a browser, leaves it with nothing to verify against while the folder
     * still looks filled. Getting that wrong is quiet: the switch below would not be set and the
     * install would go back to Valve's CDN, on the one path that exists for a device that cannot
     * reach it (DroidDeckCOK).
     */
    fun hasManifest(dir: File): Boolean = File(dir, manifestName()).isFile

    /** A component block's own line in Valve's manifest: one tab, the block's name, nothing else. */
    private val BLOCK = Regex("""^\t"([^"]+)"[ \t]*$""")

    /** One of that block's fields: two tabs, the key, tabs, the value. */
    private val FIELD = Regex("^\\t\\t\"([^\"]+)\"\\t+\"([^\"]*)\"")

    /**
     * The component files the folder's own manifest says the client is made of - the same list the
     * guest script builds, and by the same rule: a block whose name ends in `_all` or
     * `_linuxarm64_linuxarm64` is a component, and its `file` is the name the install looks for in
     * the folder. Valve's manifest carries four times as many blocks; the rest are the ones this
     * client does not unpack, and needing them here would refuse a folder that is complete.
     *
     * Read on this side so that a folder short of a component can be refused with the names, before
     * a guest is started for a run that can only end in an error (see [installFromDir]). Empty when
     * there is no manifest or nothing in it parses, and that emptiness is deliberate: the script is
     * the authority on the folder, and a parse done here must never be the reason a folder the
     * script could have installed from is turned away.
     */
    internal fun manifestComponents(dir: File): List<String> {
        val text = try {
            File(dir, manifestName()).takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            Log.w(TAG, "manifest in $dir: $e")
            null
        } ?: return emptyList()
        val out = mutableListOf<String>()
        var block: String? = null
        for (raw in text.lineSequence()) {
            // A manifest copied through Windows can arrive with CRLF; the script reads the same file
            // with awk, where a trailing \r would be part of the name, so this reading drops it and
            // finds the same names either way.
            val line = raw.trimEnd('\r')
            val header = BLOCK.find(line)
            if (header != null) {
                block = header.groupValues[1]
                continue
            }
            val field = FIELD.find(line) ?: continue
            if (block == null || field.groupValues[1] != "file") continue
            if (block.endsWith("_all") || block.endsWith("_linuxarm64_linuxarm64")) {
                out += field.groupValues[2]
            }
        }
        return out
    }

    /**
     * The components [dir]'s own manifest names that the folder does not hold, in the manifest's
     * own order - what an offline run can only end in an error over, since it fetches nothing and
     * every one of them is verified against a checksum it does not have.
     */
    internal fun missingComponents(dir: File): List<String> =
        manifestComponents(dir).filterNot { File(dir, it).isFile }

    /**
     * How many of the missing components are named in the message that refuses the run. A folder
     * built from a stale list can be short of the lot, and seventeen file names are not a report -
     * the count that goes with them is what says how much work fetching the rest is.
     */
    private const val MISSING_NAMED = 4

    /** What the script prefixes its own complaints with. */
    private const val COMPLAINT = "droiddeck-steam-install: "

    /** The install's own summary of how much a stale folder still has to fetch (DroidDeckCOK). */
    private val SUMMARY = Regex("""Steam client: (\d+) components? to fetch \((?:about (\d+) MB), (\d+) of (\d+) in the folder\)""")

    /**
     * Installs the client from a folder of packages the user fetched elsewhere, downloading only
     * the pieces that are missing from it - unless [localOnly], which downloads nothing at all and
     * takes the folder as it stands. That mode is refused outright when the folder carries no
     * manifest of [manifestName]'s name, because the script has nothing to verify the packages
     * against without one and would otherwise go back to Valve's CDN for it - a download, from the
     * one path that exists for a device that has no network to download over. A folder that is
     * short of a component the manifest names is refused in the same way, with the names: the run
     * could only end in the script's error, and the download for those belongs to the session that
     * starts with this folder (see SessionService), not to this step.
     *
     * [cdn] pins one of Valve's edges (a [SteamCdn] choice other than [SteamCdn.AUTO]); empty
     * leaves the script's own order. Returns null when it is in, or a message for the user.
     */
    fun installFromDir(context: Context, dir: File, localOnly: Boolean = false, cdn: String = "",
                       onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "A Steam client install is already running"
        if (SessionState.running) return "Stop the active session before installing the Steam client"
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        if (!dir.isDirectory) return "That folder cannot be read"
        if (packageCount(dir) == 0) return "That folder holds no Steam client packages"
        if (localOnly) {
            if (!hasManifest(dir)) {
                return context.getString(R.string.first_local_client_manifest, manifestName())
            }
            val missing = missingComponents(dir)
            if (missing.isNotEmpty()) {
                val named = missing.take(MISSING_NAMED).joinToString("\n") +
                    if (missing.size > MISSING_NAMED) "\n…" else ""
                return context.getString(R.string.first_local_client_missing, missing.size, named)
            }
        }
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

    /**
     * The guest-side environment of a client install, as the "NAME=value" elements that ride the
     * command line. They must be command elements and nothing else: the guest command starts with
     * "env -i", which discards the environment proot was handed, so a variable set on the proot
     * process is read by no one (see installNow, where that mistake spent a lifetime). Internal so
     * a test can pin the pair that matters: the offline switch is passed when it was asked for,
     * and an automatic CDN choice contributes nothing, because the script reads any value it does
     * not know as a host to use alone.
     */
    internal fun guestEnvEntries(localOnly: Boolean, cdn: String): List<String> {
        val entries = mutableListOf<String>()
        if (localOnly) entries.add("BL_STEAM_LOCAL_ONLY=1")
        SteamCdn.guestValue(cdn).takeIf { it.isNotEmpty() }?.let { entries.add("BL_STEAM_CDN=" + it) }
        return entries
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
            )
            // Everything the script itself reads has to ride this command line. The command starts
            // with "env -i", which throws away the environment proot was handed, so a variable set
            // on the proot process alone never reaches the script at all - which is how the offline
            // switch below spent its whole life: set faithfully on every first-time install, read
            // by no one, and every one of those installs downloaded the client from Valve with the
            // packages sitting in the folder beside it. PROOT_* and LD_LIBRARY_PATH are different:
            // proot reads those itself, on the host side, before env ever runs.
            guest.addAll(guestEnvEntries(localOnly, cdn))
            guest.add("/usr/local/bin/droiddeck-steam-install")
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
            // install that quietly waited on it would look like a hang. Set whenever the caller asked
            // for the offline mode, with no second look at the folder: installFromDir has already
            // refused a folder the script could not work from, and a condition here would turn a
            // missing manifest back into a download rather than into a message (DroidDeckCOK).
            // It rides the guest's command line - see the note above guestEnvEntries.
            // One of Valve's two edges, pinned by the user's choice in Setup. Empty (automatic)
            // sets nothing: the script's own case treats the variable's absence as its two-edge
            // order, and an empty value would be read as a host to use alone - a failed install.
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var total = 0
            // The script's own complaint, when it made one. Its messages name the block and the
            // file, so they say more than an exit status can - and they are the only account of the
            // one case a check on this side cannot see: a local copy whose checksum fails, which
            // the script treats as absent and then, offline, gives up over.
            var complaint: String? = null
            // The output is read with a deadline (GuestOutput): the client's installer is the
            // longest guest command in the install, so if its proot child wedges this is where a
            // run would otherwise sit for ever on one line of the progress bar. Its deadline is the
            // long one - see SILENCE_MS.
            val result = GuestOutput.collect(
                context, child, LOG_NAME, "droiddeck-steam-install", SILENCE_MS) { line ->
                Log.i(TAG, line)
                line.takeIf { it.startsWith(COMPLAINT) }?.let { complaint = it.removePrefix(COMPLAINT).trim() }
                val step = STEP.find(line)
                val message = STEP_MESSAGE.find(line)?.groupValues?.get(1)
                    ?.takeIf { !line.contains(RETRY) }
                when {
                    step != null -> {
                        val done = step.groupValues[1].toIntOrNull() ?: 0
                        total = step.groupValues[2].toIntOrNull() ?: total
                        val percent = if (total > 0) done * 100 / total else -1
                        // The script's own line is the stage: it names the component, its size and
                        // the edge it is coming from, which is what a wait of minutes is actually
                        // waiting on. The counter in it is also the percentage.
                        onProgress(message ?: "Installing the Steam client ($done/$total)", percent)
                    }
                    // A phase with no count of its own - the manifest fetch, above all. The retry
                    // line arrives here as null: it carries no count either, and the count and the
                    // percentage the attempt before it put up are the evidence to keep.
                    message != null -> onProgress(message, -1)
                    line.contains("manifest", ignoreCase = true) ->
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
            } else if (result.status != 0) {
                // The script's own words when it has any: on the offline path that is the component
                // and the file it is missing, which is what a report has to carry - "exit 1" alone
                // leaves the user with a step that failed and nothing to do about it.
                complaint?.let { "$it (exit ${result.status})" }
                    ?: "Steam client installation failed (exit ${result.status})"
            } else {
                onProgress("Steam client ready", 100)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install from $dir", e)
            e.message ?: "Could not install the Steam client"
        }
    }
}
