package com.droiddeck.launcher.session

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * The loading overlay's state: what to say, read out of the session log, and a clock so a user
 * can see that time is passing even when the log is quiet. The runtime script marks its
 * milestones with "== STEP"; the client's own bootstrap does not, but it does print its download
 * progress, and lifting that out is the difference between "starting the Steam client" for three
 * minutes and a percentage that moves.
 *
 * The wait the script cannot describe is the client's own first launch: it updates, unpacks and
 * verifies itself with nothing on stdout. It does keep a log of its own, though
 * ([clientLog]), and since both are on the same filesystem the app can read it live - that is
 * what turns those minutes into "installing the Steam client update" instead of silence.
 */
class LoadingState(context: Context, private val steam: Boolean = true) {
    var visible by mutableStateOf(true)
    var step by mutableStateOf(context.getString(com.droiddeck.launcher.R.string.loadstate_starting))
        private set
    /** What [step] is about, so the screen picks its checklist stage without reading the (translated) words. */
    var topic by mutableStateOf(Topic.SESSION)
        private set
    /** [step] is a download or install line worth showing as it is, not one of the script's own notes. */
    var readable by mutableStateOf(false)
        private set
    var percent by mutableIntStateOf(-1)

    /**
     * Seconds the current checklist stage has been running. A stage that drags on says more about
     * itself (see the loading screen's last stage): the alternative is a screen that looks frozen
     * only because the one thing it can say has been said.
     */
    var stageSeconds by mutableIntStateOf(0)
        private set
    var elapsed by mutableStateOf("")
    var hint by mutableStateOf("")
    var ended by mutableStateOf(false)
    /** The technical side of an ended session (exit status, log path), under the advice in [step]. */
    var endedDetail by mutableStateOf<String?>(null)

    // The desktop and the emulators get their own: the Steam ones talk about Steam.
    private val hints = context.resources.getStringArray(
        if (steam) com.droiddeck.launcher.R.array.loading_hints else com.droiddeck.launcher.R.array.loading_hints_desktop,
    )
    private val res = context.resources
    private val startedAt = SystemClock.elapsedRealtime()

    /** Wall clock at construction: the client's log counts as this session's if written since. */
    private val startedWallClock = System.currentTimeMillis()

    /**
     * The last thing the client's own bootstrap said. Held rather than recomputed so a phase that
     * writes one line and then works quietly for minutes keeps naming that work instead of the
     * screen falling back to a sentence that says only that time is passing.
     */
    private var lastClientLine: Line? = null

    /**
     * The last milestone read, kept across reads.
     *
     * The log is read from its tail alone, and the client's own launch writes megabytes of its own
     * after the script's last "== STEP" - CEF, ANGLE, the compositor, proot. A read that only
     * looked at the end therefore found no milestone at all once that had happened, and handed
     * back nothing, which is exactly how the screen came to hold one sentence for the whole wait
     * while the client was downloading (and why leaving the page and coming back showed the
     * percentage: the fresh session's log was short enough to still hold the milestone).
     *
     * The offsets are absolute and only ever move forward, so a marker that has scrolled out of
     * the window still orders correctly against the ones that have not.
     */
    private var stepAt = -1L
    private var stepText: String? = null
    private var progressAt = -1L
    private var progressTenths = -1
    private var bootAt = -1L
    private var bootTenths = -1
    private var downloadAt = -1L
    private var download: String? = null
    private var downloadUnpack = false
    private var downloadTenths = -1

    /** The client has been started in this session, so its own log is the one still moving. */
    private var clientStarted = false

    /** The line last handed to the screen, so a tail that names nothing keeps it rather than clears it. */
    private var last: Line? = null

    /** The client's own directory: its log, and the files it prints its own lines from. */
    private val clientRoot: File? = if (steam) SteamClient.root(context) else null

    /**
     * The Steam client's own bootstrapper log, inside the runtime. It is the only place the
     * client's first launch reports itself - updating, unpacking, verifying - and the script's
     * own log goes quiet for exactly that stretch. Null outside a Steam session.
     */
    private val clientLog: File? = clientRoot?.let { File(it, "logs/bootstrap_log.txt") }

    /**
     * The bootstrapper's milestones, in the words the client on this device prints them in.
     *
     * Its log is **not** English. Every line it means a user to read is a `SteamBootstrapper_*`
     * string, translated in the file the client ships them in - `public/steambootstrapper_<language>
     * .txt`, part of the `strings` component - and a client in Chinese therefore writes
     * `正在下载更新 (已下载 35,320，共 67,349 KB)...`. A list of English prefixes matches none of
     * it: the screen kept the last line it *did* recognise, and the only one it ever did was the
     * client's debug line `Downloaded new manifest` (never translated), which is why the wait sat
     * on "downloading the client update" with no number for as long as the download lasted.
     *
     * The numbers are not plain ASCII either - the client writes 218353 as `218,353`, and the unit
     * is not always KB (`Ko` in French, `КБ` in Russian) - so the number is lifted out of the shape
     * of the line rather than matched with a pattern written for one language.
     *
     * Read once, from every language file the client has: which one it runs in is not something the
     * app can know, and thirty small files cost nothing next to a wrong answer. A client that has
     * not unpacked them yet falls back to the English the bootstrapper prints with no file at all.
     */
    private val phrases: List<Phrase> by lazy { bootstrapperPhrases() }

    /** Only the lines that can possibly match are tried, keyed by the first letter of each. */
    private val phrasesByFirst: Map<Char, List<Phrase>> by lazy {
        phrases.groupBy { it.pieces[0][0].lowercaseChar() }
    }

    // When the current stage began, so the screen can tell a new stage from a long one.
    private var stageStartedAt = SystemClock.elapsedRealtime()
    private var stageTopic = Topic.SESSION

    /** Once a second: the clock and the hint. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val seconds = (now - startedAt) / 1000
        elapsed = res.getString(com.droiddeck.launcher.R.string.loadstate_elapsed, seconds / 60, seconds % 60)
        stageSeconds = ((now - stageStartedAt) / 1000).toInt()
        hint = hints[((seconds / 8) % hints.size).toInt()]
    }

    /** A line the app itself writes (an install's progress, the session starting). */
    fun say(line: String, topic: Topic, readable: Boolean) {
        step = line
        this.topic = topic
        this.readable = readable
        restage(topic)
    }

    /** The stage clock restarts with the stage; the screen's last stage reads it to say more. */
    private fun restage(next: Topic) {
        if (next == stageTopic) return
        stageTopic = next
        stageStartedAt = SystemClock.elapsedRealtime()
        stageSeconds = 0
    }

    fun showEnded(message: String, detail: String? = null) {
        visible = true
        ended = true
        step = message
        endedDetail = detail
    }

    /** Re-reads the end of the log and updates the line and the bar. */
    fun update(context: Context, log: File?) {
        val state = read(context, log) ?: return
        step = state.line
        topic = state.topic
        readable = state.readable
        percent = state.percent
        restage(state.topic)
    }

    private class Line(val line: String, val percent: Int, val topic: Topic, val readable: Boolean)

    /** What one of the bootstrapper's lines is about, in the terms the screen has words for. */
    private enum class Kind { FAILED, CHECK, VERIFY, INSTALL, DOWNLOAD }

    /**
     * One line the bootstrapper can print: what it is about, and the literal pieces its translation
     * file puts its numbers between.
     */
    private class Phrase(val kind: Kind, val pieces: List<String>) {
        /** How many numbers the line carries: the pieces are the text between them. */
        val values: Int get() = pieces.size - 1

        /**
         * The line's numbers when [message] is that line, or null when it is not.
         *
         * The pieces are looked for in order, and the text between two of them is read as a number
         * when that is what it is: `218,353 of 247,576 KB`, `35,320，共 67,349 KB` and `67 349 Ko`
         * all come out as two of them, none of the three needing a pattern of its own. A template
         * that fills in something else - `Corrupt package file (%pkgName%).` - matches the same way
         * and simply carries no number.
         */
        fun numbers(message: String): List<Long>? {
            var at = 0
            val found = ArrayList<Long>(maxOf(0, values))
            for (index in pieces.indices) {
                val piece = pieces[index]
                val hit = message.indexOf(piece, at, ignoreCase = true)
                if (hit < 0) return null
                if (index > 0) number(message.substring(at, hit))?.let { found += it }
                at = hit + piece.length
            }
            return found
        }

        /** [text] as a number, or null when it is anything but one and its separators. */
        private fun number(text: String): Long? {
            val digits = text.filter { it in '0'..'9' }
            if (digits.isEmpty()) return null
            if (text.any { it !in '0'..'9' && it !in SEPARATORS }) return null
            return digits.toLongOrNull()
        }

        private companion object {
            /** What a number may have in it when the client's language writes one. */
            const val SEPARATORS = " ,.'’\u00A0\u202F"
        }
    }

    /** Only the tail is read: the client alone writes megabytes an hour. */
    private fun read(context: Context, log: File?): Line? {
        if (log == null || !log.isFile) return last
        val (start, text) = tail(log) ?: return last
        var offset = start
        for (line in text.split('\n')) {
            val at = offset
            offset += line.length + 1
            if (line.startsWith("== STEP ")) {
                // Only forward: a milestone already seen is never replaced by an older one just
                // because the window moved and now starts in the middle of the session.
                if (at >= stepAt) {
                    stepAt = at
                    stepText = line.substringAfter("== STEP ").substringAfter(' ')
                    val m = INSTALL_COUNT.find(line)
                    if (m != null) {
                        val size = m.groupValues[5]
                        downloadAt = at
                        downloadUnpack = m.groupValues[1] == "unpacking"
                        download = if (size.isEmpty()) m.groupValues[2] else m.groupValues[2] + " · " + size
                        downloadTenths = m.groupValues[3].toInt() * 1000 / maxOf(1, m.groupValues[4].toInt())
                    }
                }
            } else {
                val p = PROGRESS.find(line)
                if (p != null && at >= progressAt) {
                    progressAt = at
                    progressTenths = p.groupValues[1].toInt() * 10 + p.groupValues[2].toInt()
                }
                val numbers = BOOTSTRAPPER_PROGRESS?.numbers(line)
                if (numbers != null && numbers.size >= 2 && numbers[1] > 0 && at >= bootAt) {
                    bootAt = at
                    bootTenths = (numbers[0].coerceAtLeast(0) * 1000 / numbers[1]).toInt().coerceIn(0, 1000)
                }
            }
        }
        val scriptStage = stepText?.let { topicOf(it, steam) }
        val script = stepText
        if (scriptStage == Topic.STEAM_STARTING) clientStarted = true
        // The client's own log, once it is the process that has run: held on to rather than
        // re-derived from the tail, because the script writes more lines under a running client
        // ("signed in: asking the client again for ...") and those would otherwise take the screen
        // off the client's own progress for the rest of the launch.
        val client = if (clientStarted) clientLine(context) else null
        val found = when {
            // Last thing written wins: the install script's own byte count while it is fetching,
            // the bootstrapper's while it is. Both are shown with a decimal, because on a
            // 344 MB install a whole percent is three and a half megabytes of nothing moving.
            progressAt >= 0 && progressAt > stepAt && progressAt >= bootAt -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, tenths(progressTenths)),
                progressTenths / 10, Topic.STEAM, readable = true,
            )
            bootAt > stepAt -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, tenths(bootTenths)),
                bootTenths / 10, Topic.STEAM, readable = true,
            )
            downloadAt == stepAt && download != null -> Line(
                context.getString(
                    if (downloadUnpack) com.droiddeck.launcher.R.string.loadstate_steam_unpack
                    else com.droiddeck.launcher.R.string.loadstate_steam_client,
                    // The component counter only moves when a component lands and one is 105 MB, so
                    // the number goes in front of the name: this is the phase with no percentage of
                    // its own, and the wait is what the user is asking about.
                    if (downloadTenths >= 0) tenths(downloadTenths) + " (" + download + ")" else download,
                ),
                if (downloadTenths >= 0) downloadTenths / 10 else -1, Topic.STEAM, readable = true,
            )
            client != null -> client
            // Past "starting the Steam client" the script has nothing left to say.
            scriptStage == Topic.STEAM_STARTING -> Line(script.orEmpty(), -1, Topic.STEAM_STARTING, readableScriptStep(script.orEmpty()))
            script != null && scriptStage != null -> Line(script, -1, scriptStage, readableScriptStep(script))
            // Nothing in the window at all: keep what is on screen instead of blanking it.
            else -> last
        }
        last = found
        return found
    }

    /** The tail of [log], and the offset that tail starts at. */
    private fun tail(log: File): Pair<Long, String>? = try {
        RandomAccessFile(log, "r").use { file ->
            val length = file.length()
            val want = minOf(length, TAIL_BYTES)
            val start = length - want
            file.seek(start)
            val bytes = ByteArray(want.toInt())
            file.readFully(bytes)
            start to String(bytes, StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * What the client's own bootstrapper last said, or null when it is not the one writing: its
     * log outlives the session, so only a file written since this one began counts, and the answer
     * is held afterwards because the phases it names run for minutes on one line. The lines are
     * read in whatever language the client is in - see [phrases].
     */
    private fun clientLine(context: Context): Line? {
        val log = clientLog ?: return null
        // Ours if it has been written since this session began, however long it has been quiet
        // since: hashing the client's own files says one line and then nothing for minutes, and a
        // window that expires turns those minutes back into the screen saying nothing at all.
        val fresh = try {
            log.isFile && log.lastModified() >= startedWallClock - 2_000L
        } catch (e: Exception) {
            false
        }
        if (!fresh) return lastClientLine
        val (_, text) = tail(log) ?: return lastClientLine
        var found: Line? = null
        for (raw in text.split('\n')) {
            val next = bootstrapperLine(context, clientMessage(raw.trim()))
            if (next != null) found = next
        }
        if (found != null) lastClientLine = found
        return found ?: lastClientLine
    }

    /**
     * What one of the bootstrapper's lines says on screen, or null when it is one of its notes
     * rather than one of its milestones.
     */
    private fun bootstrapperLine(context: Context, message: String): Line? {
        val batch = message.firstOrNull()?.let { phrasesByFirst[it.lowercaseChar()] } ?: return null
        val hit = batch.firstNotNullOfOrNull { phrase -> phrase.numbers(message)?.let { phrase to it } } ?: return null
        val (phrase, numbers) = hit
        return when (phrase.kind) {
            Kind.FAILED -> spoken(context, com.droiddeck.launcher.R.string.load_client_failed)
            // Before the update is even looked for - the first thing a client that has to fetch
            // anything says, and long enough on its own to look stuck.
            Kind.CHECK -> spoken(context, com.droiddeck.launcher.R.string.load_client_check)
            Kind.VERIFY -> spoken(context, com.droiddeck.launcher.R.string.load_client_verify)
            Kind.INSTALL -> spoken(context, com.droiddeck.launcher.R.string.load_client_install)
            // The client is the only thing that knows how far its own download has got, and it says
            // so with a decimal because on 344 MB a whole percent is three and a half megabytes of
            // nothing moving. A line without a number is the phase, not the progress.
            Kind.DOWNLOAD -> if (numbers.size >= 2 && numbers[1] > 0) {
                val value = (numbers[0].coerceAtLeast(0) * 1000 / numbers[1]).toInt().coerceIn(0, 1000)
                Line(
                    context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, tenths(value)),
                    value / 10, Topic.STEAM_STARTING, readable = true,
                )
            } else {
                spoken(context, com.droiddeck.launcher.R.string.load_client_download)
            }
        }
    }

    /**
     * The bootstrapper's lines as the client itself spells them, read out of the translation files
     * it ships ([clientRoot] `public/steambootstrapper_*.txt`), or the English in its own binary
     * when there are none to read.
     */
    private fun bootstrapperPhrases(): List<Phrase> {
        val root = clientRoot ?: return ENGLISH_PHRASES
        val files = try {
            File(root, "public").listFiles { file ->
                file.isFile && file.name.startsWith("steambootstrapper_") && file.name.endsWith(".txt")
            }
        } catch (e: Exception) {
            null
        }
        if (files.isNullOrEmpty()) return ENGLISH_PHRASES
        val read = ArrayList<Phrase>()
        for (file in files) {
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            for ((key, kind) in BOOTSTRAPPER_KEYS) {
                val value = token(text, key) ?: continue
                phrase(kind, value)?.let { read += it }
            }
        }
        if (read.isEmpty()) return ENGLISH_PHRASES
        return priority(read)
    }

    /**
     * The value of [key] in one of those files, or null when it has not got it.
     *
     * Both spellings in use are read: the translated files quote the key
     * (`"SteamBootstrapper_UpdateInstalling"  "正在安装更新..."`) and the English one does not
     * (`SteamBootstrapper_UpdatingSteam   "Updating %appname%..."`), so the quote after the key is
     * taken when it is there - the one that closes the key, not the one that opens the value.
     */
    private fun token(text: String, key: String): String? {
        val pattern = "(?<![A-Za-z0-9_])" + Regex.escape(key) + "\"?(?![A-Za-z0-9_])\\s*\"([^\"]*)\""
        return Regex(pattern).find(text)?.groupValues?.get(1)
    }

    private fun spoken(context: Context, @androidx.annotation.StringRes text: Int) =
        Line(context.getString(text), -1, Topic.STEAM_STARTING, readable = true)

    /** Tenths of a percent the way the screen says them: 423 is "42.3%". */
    private fun tenths(value: Int) = "${value / 10}.${value % 10}%"

    /**
     * A bootstrapper line without its clock: every line is prefixed `[2026-10-09 21:14:02] `, so
     * the message itself starts after it.
     */
    private fun clientMessage(line: String): String {
        val close = line.indexOf("] ")
        return if (line.startsWith("[") && close in 1..32) line.substring(close + 2) else line
    }

    /** The checklist stages a loading line can move the screen to. */
    enum class Topic { RUNTIME, SESSION, DESKTOP, STEAM, STEAM_STARTING, OTHER }

    companion object {
        /**
         * A session script "== STEP" line's topic in a Steam ([steam]) or desktop session; those
         * lines are English whatever the app's language.
         */
        internal fun topicOf(scriptStep: String, steam: Boolean): Topic {
            val t = scriptStep.lowercase()
            return when {
                "linux runtime" in t -> Topic.RUNTIME
                "starting the session" in t -> Topic.SESSION
                !steam && "desktop" in t -> Topic.DESKTOP
                steam && "starting the steam client" in t -> Topic.STEAM_STARTING
                steam && ("steam" in t || "client" in t || "proton" in t || "library" in t || "compatibility" in t) -> Topic.STEAM
                else -> Topic.OTHER
            }
        }

        /** The script's install lines are worth reading as they are; its other notes are not. */
        internal fun readableScriptStep(scriptStep: String): Boolean {
            val t = scriptStep.lowercase()
            return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
        }

        /**
         * How much of the log's end is read. Big enough that the session's own milestones are in
         * the window for a while after the client has started writing its own output over them
         * (see the offsets held in [LoadingState]), and still small enough to read twice a second.
         */
        private const val TAIL_BYTES = 128L * 1024

        /** Where a translation file puts the values it fills in. */
        private val PLACEHOLDER = Regex("""%[A-Za-z_]+%""")

        /**
         * The bootstrapper strings the screen has something to say about, and what each says. The
         * keys are the client's own; the words are whatever its translation file puts in them, which
         * is the point - see [LoadingState.phrases].
         */
        private val BOOTSTRAPPER_KEYS = listOf(
            "SteamBootstrapper_FailedDownloadHTTPError" to Kind.FAILED,
            "SteamBootstrapper_NoNetwork" to Kind.FAILED,
            "SteamBootstrapper_CorruptPackageFile" to Kind.FAILED,
            "SteamBootstrapper_FailedManifestLoad" to Kind.FAILED,
            "SteamBootstrapper_FailedPackageRead" to Kind.FAILED,
            "SteamBootstrapper_FailedPackageUnzip" to Kind.FAILED,
            "SteamBootstrapper_UpdateApplyFail" to Kind.FAILED,
            "SteamBootstrapper_UpdateChecking" to Kind.CHECK,
            "SteamBootstrapper_InstallVerify" to Kind.VERIFY,
            "SteamBootstrapper_UpdatePrepare" to Kind.INSTALL,
            "SteamBootstrapper_UpdateExtractingPackage" to Kind.INSTALL,
            "SteamBootstrapper_UpdateInstalling" to Kind.INSTALL,
            "SteamBootstrapper_UpdateCleanup" to Kind.INSTALL,
            "SteamBootstrapper_DownloadComplete" to Kind.INSTALL,
            "SteamBootstrapper_UpdateComplete" to Kind.INSTALL,
            "SteamBootstrapper_UpdateDownload" to Kind.DOWNLOAD,
            "SteamBootstrapper_UpdateDownloading" to Kind.DOWNLOAD,
        )

        /**
         * What a bootstrapper line is read as when there is no translation file to read: the English
         * in its own binary, plus the debug lines it never translates at all (`Downloaded new
         * manifest`, `Manifest download: finished`).
         */
        private val ENGLISH_PHRASES: List<Phrase> = priority(
            listOf(
                Kind.FAILED to "Download failed",
                Kind.FAILED to "Steam needs to be online",
                Kind.FAILED to "Failed to apply update",
                Kind.FAILED to "Failed to load manifest",
                Kind.FAILED to "Corrupt package file",
                Kind.CHECK to "Checking for update",
                Kind.CHECK to "Checking for available update",
                Kind.CHECK to "Found pending update",
                Kind.VERIFY to "Verifying",
                Kind.INSTALL to "Preparing to update",
                Kind.INSTALL to "Installing update",
                Kind.INSTALL to "Extracting package",
                Kind.INSTALL to "Creating folder",
                Kind.INSTALL to "Applying update",
                Kind.INSTALL to "Update complete",
                Kind.INSTALL to "Download complete",
                Kind.INSTALL to "Cleaning up",
                Kind.INSTALL to "Saving package",
                Kind.INSTALL to "Deleting file",
                Kind.DOWNLOAD to "Downloading update (%bytes% of %size% KB)...",
                Kind.DOWNLOAD to "Downloading update",
                Kind.DOWNLOAD to "Downloading Update",
                Kind.DOWNLOAD to "Downloading manifest",
                Kind.DOWNLOAD to "Manifest download",
                Kind.DOWNLOAD to "Downloaded new manifest",
            ).mapNotNull { (kind, text) -> phrase(kind, text) },
        )

        /**
         * The bootstrapper's own progress line as it can turn up in the session log too, for the
         * runs where its output is being captured there rather than going to its own log.
         */
        private val BOOTSTRAPPER_PROGRESS: Phrase? =
            phrase(Kind.DOWNLOAD, "Downloading update (%bytes% of %size% KB)...")

        /**
         * Which line wins when more than one could be read out of the same words: a failure before
         * anything else, then the line that carries the numbers ("Downloading update (1 of 2 KB)"
         * also reads as "Downloading update"), then the longer phrase.
         */
        private fun priority(phrases: List<Phrase>): List<Phrase> = phrases.sortedWith(
            compareByDescending<Phrase> { it.kind == Kind.FAILED }
                .thenByDescending { it.values }
                .thenByDescending { it.pieces[0].length },
        )

        /**
         * [template] as a phrase, or null when its shape is one this cannot read: a placeholder at
         * an end (`Updating %appname%...`, `%appname% needs to be online...`) is the text on the
         * other side of it and carries no number of its own, and one with nothing between two
         * placeholders would leave a number with no text to find it by.
         */
        private fun phrase(kind: Kind, template: String): Phrase? {
            val parts = template.split(PLACEHOLDER)
            val pieces = parts.mapIndexed { index, part ->
                val text = part.trim()
                // The file's own punctuation is the file's own: the line in the log may end without it.
                if (index == parts.lastIndex) text.trimEnd('.', '…', '。', ' ') else text
            }
            val kept = pieces.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
            if (kept.isEmpty() || kept.any { it.isEmpty() }) return null
            return Phrase(kind, kept)
        }

        /**
         * The install script's own progress, a line it writes while a component is coming down so
         * that a 105 MB piece moves the number instead of only changing it when it lands.
         */
        private val PROGRESS = Regex("""== PROGRESS (\d+)\.(\d)""")

        /**
         * One component at a time, as droiddeck-steam-install announces each: fetched, or taken
         * from the local folder. The size is there on the downloads so a component that takes
         * minutes says why - 105 MB explains a wait that a file name does not.
         */
        private val INSTALL_COUNT = Regex(
            """(downloading|unpacking) Steam(?: from the local copy)?: (\S+) \((\d+)/(\d+)(?:, ([\d.]+ [KMG]B))?\)""",
        )
    }
}
