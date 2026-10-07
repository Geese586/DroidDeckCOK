package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeInstallService
import java.io.File

/**
 * The whole first-time install as one job: pick the folder the releases were saved into and the
 * runtime, Valve's Proton, the Steam client, the desktop, its fonts and any third-party Proton go
 * in one after another.
 *
 * It is the way in for a device with no usable network, where the releases are fetched on a
 * computer first. The layout is the one the project builds - `tools/` and `download-steam-client.bat`
 * lay it out, and a copy of it is what a user carries across:
 *
 * ```
 * FirstLocalInstall/
 *     linuxfs.tar.zst                              the runtime; everything else refuses without it
 *     proton-experimental-arm64-<build>.tar.zst    Valve's Proton (ARM64)
 *     steam-client/                                the packages download-steam-client.bat fetched
 *     desktop.tar.zst                              the LXQt desktop, from the project's catalog
 *     fonts/                                       CJK fonts, for the desktop and everything else
 *     GE-Proton11-7-aarch64.tar.gz                 any third-party Proton, optional
 * ```
 *
 * The desktop comes after the Steam client because that is the order a device is used in - play
 * first, the desktop on top - and the fonts after the desktop, which is where they show up. Nothing
 * depends on either: the rootfs already carries fontconfig, so a font dropped in is picked up by
 * whatever starts next.
 *
 * Either the folder itself or its parent may be picked: a copy at /Download/FirstLocalInstall is
 * usually reached by picking /Download, and one picked for its contents - someone who opens
 * FirstLocalInstall and picks steam-client - is read where it stands.
 *
 * The folder a run reads is remembered, and it is what decides whether the play page's button is
 * still offered: while that folder holds something the device has not got the button is there, and
 * it goes once it does not. See [needed].
 *
 * Nothing here is a second implementation. Each part is installed by the same code the settings
 * page's own "from file" rows call; all this decides is what is in the folder, what is still
 * missing, and the order.
 *
 * Nothing is checked against an online catalog either. The archives were put in the folder by the
 * person installing them, and this path exists for a device that cannot reach GitHub - so a lookup
 * would be a request that ends in its timeout, minutes on "checking" before an install that was
 * never in doubt. Each part goes in unvouched for, exactly as it does when the network is off, and
 * the marker its own installer writes is what says it is there.
 */
internal object FirstLocalInstall {
    private const val TAG = "FirstLocalInstall"

    /** The folder as the project lays it out; a picked folder is searched for one named this. */
    const val NAME = "FirstLocalInstall"

    /** Where the Steam client's packages sit inside it. */
    private const val PACKAGES = "steam-client"

    /** Where the CJK fonts sit inside it; the folder itself works too, as for the packages. */
    private const val FONTS = "fonts"

    private const val RUNTIME_PREFIX = "linuxfs"
    private const val PROTON_PREFIX = "proton"
    private const val DESKTOP_PREFIX = "desktop"
    private const val ZST = ".tar.zst"

    /**
     * The parts, in the order they are installed. EXTRAS is the third-party Protons: the two build
     * ids come out of [ProtonExtras.tools], so there can be more than one of those.
     */
    enum class Part { RUNTIME, PROTON, CLIENT, DESKTOP, FONTS, EXTRAS }

    /**
     * One payload in the folder. [source] is the archive, or for the client the folder of packages.
     * [tool] says which third-party Proton an archive is, and is null for the rest.
     */
    class Found(val part: Part, val source: File, val tool: ProtonExtras.Tool? = null)

    /** What one step ended as: a problem to show the user, or null when it went in. */
    class Outcome(val found: Found, val problem: String?)

    /**
     * The folder to read: the picked one, or the FirstLocalInstall inside it. One picked for its
     * own contents keeps them, so picking steam-client directly works as well as picking its parent.
     */
    fun folder(picked: File): File {
        if (!picked.isDirectory) return picked
        val inside = picked.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(NAME, ignoreCase = true) }
        return inside ?: picked
    }

    /**
     * Everything in [folder] this app knows how to install, in the order it has to go in: the
     * runtime first, then Valve's Proton, the client, the desktop, its fonts, then any third-party
     * build.
     *
     * One archive per thing, deliberately. The Proton seed installs into one place and the client
     * is one tree, so a second archive of either would only overwrite the first; the name sorts
     * them so which one is taken is at least the same on every run. The desktop is the same - one
     * tar over the rootfs - and the fonts are one folder.
     */
    fun found(folder: File): List<Found> {
        val entries = folder.listFiles()?.toList() ?: return emptyList()
        val files = entries.filter { it.isFile }
        val out = mutableListOf<Found>()
        files.filter { it.name.lowercase().startsWith(RUNTIME_PREFIX) && it.name.lowercase().endsWith(ZST) }
            .minByOrNull { it.name }?.let { out += Found(Part.RUNTIME, it) }
        files.filter { it.name.lowercase().startsWith(PROTON_PREFIX) && it.name.lowercase().endsWith(ZST) }
            .minByOrNull { it.name }?.let { out += Found(Part.PROTON, it) }
        // The packages live in a folder of their own, but a folder picked for what is in it - the
        // steam-client directory itself - holds them directly, and that counts too.
        val packages = entries.firstOrNull { it.isDirectory && it.name.equals(PACKAGES, ignoreCase = true) }
            ?.takeIf { SteamClient.packageCount(it) > 0 }
            ?: folder.takeIf { SteamClient.packageCount(it) > 0 }
        packages?.let { out += Found(Part.CLIENT, it) }
        files.filter { it.name.lowercase().startsWith(DESKTOP_PREFIX) && it.name.lowercase().endsWith(ZST) }
            .minByOrNull { it.name }?.let { out += Found(Part.DESKTOP, it) }
        // The same rule as the packages: the fonts/ folder inside, or a folder that is one itself.
        val fonts = entries.firstOrNull { it.isDirectory && it.name.equals(FONTS, ignoreCase = true) }
            ?.takeIf { Fonts.fontFiles(it).isNotEmpty() }
            ?: folder.takeIf { Fonts.fontFiles(it).isNotEmpty() }
        fonts?.let { out += Found(Part.FONTS, it) }
        for (tool in ProtonExtras.tools) {
            files.filter { it.name.startsWith(tool.prefix) && tool.assetPattern.containsMatchIn(it.name) }
                .minByOrNull { it.name }?.let { out += Found(Part.EXTRAS, it, tool) }
        }
        return out
    }

    /** True when the device already holds what [found] carries. */
    fun installed(context: Context, found: Found): Boolean = when (found.part) {
        Part.RUNTIME -> LinuxRuntimeInstaller.installedVersion(context) != null
        // The seed's own appmanifest counts too, so a client that fetched Proton itself is left as
        // it is rather than overwritten with the archive.
        Part.PROTON -> !DesktopCatalog.protonSeedNeeded(context)
        Part.CLIENT -> SteamClient.installed(context)
        // The launcher the session actually runs, which is what the app's own desktop page tests.
        Part.DESKTOP -> DesktopCatalog.desktopInstalled(context)
        Part.FONTS -> Fonts.installed(context, found.source)
        Part.EXTRAS -> found.tool?.let { ProtonExtras.installed(context, it) != null } ?: false
    }

    /** [found] with everything the device already has taken out. */
    fun missing(context: Context, found: List<Found>): List<Found> = found.filterNot { installed(context, it) }

    /**
     * The folder the last run read from, while it is still there. Null when none was ever picked,
     * or when the one that was has gone - a folder taken off the device cannot be asked anything.
     */
    fun rememberedFolder(context: Context): File? = SessionPrefs.firstLocalFolder(context)
        .takeIf { it.isNotEmpty() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory }

    /** Records [folder] as both the one [needed] judges by and the one the picker opens at. */
    fun remember(context: Context, folder: File) {
        SessionPrefs.setFirstLocalFolder(context, folder.absolutePath)
    }

    /**
     * Whether the play page's first-time install button belongs: true while the device is short of
     * something a folder install would put in.
     *
     * The runtime, Valve's Proton and the Steam client are asked about first and always count - a
     * device without them is not set up at all. Everything else is judged by the folder the last
     * run was read from, which is what the button is for in the first place: while that folder
     * still holds something the device has not got - the desktop, the fonts, a third-party Proton -
     * the button stays, and it goes once everything in the folder has gone in. It is the folder
     * that answers, so a folder that carries no third-party Proton never keeps the button alive,
     * and one the user has emptied of everything they want stops asking as soon as they are in.
     *
     * With no folder to ask - none picked yet, or the one picked has since gone - the desktop's own
     * check stands in for it, as it did before a folder was remembered at all.
     *
     * Called off the main thread: this lists a folder and stats what it finds.
     */
    fun needed(context: Context): Boolean {
        if (LinuxRuntimeInstaller.installedVersion(context) == null) return true
        if (DesktopCatalog.protonSeedNeeded(context)) return true
        if (!SteamClient.installed(context)) return true
        val folder = rememberedFolder(context) ?: return !DesktopCatalog.desktopInstalled(context)
        return missing(context, found(folder)).isNotEmpty()
    }

    /**
     * Installs [found] one after another, blocking, so it is meant for a worker thread.
     *
     * Each part goes in with no catalog entry - this path never looks one up, see the class comment -
     * which every installer takes as "nothing can vouch for these bytes": the runtime records the
     * archive's own digest, the desktop and the seed record theirs, and the third-party Proton is
     * left alone rather than refused. The archive belongs to the user and is never deleted.
     *
     * [onProgress] gets the step's number and the count before it and again as that step reports,
     * so a step's own stage is never read as the whole run's. A step that fails does not stop the
     * ones after it - except the runtime, which nothing else can be installed without.
     */
    fun install(context: Context, found: List<Found>, onProgress: (Int, Int, String, Int) -> Unit): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        found.forEachIndexed { index, item ->
            val number = index + 1
            val total = found.size
            onProgress(number, total, "Preparing…", -1)
            val listener = LinuxRuntimeInstaller.ProgressListener { stage, percent ->
                onProgress(number, total, stage, percent)
            }
            val problem = try {
                when (item.part) {
                    Part.RUNTIME -> {
                        // In the foreground, so a long unpack is not killed if the screen is left.
                        RuntimeInstallService.startLocal(context, item.source, null)
                        if (LinuxRuntimeInstaller.installFromFile(context, item.source, null, listener)) null
                        else "the archive could not be installed"
                    }
                    Part.PROTON -> DesktopCatalog.installSeedFromFile(context, null, item.source, listener)
                    Part.CLIENT -> SteamClient.installFromDir(context, item.source) { stage, percent ->
                        onProgress(number, total, stage, percent)
                    }
                    // The desktop unpacks over the rootfs like the runtime does, so it goes through
                    // the same extractor - and is recorded under its own id, which is what keeps a
                    // desktop installed with no catalog reachable from being marked as the seed.
                    Part.DESKTOP -> DesktopCatalog.installTarFromFile(
                        context, DesktopCatalog.DESKTOP_ID, null, item.source, listener)
                    Part.FONTS -> Fonts.install(context, item.source) { stage, percent ->
                        onProgress(number, total, stage, percent)
                    }
                    Part.EXTRAS -> item.tool?.let { tool ->
                        ProtonExtras.installFromFile(context, tool, item.source, null) { stage, percent ->
                            onProgress(number, total, stage, percent)
                        }
                    } ?: "no compatibility tool matches that archive"
                }
            } catch (e: Exception) {
                Log.e(TAG, "install ${item.part} from ${item.source}", e)
                e.message ?: "could not be installed"
            }
            outcomes += Outcome(item, problem)
            // Everything after the runtime needs it, so one that did not go in ends the run here:
            // the three that follow would only repeat "install the Linux runtime first".
            if (item.part == Part.RUNTIME && problem != null) return outcomes
        }
        return outcomes
    }
}
