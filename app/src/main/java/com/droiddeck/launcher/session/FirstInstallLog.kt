package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a one-click local install leaves on disk: every step it took, every failure with its stack
 * trace, and a heartbeat while a step says nothing else - because a device that hangs, or dies
 * halfway, otherwise has nothing to hand over.
 *
 * The install is the one operation in the app nobody can retry from a screen. A runtime unpack is
 * minutes of a 790 MB archive, and the steps after it put proot on the device; when one of those
 * stops, the app's own report was a Toast that went away in seconds and the reasons lived on
 * Logcat, which needs a cable. So the run is written here as it goes, and the report dialog that
 * ends it offers to write the file somewhere the user can reach.
 *
 * `files/logs/tools` on purpose: the session log share already picks up the recent files of that
 * folder, so an install log travels with the logs a user is used to sending.
 *
 * Nothing in here may throw - a log that breaks the install it documents is worse than no log -
 * and every entry goes to Logcat as well, for a device in hand.
 */
object FirstInstallLog {
    private const val TAG = "FirstInstallLog"
    private const val NAME = "first-install.log"

    /** The file is kept to this; past it the head is dropped, so the newest run always fits. */
    internal const val MAX_BYTES = 512L * 1024

    private val lock = Any()
    private val at = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** The file a run writes into. Always the same one, appended; the caller shows and copies it. */
    fun file(context: Context): File = fileIn(LinuxRuntime.logDir(context))

    /** The same file under a given logs folder - the session logs' own, or a test's. */
    internal fun fileIn(logsRoot: File): File =
        File(File(logsRoot, SessionPaths.TOOLS_DIR).apply { mkdirs() }, NAME)

    /**
     * Starts a run: one line to separate it from the last, then what the device is and what the
     * folder offered. Written before the first byte is unpacked, so a run that never gets further
     * still says what it was about to do.
     */
    fun begin(context: Context, folder: File, held: List<String>, missing: List<String>, need: Long, free: Long) {
        val lines = mutableListOf<String>()
        lines += ""
        lines += "=".repeat(72)
        lines += "one-click local install, ${Date()}"
        lines += "=".repeat(72)
        lines += deviceLines(context)
        lines += "folder      = ${folder.absolutePath}"
        lines += "in the folder = " + (held.ifEmpty { listOf("(nothing this app can install)") }.joinToString(", "))
        lines += "to install    = " + (missing.ifEmpty { listOf("(all of it is in already)") }.joinToString(", "))
        lines += "space: needs ${FileUtils.sizeToString(need)}, free ${FileUtils.sizeToString(free)}"
        if (free in 1 until need) lines += "WARNING: less free space than the install needs"
        write(context, lines.joinToString("\n"))
    }

    /** One step of a run, its number and what it reads from. */
    fun step(context: Context, number: Int, total: Int, label: String, source: File) {
        write(context, "---- step $number/$total  $label  <-  ${source.name} (${FileUtils.sizeToString(source.length())})")
    }

    /** The stage a step reports, written only when it changes: the extractor repeats its own. */
    fun stage(context: Context, text: String) {
        write(context, "     stage: $text")
    }

    /** How a step ended. [ms] is the whole step, [problem] null when it went in. */
    fun stepDone(context: Context, number: Int, total: Int, label: String, ms: Long, problem: String?) {
        val took = String.format(Locale.US, "%.1f s", ms / 1000.0)
        write(context, if (problem == null) "---- step $number/$total done in $took: ok  ($label)"
            else "---- step $number/$total FAILED in $took: $problem  ($label)")
    }

    /** A throwable that got past a step: the message the user sees, and the trace we need. */
    fun failed(context: Context, label: String, t: Throwable) {
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
        write(context, "!!!! $label threw ${t.javaClass.name}: ${t.message}\n$trace")
    }

    /** Anything else worth a line: a stall, a leftover, a decision taken instead of a failure. */
    fun note(context: Context, text: String) {
        write(context, "     note: $text")
    }

    /**
     * Proof of life while a step is in something we cannot instrument - a guest command with no
     * output, an unpack between its own progress reports. A gap in these is what a hang looks like
     * from the outside, so they are written whether or not anything changed.
     */
    fun heartbeat(context: Context, what: String, inRunMs: Long, quietMs: Long) {
        val run = String.format(Locale.US, "%.1f min", inRunMs / 60_000.0)
        val quiet = String.format(Locale.US, "%.1f min", quietMs / 60_000.0)
        write(context, "     · $run in, $quiet quiet, at \"$what\"")
    }

    /** Ends a run with what each step ended as. */
    internal fun end(context: Context, outcomes: List<FirstLocalInstall.Outcome>, ms: Long, hard: String?) {
        val lines = mutableListOf<String>()
        val took = String.format(Locale.US, "%.1f min", ms / 60_000.0)
        val bad = outcomes.count { it.problem != null }
        lines += "run ended after $took: ${outcomes.size - bad} of ${outcomes.size} steps in" +
            (if (bad == 0) "" else ", $bad failed")
        for (outcome in outcomes) {
            lines += "  " + if (outcome.problem == null) "ok   ${outcome.found.source.name}"
            else "FAIL ${outcome.found.source.name}: ${outcome.problem}"
        }
        // A run that died outside a step left no outcome at all, so it is the only thing said here.
        hard?.let { lines += "the run itself did not finish: $it" }
        lines += "log: ${file(context).absolutePath}"
        write(context, lines.joinToString("\n"))
    }

    /**
     * Everything an install wrote, copied into [folder] so it can be carried off the device: this
     * run's log and the guest commands' own files beside it. Returns what was written.
     */
    fun save(context: Context, folder: File): List<File> = saveFrom(LinuxRuntime.logDir(context), folder)

    /** The copy itself, over a logs folder - the session logs' own, or a test's. */
    internal fun saveFrom(logsRoot: File, folder: File): List<File> {
        val out = File(folder, "DroidDeck-first-install").apply { mkdirs() }
        val written = mutableListOf<File>()
        val sources = (File(logsRoot, SessionPaths.TOOLS_DIR).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".log") }
        for (source in sources) {
            runCatching {
                val target = File(out, source.name)
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                written += target
            }.onFailure { Log.w(TAG, "could not copy ${source.name}", it) }
        }
        return written
    }

    /** The last [lines] lines of the log, for a screen that wants to show why before it saves. */
    fun tail(context: Context, lines: Int): String = tailOf(file(context), lines)

    internal fun tailOf(file: File, lines: Int): String =
        runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    /** What the device is: the first thing a report from someone else needs to be read. */
    private fun deviceLines(context: Context): List<String> {
        val lines = mutableListOf<String>()
        fun line(name: String, value: () -> String) {
            lines += name.padEnd(12) + "= " + runCatching(value).getOrElse { "?" }
        }
        line("app") { "4.0 (${BuildConfig.VERSION_CODE}) COK ${BuildConfig.FORK_VERSION}, ${BuildConfig.BUILD_TYPE}" }
        line("android") { "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})" }
        line("device") { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / ${android.os.Build.DEVICE} ${android.os.Build.HARDWARE}" }
        line("soc") { soc() }
        line("abis") { android.os.Build.SUPPORTED_ABIS.joinToString(", ") }
        line("cores") { Runtime.getRuntime().availableProcessors().toString() }
        // The one number that decides whether a native binary may load at all: 4 KB pages or 16.
        line("page size") {
            val size = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            "$size B" + if (size > 4096) " (16 KB pages)" else ""
        }
        line("heap") { "${Runtime.getRuntime().maxMemory() / (1 shl 20)} MiB" }
        line("files dir") {
            val stat = android.os.StatFs(context.filesDir.path)
            "${context.filesDir.path}, free ${FileUtils.sizeToString(stat.availableBytes)} of ${FileUtils.sizeToString(stat.totalBytes)}"
        }
        line("runtime") { LinuxRuntime.rootDir(context).absolutePath + if (LinuxRuntime.rootDir(context).isDirectory) "" else " (not there)" }
        return lines
    }

    private fun soc(): String = if (android.os.Build.VERSION.SDK_INT >= 31) {
        "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}".trim().ifEmpty { "(not reported)" }
    } else "(SDK < 31)"

    private fun write(context: Context, text: String) {
        Log.i(TAG, text)
        writeTo(file(context), text)
    }

    /**
     * The append itself, over a file - no Context, so the capping below can be tested with a log
     * that is grown past its limit on purpose. Never throws: the caller's log breaking the install
     * it documents would be worse than no log.
     */
    internal fun writeTo(file: File, text: String) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(text + "\n")
                if (file.length() > MAX_BYTES) trim(file)
            }.onFailure { Log.w(TAG, "could not write the install log", it) }
        }
    }

    /** Drops the head of an oversized log, keeping the newest half and saying so at the top. */
    private fun trim(file: File) {
        val text = file.readText()
        val keep = text.takeLast((MAX_BYTES / 2).toInt())
        file.writeText("(earlier lines dropped: the log is capped)\n" + keep)
    }
}
