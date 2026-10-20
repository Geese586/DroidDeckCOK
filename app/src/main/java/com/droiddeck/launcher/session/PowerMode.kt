package com.droiddeck.launcher.session

import android.content.Context

/**
 * The power switch on the Steam tab (DroidDeckCOK).
 *
 * Upstream caps nothing by default: `fpsLimit` is 0, so the compositor composites at the panel
 * rate (120/144 Hz) and the display stays in its fastest mode even when a 2D game only draws 60
 * frames. Measured on a Snapdragon 8 Gen 3 running Sekiro, the four pieces this switch used to
 * drop (zram paging of the client, GameThreadHints, the log fsync interval, the glasses poll)
 * came to well under 0.15 W together - no meter can separate that from an 8 W session. Worse,
 * dropping zram trades CPU for memory and can bring lmkd in sooner.
 *
 * What does move the meter is the cadence the whole session runs at. With the switch on the
 * session is capped at [FPS_CAP]: `BL_FPS`, the compositor's `nativeSetLayerFrameRate` and
 * [PerfMode]'s display-mode pick all follow it, so a 120 Hz panel wakes 60 times a second
 * instead of 120. A game that never reaches 60 fps is left exactly as it was, and a limit the
 * user already set below [FPS_CAP] wins.
 */
object PowerMode {
    const val FPS_CAP = 60

    fun enabled(context: Context): Boolean = SessionPrefs.powerMode(context)

    /** Off: the user's own limit, untouched (0 means no cap). */
    fun fpsLimit(context: Context, original: Int): Int = when {
        !enabled(context) -> original
        original in 1 until FPS_CAP -> original
        else -> FPS_CAP
    }

    /** One line for app.log, so a test session says which of the two ran. */
    fun describe(context: Context): String =
        if (enabled(context)) {
            "power mode: on (frame cap $FPS_CAP fps; BL_FPS, compositor and display mode follow it)"
        } else {
            "power mode: off (0.5.0 logic, uncapped)"
        }
}
