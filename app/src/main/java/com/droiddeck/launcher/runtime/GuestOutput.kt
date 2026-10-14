package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPaths
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads a guest command's merged output line by line - to whoever asked for it, and to a file -
 * and, unlike a bare `waitFor()`, gives up on a child that has stopped saying anything.
 *
 * Three of the one-click install's steps run proot for a moment: fc-cache for the fonts, the Steam
 * client's own installer, and a third-party Proton's unpacker. proot's tracer can be left waiting
 * by a vendor kernel, and then the child never exits and never prints again - while we read its
 * output and then wait for it for ever. From the outside that is exactly a stuck install: the same
 * stage on screen, nothing reported, no way on. So the read has a deadline: no line for
 * [silenceMs] and the child is killed and reported as a timeout, which lets the step fail loudly
 * and the run carry on to the ones after it.
 *
 * The file is `files/logs/tools/<logName>.log`, the folder the session log share already carries,
 * so the lines a step died on leave the device with the rest of the logs.
 */
object GuestOutput {
    private const val TAG = "GuestOutput"

    /** How long a child may say nothing at all before it is taken for stuck. */
    const val SILENCE_MS = 10L * 60 * 1000

    /** How long a killed child is given to go away before it is killed harder. */
    private const val KILL_GRACE_MS = 3_000L

    /** A unique end-of-output marker the reader thread puts on the queue when the stream dries up. */
    private const val EOF = "\u0000droiddeck-eof"

    /**
     * What a child ended as. [status] is its exit code (-1 when it never gave one), [silentFor] how
     * long it had said nothing when it was killed, and [timedOut] whether that is what happened.
     */
    class Result(val status: Int, val silentFor: Long, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && status == 0
    }

    /**
     * Reads [child] until its output ends, handing every line to [onLine] and to
     * `files/logs/tools/[logName].log`. Kills it and returns a timeout when it says nothing for
     * [silenceMs]. [label] names the command in the log's own first line.
     */
    fun collect(context: Context, child: Process, logName: String, label: String,
                silenceMs: Long = SILENCE_MS, onLine: (String) -> Unit): Result =
        read(child, label, openLog(context, logName, label), silenceMs, onLine)

    /**
     * The read itself, over a child and an already-open log - no Context, so a test can drive it
     * with a real process and watch the deadline do its work.
     */
    internal fun read(child: Process, label: String, log: PrintWriter?, silenceMs: Long,
                      onLine: (String) -> Unit): Result {
        val queue = LinkedBlockingQueue<String>()
        // Reading on its own thread is what makes a deadline possible at all: the read blocks on
        // the pipe, so this thread cannot be the one waiting on the clock.
        val reader = Thread({
            try {
                child.inputStream.bufferedReader().useLines { for (line in it) queue.put(line) }
            } catch (t: Throwable) {
                Log.w(TAG, "$label: reading its output", t)
            } finally {
                runCatching { queue.put(EOF) }
            }
        }, "guest-output").apply { isDaemon = true; start() }

        var last = System.currentTimeMillis()
        try {
            while (true) {
                val line = queue.poll(500, TimeUnit.MILLISECONDS)
                if (line == null) {
                    val quiet = System.currentTimeMillis() - last
                    if (quiet >= silenceMs) {
                        kill(child)
                        val killed = "TIMEOUT: nothing said for ${quiet / 1000} s; the child was killed"
                        log?.println(killed); log?.flush()
                        Log.w(TAG, "$label $killed")
                        return Result(-1, quiet, true)
                    }
                    continue
                }
                if (line == EOF) break
                last = System.currentTimeMillis()
                log?.println(line); log?.flush()
                onLine(line)
            }
            // The output has ended, so this returns at once - unless the process is somehow still
            // around, and then the same deadline applies to the exit itself.
            val status = runCatching { child.waitFor() }.getOrElse { t ->
                Log.w(TAG, "$label: waiting for it to exit", t); -1
            }
            return Result(status, 0, false)
        } finally {
            log?.close()
        }
    }

    /** Opens the step's log file, with the moment and the command it is about at the top. */
    private fun openLog(context: Context, logName: String, label: String): PrintWriter? = try {
        val dir = File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }
        PrintWriter(java.io.FileWriter(File(dir, "$logName.log"), true)).also {
            it.println("== ${java.util.Date()} $label")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "could not open the log for $label", e)
        null
    }

    /** Ends [child] for good. Its own children are the runtime's problem, not this call's. */
    private fun kill(child: Process) {
        runCatching { child.destroy() }
        val deadline = System.currentTimeMillis() + KILL_GRACE_MS
        while (child.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (child.isAlive) runCatching { child.destroyForcibly() }
    }
}
