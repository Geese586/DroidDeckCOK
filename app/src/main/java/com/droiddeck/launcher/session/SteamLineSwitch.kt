package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * The edge the user taps on the loading screen while a Steam client download is running.
 *
 * The choice travels as a file, not as a signal: the session root is bind-mounted into the guest at
 * its own path, and `droiddeck-session` already takes steam-stop, steam-game and steam-sleep-ready
 * through it. So the app writes an edge name into `steam-line` and `droiddeck-steam-install` reads
 * it once a second while a file is coming down - no new channel across the proot boundary, and
 * nothing to keep in sync about pid's.
 *
 * The file is deliberately per run. A tap is a reason for one install, like the measurement that
 * produced the ranking above it: [clear] runs at the start of every session, so last night's choice
 * cannot decide tonight's download. Its absence means [AUTO] - the script's own order, or the
 * measured one - which is why [choose] with [AUTO] deletes the file rather than writing a word for
 * it.
 */
object SteamLineSwitch {
    /** No file: the script uses its measured order, or its own when nothing was measured. */
    const val AUTO = SteamCdn.AUTO

    /** The name the guest reads; also the file's name in the session root. */
    const val FILE = "steam-line"

    fun file(context: Context): File = File(LinuxRuntime.sessionRoot(context), FILE)

    /** What the guest has been told: [SteamCdn.FASTLY], [SteamCdn.AKAMAI] or [AUTO]. */
    fun current(context: Context): String = read(file(context))

    /** [choice] for the running install; [AUTO] deletes the file, meaning "decide it yourself". */
    fun choose(context: Context, choice: String) = write(file(context), choice)

    /** Forget a tap from an earlier session. */
    fun clear(context: Context) {
        file(context).delete()
    }

    /** [current] on a directory rather than a context, so the rule can be tested without one. */
    internal fun read(target: File): String = runCatching { target.readText().trim() }
        .getOrDefault(AUTO)
        .takeIf { it == SteamCdn.FASTLY || it == SteamCdn.AKAMAI }
        ?: AUTO

    /**
     * [choose] on a directory rather than a context.
     *
     * Written through a temporary name and renamed into place: the guest polls this file while it
     * downloads, and a reader that caught a half-written name would find an edge it does not know
     * and stay where it is - the one moment the file exists is the one moment it must be whole.
     */
    internal fun write(target: File, choice: String) {
        if (choice != SteamCdn.FASTLY && choice != SteamCdn.AKAMAI) {
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText("$choice\n")
        if (!tmp.renameTo(target)) {
            target.writeText("$choice\n")
            tmp.delete()
        }
    }
}
