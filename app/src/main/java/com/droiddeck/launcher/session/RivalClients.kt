package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Other apps on the device that sign into the user's Steam account with their own client. Steam
 * allows one session per account: the moment one of them signs in, ours is signed out - which
 * shows up as "Session Replaced" in the client's log 2-3 seconds after every login, and looks
 * like a bug in this app.
 *
 * Installed is the most a normal app can know (since Android 7 `getRunningAppProcesses()` returns
 * only the caller's own), so the list is checked for presence and then asked to stop.
 * `killBackgroundProcesses` is a normal permission: it ends an app's background processes and
 * leaves anything in the foreground alone - and background is exactly the case that keeps
 * happening, because GameHub declares boot receivers and is running from the moment the phone
 * starts without ever having been opened. Telling someone to close an app they never opened is
 * not much help. Ported from Bannerlator's Linux session (GPL-3.0).
 */
object RivalClients {
    private const val TAG = "RivalClients"

    private val PACKAGES = arrayOf(
        "com.xiaoji.egggame",     // GameHub
    )

    fun installed(context: Context): List<String> =
        PACKAGES.filter { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Throwable) { false }
        }

    fun name(pkg: String): String = if (pkg == "com.xiaoji.egggame") "GameHub" else pkg

    /**
     * Stop the installed rivals' background processes before the client starts. A rival in the
     * foreground survives this. The activity service call and its log messages are unchanged.
     */
    fun stopBeforeSession(context: Context) {
        val rivals = installed(context)
        if (rivals.isEmpty()) return
        Log.w(TAG, "competing Steam client installed: $rivals")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                for (pkg in rivals) am.killBackgroundProcesses(pkg)
                Log.i(TAG, "asked Android to stop background processes of $rivals")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not stop competing Steam clients", t)
        }
    }
}
