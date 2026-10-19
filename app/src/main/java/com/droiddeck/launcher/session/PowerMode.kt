package com.droiddeck.launcher.session

import android.content.Context

/**
 * The power-saving switch on the Steam tab (DroidDeckCOK).
 *
 * Upstream 0.5.0 added four pieces of per-session overhead that a phone pays for whether or not
 * the user is looking at Steam: zram compression of the client (on by default below ~11 GiB),
 * the GameThreadHints boost loop, a 2 s fsync of the whole session folder, and a 100 ms glasses
 * tracking poll. Turning this switch on drops those four; turning it off has to be upstream's
 * behaviour exactly, so every getter below **delegates to the original SessionPrefs function**
 * rather than restating its default. A `zram()` that simply returned `false` would quietly break
 * ZramSupport.recommended()'s fallback for everyone who never opened the switch.
 *
 * Read at session start (SessionService) and once per session in SessionActivity; the switch
 * itself only changes the next session.
 */
object PowerMode {
    const val OFF_LOG_SYNC_MS = 2_000L
    const val ON_LOG_SYNC_MS = 15_000L
    const val OFF_GLASSES_POLL_MS = 100L
    const val ON_GLASSES_POLL_MS = 1_000L

    fun enabled(context: Context): Boolean = SessionPrefs.powerMode(context)

    /** Off: exactly [SessionPrefs.zramCompression], including its recommended() fallback. */
    fun zram(context: Context): Boolean =
        if (enabled(context)) false else SessionPrefs.zramCompression(context)

    /** Off: exactly [SessionPrefs.gameThreadHints], whose default is true. */
    fun threadHints(context: Context): Boolean =
        if (enabled(context)) false else SessionPrefs.gameThreadHints(context)

    fun logSyncMs(context: Context): Long = if (enabled(context)) ON_LOG_SYNC_MS else OFF_LOG_SYNC_MS

    fun glassesPollMs(context: Context): Long =
        if (enabled(context)) ON_GLASSES_POLL_MS else OFF_GLASSES_POLL_MS

    /** One line for app.log, so a test session says which of the two ran. */
    fun describe(context: Context): String =
        if (enabled(context)) {
            "power mode: on (zram off, game thread hints off, " +
                "app.log fsync $ON_LOG_SYNC_MS ms, glasses tracking poll $ON_GLASSES_POLL_MS ms)"
        } else {
            "power mode: off (0.5.0 logic)"
        }
}
