package com.droiddeck.launcher.session

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverZip
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.DesktopLanguage
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeInstallService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The whole first-time install as one job: pick the folder the releases were saved into and the
 * runtime, Valve's Proton, the Steam client, the desktop, the desktop's Chinese translations, its
 * fonts, any third-party Proton and the GPU drivers go in one after another.
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
 *     desktop-kde.tar.zst                          the KDE desktop 0.4.0 pins (desktop-kde-r2)
 *     desktop-l10n.tar.zst                         the desktop's Chinese translations, optional
 *     fonts/                                       CJK fonts, for the desktop and everything else
 *     GE-Proton11-7-aarch64.tar.gz                 any third-party Proton, optional
 *     proton-cachyos-<build>-arm64.tar.xz          ... one per build: GE-Proton, proton-cachyos
 *     DroidDeck-Proton-<build>-aarch64.tar.gz      and the project's own, each when it is there
 *     GPUDrivers/                                  the GPU drivers tab's "Export drivers" output
 * ```
 *
 * The desktop comes after the Steam client because that is the order a device is used in - play
 * first, the desktop on top - and the fonts after the desktop, which is where they show up. Nothing
 * depends on either: the rootfs already carries fontconfig, so a font dropped in is picked up by
 * whatever starts next.
 *
 * The GPU drivers go last and are one step, not one per zip: the folder's `GPUDrivers/` holds what
 * the drivers tab's own "Export drivers" wrote, and every zip in it is put in the way that tab's
 * "Import .zip" would - each in the list its own contents belong to, a bundle's two halves together.
 * See [DriverZip] for how a zip is recognised and how this tells whether the device already has it.
 * A `linuxfs.tar.zst` carried on its own is not a driver, and neither is anything else that is not
 * one of those zips.
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
 *
 * And nothing that goes in reaches out afterwards. Two of the installers a part can be handed to
 * do exactly that: the Steam client's own script asks Valve's CDN for a newer manifest unless it is
 * told the folder is all there is, and a compatibility tool is followed by an esync pack lookup
 * unless it is told not to (see [SteamClient.installFromDir] and [ProtonExtras.installFromFile]).
 * Both are turned off here - this is the one path whose whole point is that the device has no
 * network - so a run never waits on a request it cannot complete.
 */
internal object FirstLocalInstall {
    private const val TAG = "FirstLocalInstall"

    /** The folder as the project lays it out; a picked folder is searched for one named this. */
    const val NAME = "FirstLocalInstall"

    /** Where the Steam client's packages sit inside it. */
    private const val PACKAGES = "steam-client"

    /** Where the CJK fonts sit inside it; the folder itself works too, as for the packages. */
    private const val FONTS = "fonts"

    /** Where the exported GPU drivers sit inside it; the folder itself works too. */
    private const val DRIVERS = "GPUDrivers"

    private const val RUNTIME_PREFIX = "linuxfs"
    private const val PROTON_PREFIX = "proton"
    private const val DESKTOP_PREFIX = "desktop"
    private const val DESKTOP_L10N_PREFIX = "desktop-l10n"
    private const val ZST = ".tar.zst"

    /**
     * The marker the desktop translation pack is recorded under, in the shape the desktop and the
     * seed record themselves (`.droiddeck-pkg-<id>`, see [DesktopCatalog]). The pack is a patch laid
     * over an already-installed desktop and carries no marker of its own, and the runtime it patches
     * ships zh locales of its own - glibc's catalogues, glib20, tar, fontconfig - so the locale tree
     * cannot tell whether *this* pack went in. Its own mark can.
     */
    private const val DESKTOP_L10N_MARKER = ".droiddeck-pkg-desktop-l10n"

    /** What an archive unpacks to, as a multiple of its own size; zstd, xz and gzip read alike. */
    private const val EXPANSION = 3L

    /** Headroom on top of that, for what is written beside an archive while it unpacks. */
    private const val HEADROOM = 1L shl 30

    /**
     * The parts, in the order they are installed. EXTRAS is the third-party Protons: a build id and
     * an asset pattern come out of [ProtonExtras.tools], so there is one of those per build. GPU is
     * the folder of exported driver zips, and is one step however many drivers are in it.
     */
    enum class Part { RUNTIME, PROTON, CLIENT, DESKTOP, L10N, FONTS, EXTRAS, GPU }

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
     * runtime first, then Valve's Proton, the client, the desktop, its fonts, any third-party build,
     * and last the folder of GPU drivers.
     *
     * One archive per thing, deliberately. The Proton seed installs into one place and the client
     * is one tree, so a second archive of either would only overwrite the first; the name sorts
     * them so which one is taken is at least the same on every run. The desktop is the same - one
     * tar over the rootfs - and the fonts are one folder. The drivers are the exception that proves
     * it: a folder of them is a step of its own, and every zip in it is put in, because each one is
     * a driver rather than another copy of the same thing.
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
        files.filter {
            it.name.lowercase().startsWith(DESKTOP_PREFIX) &&
                it.name.lowercase().endsWith(ZST) &&
                !it.name.lowercase().startsWith(DESKTOP_L10N_PREFIX)
        }
            .minByOrNull { it.name }?.let { out += Found(Part.DESKTOP, it) }
        // The desktop's Chinese translations also answer to the "desktop" prefix, and are a step
        // of their own right after it: upstream's desktop package ships English only, and a device
        // whose desktop already went in must still be able to take the translations without
        // unpacking the whole desktop again. Unpacked straight over the rootfs, and recorded under
        // a marker of their own so the step can tell it went in (see [l10nInstalled]).
        files.filter {
            it.name.lowercase().startsWith(DESKTOP_L10N_PREFIX) && it.name.lowercase().endsWith(ZST)
        }
            .sortedBy { it.name }
            .forEach { out += Found(Part.L10N, it) }
        // The same rule as the packages: the fonts/ folder inside, or a folder that is one itself.
        val fonts = entries.firstOrNull { it.isDirectory && it.name.equals(FONTS, ignoreCase = true) }
            ?.takeIf { Fonts.fontFiles(it).isNotEmpty() }
            ?: folder.takeIf { Fonts.fontFiles(it).isNotEmpty() }
        fonts?.let { out += Found(Part.FONTS, it) }
        for (tool in ProtonExtras.tools) {
            files.filter { it.name.startsWith(tool.prefix) && tool.assetPattern.containsMatchIn(it.name) }
                .minByOrNull { it.name }?.let { out += Found(Part.EXTRAS, it, tool) }
        }
        // The drivers are the one part that is a folder of several files rather than one archive,
        // and the one where "one per thing" does not apply: each zip is a driver of its own, and the
        // step is the folder so a run of them is one line of progress and one line of report. The
        // same rule as the packages and the fonts for where it may sit; only zips the export wrote
        // count, so a stray archive dropped in beside them is left alone.
        val drivers = entries.firstOrNull { it.isDirectory && it.name.equals(DRIVERS, ignoreCase = true) }
            ?.takeIf { driverZips(it).isNotEmpty() }
            ?: folder.takeIf { it.name.equals(DRIVERS, ignoreCase = true) && driverZips(it).isNotEmpty() }
        drivers?.let { out += Found(Part.GPU, it) }
        return out
    }

    /** The driver zips a folder of them holds, by name: what "Export drivers" writes. */
    private fun driverZips(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && DriverZip.read(it) != null }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * True when the desktop's Chinese translations are already in: the marker the install step
     * writes once the pack is unpacked (see [DESKTOP_L10N_MARKER]).
     *
     * Deliberately not "a zh locale directory is under /usr/share/locale". The runtime ships those
     * of its own - glibc's catalogues for glib20, tar, fontconfig and the rest, in both zh_CN and
     * zh_TW - so that test reads true on a device the pack has never touched, and the step would
     * count as done the moment it is offered, leaving the button dark and Plasma in English. The
     * patch's own mark is the only thing that can answer for the patch.
     */
    fun l10nInstalled(context: Context): Boolean =
        File(LinuxRuntime.rootDir(context), DESKTOP_L10N_MARKER).isFile

    /**
     * About how much room [found] needs on the volume the install lands on, for the message shown
     * before a run that cannot finish. Each archive is counted at what it unpacks to rather than at
     * what it is on disk, since that is what sits there afterwards - a compressed rootfs grows by
     * about three times - with a flat headroom over the lot. A step's own installer still does its
     * own check; this one exists to say no before ten gigabytes go in and the volume fills at four.
     */
    fun spaceNeeded(found: List<Found>): Long =
        found.sumOf { sizeOf(it.source) } * EXPANSION + HEADROOM

    /** What [source] is on disk: one archive, or every file of a folder of packages, fonts or zips. */
    private fun sizeOf(source: File): Long =
        if (source.isFile) source.length()
        else source.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** True when the device already holds what [found] carries. */
    fun installed(context: Context, found: Found): Boolean = when (found.part) {
        Part.RUNTIME -> LinuxRuntimeInstaller.installedVersion(context) != null
        // The seed's own appmanifest counts too, so a client that fetched Proton itself is left as
        // it is rather than overwritten with the archive.
        Part.PROTON -> !DesktopCatalog.protonSeedNeeded(context)
        Part.CLIENT -> SteamClient.installed(context)
        // The launcher the session actually runs, which is what the app's own desktop page tests.
        Part.DESKTOP -> DesktopCatalog.desktopInstalled(context)
        // The translations' own verdict, so a desktop already in does not take this step with it:
        // without this a folder carrying the pack would keep the step, and the button, forever.
        Part.L10N -> l10nInstalled(context)
        Part.FONTS -> Fonts.installed(context, found.source)
        Part.EXTRAS -> found.tool?.let { ProtonExtras.installed(context, it) != null } ?: false
        // The folder of drivers counts as held only when every zip in it does: the step is the whole
        // folder, so one driver still missing keeps it - and the button - on offer.
        Part.GPU -> driverZips(found.source).all { zip ->
            DriverZip.read(zip)?.let { DriverZip.installed(context, it) } ?: true
        }
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
     * still holds something the device has not got - the desktop, the fonts, a third-party Proton,
     * the GPU drivers - the button stays, and it goes once everything in the folder has gone in. It
     * is the folder that answers, so a folder that carries no third-party Proton never keeps the
     * button alive, and one the user has emptied of everything they want stops asking as soon as
     * they are in. The drivers are read the same way: the folder of them is one step, so it counts
     * as held only when every zip in it is, and the last driver of a folder is what takes the
     * button away.
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
     *
     * [onStall] is called once when a step has made no progress at all for [STALL_MS] - a device
     * with a wedged proot child looks exactly like a slow one from here, and the screen says so
     * rather than showing the same line for ever. Nothing is killed for it: the watchdog in
     * [runtime.GuestOutput] is what gives up on a child of ours, and an unpack that is merely slow
     * must not be cut off.
     *
     * Every step boundary and every failure also goes to [FirstInstallLog], because the report that
     * ends a run is a Toast on a screen the user may not be looking at.
     */
    fun install(
        context: Context,
        found: List<Found>,
        onProgress: (Int, Int, String, Int) -> Unit,
        onStall: ((String, Int) -> Unit)? = null,
    ): List<Outcome> {
        val started = System.currentTimeMillis()
        val lastEvent = AtomicLong(started)
        val current = AtomicReference(context.getString(R.string.common_working))
        val stopped = AtomicBoolean(false)
        val watchdog = watchdog(context, started, lastEvent, current, stopped, onStall)
        watchdog.start()
        val outcomes = mutableListOf<Outcome>()
        try {
            found.forEachIndexed { index, item ->
                val number = index + 1
                val total = found.size
                val label = label(context, item)
                val stepStarted = System.currentTimeMillis()
                FirstInstallLog.step(context, number, total, label, item.source)
                onProgress(number, total, "Preparing…", -1)
                lastEvent.set(System.currentTimeMillis())
                val seen = AtomicReference("Preparing…")
                val listener = LinuxRuntimeInstaller.ProgressListener { stage, percent ->
                    lastEvent.set(System.currentTimeMillis())
                    current.set(stage)
                    if (seen.getAndSet(stage) != stage) FirstInstallLog.stage(context, stage)
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
                        // localOnly: the client is installed from the folder as it stands, with
                        // nothing fetched - the script reads the folder's own manifest and refuses
                        // to download. This path exists for a device that cannot reach Valve, and
                        // an install that waited on the CDN first would look like a hang.
                        Part.CLIENT -> SteamClient.installFromDir(context, item.source, localOnly = true) { stage, percent ->
                            onProgress(number, total, stage, percent)
                        }
                        // The desktop unpacks over the rootfs like the runtime does, so it goes through
                        // the same extractor. 0.4.0 pins the desktop to one package - KDE, tagged
                        // DESKTOP_KDE_TAG - and desktopInstalled() only counts a marker that carries
                        // exactly that tag, so a hand-carried archive can no longer go in unvouched:
                        // the pinned entry verifies the file's sha256 and the marker is written as the
                        // tag, which is what makes the step count as done. The LXQt desktop upstream
                        // replaced fails that checksum and is skipped with a message instead of being
                        // unpacked into a session that now starts KWin and cannot use it.
                        Part.DESKTOP -> DesktopCatalog.installTarFromFile(
                            context, DesktopCatalog.DESKTOP_ID, DesktopCatalog.desktopEntry(context),
                            item.source, listener)
                        // The desktop's Chinese translations, as their own step right after the desktop
                        // they belong to: upstream's package ships English only. Plain files over the
                        // rootfs through the same extractor, their own marker written the moment the
                        // unpack goes through - that mark, not the locale tree, is what says the patch
                        // is in (see [l10nInstalled]) - and the desktop put onto Traditional Chinese in
                        // the same breath, since a catalogue Plasma is never told to read is no
                        // translation at all. A failed unpack only means Plasma stays in English, so it
                        // is reported, not fatal.
                        Part.L10N ->
                            if (LinuxRuntimeInstaller.extract(
                                    context, item.source, LinuxRuntime.rootDir(context), listener)
                            ) {
                                FileUtils.writeString(
                                    File(LinuxRuntime.rootDir(context), DESKTOP_L10N_MARKER), "1")
                                // And the desktop itself onto Traditional Chinese, here and only here.
                                // KDE's settings page writes the same file, but only from inside a
                                // desktop already up - and one that comes up in English is not one the
                                // user can read their way round. Doing it at the moment the pack
                                // arrives is also what keeps the user's own choice afterwards: nothing
                                // else writes that file, so a language picked in KDE later stands (see
                                // DesktopLanguage).
                                DesktopLanguage.applyTraditionalChinese(context)
                                null
                            } else "the desktop's translations could not be unpacked"
                        Part.FONTS -> Fonts.install(context, item.source) { stage, percent ->
                            onProgress(number, total, stage, percent)
                        }
                        // syncPacks = false: no esync pack is looked up after a build goes in.
                        // That lookup is a request to GitHub, and every device does it by default
                        // (SessionPrefs.syncFallback defaults to true) - which is the one thing
                        // this path, meant for a machine that cannot reach GitHub at all, must not
                        // do. The packs the release shipped with are already in the runtime by
                        // then (SessionFiles.stage has staged them), and a session started later
                        // fills in the rest by itself.
                        Part.EXTRAS -> {
                            val tool = item.tool
                            val answer = if (tool == null) null
                            else ProtonExtras.installFromFile(context, tool, item.source, null, syncPacks = false) { stage, percent ->
                                onProgress(number, total, stage, percent)
                            }
                            extrasProblem(tool, answer)
                        }
                        Part.GPU -> installDrivers(context, item.source) { stage, percent ->
                            onProgress(number, total, stage, percent)
                        }
                    }
                // Throwable, not Exception: an Error from under an extractor or a native library used
                // to leave this loop without an outcome, which took the rest of the run with it and
                // left the screen on the last step for ever, saying nothing. Now it is one step's
                // problem, logged with its trace, and the steps after it still get their turn.
                } catch (t: Throwable) {
                    Log.e(TAG, "install ${item.part} from ${item.source}", t)
                    FirstInstallLog.failed(context, label, t)
                    t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                }
                outcomes += Outcome(item, problem)
                FirstInstallLog.stepDone(context, number, total, label, System.currentTimeMillis() - stepStarted, problem)
                lastEvent.set(System.currentTimeMillis())
                // Everything after the runtime needs it, so one that did not go in ends the run here:
                // the three that follow would only repeat "install the Linux runtime first".
                if (item.part == Part.RUNTIME && problem != null) return outcomes
            }
            return outcomes
        } finally {
            // The run is over however it ended, so the heartbeat stops with it - a watchdog left
            // writing would put lines into the next run's log.
            stopped.set(true)
            watchdog.interrupt()
        }
    }

    /** The message an EXTRAS step that has no tool of its own reports; see [extrasProblem]. */
    internal const val NO_COMPATIBILITY_TOOL = "no compatibility tool matches that archive"

    /**
     * What an EXTRAS step ended as, from the two nulls that reach it.
     *
     * [tool] is the step's own, which [found] only ever makes for a tool it matched - so it is
     * null only for a step that was never made. [installed] is the installer's answer, which is
     * *null when the build went in*. The two mean opposite things, and folding them into one "?:"
     * read the success as the first: every third-party Proton that had just been installed was
     * reported as "no compatibility tool matches that archive" beside a step that had nothing
     * wrong with it. Written out so the two are told apart, and so a test can hold them apart.
     */
    internal fun extrasProblem(tool: ProtonExtras.Tool?, installed: String?): String? =
        if (tool == null) NO_COMPATIBILITY_TOOL else installed

    /**
     * Whether a finished run has anything worth putting on screen.
     *
     * A run where every step went in has nothing to say. A report listing ten "installed" lines is
     * the one thing a user who just watched the whole run cannot use - and it buries the one line
     * that would matter on a run that half worked. So a report is for a step that failed, an error
     * raised outside any step, or a step the watchdog found silent; anything else is silent too.
     */
    internal fun worthReporting(outcomes: List<Outcome>, hard: String?, stalled: Boolean): Boolean =
        hard != null || stalled || outcomes.any { it.problem != null }

    /**
     * The 1-based numbers of the steps that failed, in the order they ran. Empty on a run that went
     * in, which is the same shape [worthReporting] reads as "nothing to show" when nothing else
     * happened either.
     */
    internal fun failedSteps(outcomes: List<Outcome>): List<Int> =
        outcomes.mapIndexedNotNull { index, outcome -> if (outcome.problem == null) null else index + 1 }

    /** How long a step may make no progress at all before the screen says the run looks stuck. */
    private const val STALL_MS = 5L * 60 * 1000

    /**
     * Writes a heartbeat every quarter minute and tells [onStall] once when a step has been quiet
     * for [STALL_MS]. Never stops the work: an unpack of a 3 GB rootfs is quiet for minutes at a
     * time on a slow card, and cutting that off would turn a slow install into a failed one. A
     * child of ours that is really wedged is the other watchdog's business ([runtime.GuestOutput]).
     */
    private fun watchdog(
        context: Context,
        started: Long,
        lastEvent: AtomicLong,
        current: AtomicReference<String>,
        stopped: AtomicBoolean,
        onStall: ((String, Int) -> Unit)?,
    ): Thread = Thread({
        var told = false
        while (!stopped.get()) {
            try {
                Thread.sleep(15_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopped.get()) return@Thread
            val now = System.currentTimeMillis()
            val quiet = now - lastEvent.get()
            FirstInstallLog.heartbeat(context, current.get(), now - started, quiet)
            if (!told && quiet >= STALL_MS) {
                told = true
                val minutes = (quiet / 60_000).toInt()
                FirstInstallLog.note(context, "no progress for $minutes min; still at \"${current.get()}\"")
                onStall?.invoke(current.get(), minutes)
            }
        }
    }, "first-install-watchdog")

    /** What a part is called in the log and in the report: the same names the screen uses. */
    private fun label(context: Context, found: Found): String = when (found.part) {
        Part.RUNTIME -> context.getString(R.string.setup_runtime)
        Part.PROTON -> context.getString(R.string.mode_proton_arm64)
        Part.CLIENT -> context.getString(R.string.mode_steam_client)
        Part.DESKTOP -> context.getString(R.string.content_linux_desktop)
        Part.L10N -> context.getString(R.string.first_local_l10n)
        Part.FONTS -> context.getString(R.string.first_local_fonts)
        Part.EXTRAS -> found.tool?.name ?: found.source.name
        Part.GPU -> context.getString(R.string.mode_gpu_drivers)
    }

    /**
     * Every driver zip in [folder], each through the importer its own kind belongs to - the same
     * three the GPU drivers tab's "Import .zip" picks between. Returns what stopped it: null when
     * they all went in, else the ones that did not, by name.
     *
     * A zip is installed only when the device has not got it, which is what keeps a second copy of a
     * driver from being unpacked beside the first - the importers take a fresh directory each time,
     * so re-importing one that is already there would leave the device with two. A bundled display
     * driver is skipped outright: it comes from the apk and the app unpacks it itself. Nothing is
     * set as the driver in use: this puts the files on the device, and which one to run is the
     * drivers page's own choice.
     *
     * One zip that cannot be installed does not stop the rest, since they are independent of each
     * other - but the folder as a whole counts as failed, which is what keeps the step, and the
     * button, in front of the user until all of them are in.
     */
    private fun installDrivers(context: Context, folder: File, onProgress: (String, Int) -> Unit): String? {
        val zips = driverZips(folder)
        val failed = ArrayList<String>()
        zips.forEachIndexed { index, zip ->
            val entry = DriverZip.read(zip) ?: return@forEachIndexed
            if (DriverZip.installed(context, entry) || DriverZip.isBundled(entry)) return@forEachIndexed
            onProgress(context.getString(R.string.mode_gpu_drivers) + "  ${index + 1}/${zips.size}", -1)
            try {
                val uri = Uri.fromFile(zip)
                when (entry.kind) {
                    DriverZip.Kind.BUNDLE -> DriverBundle.install(context, uri)
                    DriverZip.Kind.RUNTIME -> LinuxVulkanDriverManager(context).installDriver(uri, zip.name)
                    DriverZip.Kind.DISPLAY -> TurnipDriver(context).installFromZip(uri, zip.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "installing driver $zip", e)
                failed.add(zip.name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        if (failed.isEmpty()) return null
        return failed.size.toString() + " of " + zips.size + " drivers could not be installed: " +
            failed.joinToString("; ")
    }
}
