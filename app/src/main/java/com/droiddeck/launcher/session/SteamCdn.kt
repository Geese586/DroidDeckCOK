package com.droiddeck.launcher.session

import android.content.Context

/**
 * Which of Valve's client-update edges the Steam client is fetched from.
 *
 * Valve publishes the same packages from two independent edge networks - Fastly and Akamai - and
 * the speed and reachability of each are properties of the network the device is on, not of the
 * device: the build that comes down in seconds through one can crawl or never start through the
 * other, and which is which differs between carriers and cities. The guest script tries them in
 * turn but only moves on when a host refuses a file outright, so a host that answers yet crawls is
 * waited out, and until now nothing on this side ever chose between them.
 *
 * [AUTO] is the script's own order, Fastly then Akamai. [FASTLY] and [AKAMAI] pin one edge - what
 * a user who has timed the two (Setup › Diagnostics › the line test) can do. A pinned edge is used
 * alone, the script's own rule for an override, so pinning one that the network cannot reach at
 * all trades a slow install for a failed one: that is why the choice is never made for the user
 * and the diagnostic exists to inform it.
 */
object SteamCdn {
    /** The guest script's own order; no BL_STEAM_CDN is passed at all. */
    const val AUTO = ""
    const val FASTLY = "fastly"
    const val AKAMAI = "akamai"

    /** The values the setting takes, in menu order. */
    val choices = listOf(AUTO, FASTLY, AKAMAI)

    /** The two edges, as the DNS names the bootstrapper and the script both resolve. */
    const val FASTLY_HOST = "client-update.fastly.steamstatic.com"
    const val AKAMAI_HOST = "client-update.akamai.steamstatic.com"

    private const val PREFS = "session"
    private const val KEY = "steamCdn"

    /** [AUTO], [FASTLY] or [AKAMAI]; anything else reads as [AUTO]. */
    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO)
            ?.takeIf { it in choices } ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, value.takeIf { it in choices } ?: AUTO).apply()
    }

    /**
     * The value BL_STEAM_CDN takes for [choice]: empty means the variable is not set and the
     * guest keeps its own order - the script's `case` treats any other string, including an
     * empty one's absence, as a host to use alone, so only a real pin is ever passed.
     */
    fun guestValue(choice: String): String = when (choice) {
        FASTLY -> FASTLY
        AKAMAI -> AKAMAI
        else -> AUTO
    }

    /**
     * The edges a run of [choice] would try, in order - also the edges the line test measures,
     * both of them, whatever the choice: the point of the test is to inform the choice.
     */
    fun hosts(choice: String): List<String> = when (choice) {
        FASTLY -> listOf(FASTLY_HOST)
        AKAMAI -> listOf(AKAMAI_HOST)
        else -> listOf(FASTLY_HOST, AKAMAI_HOST)
    }

    /** The manifest's file name, the one small file every edge serves for a connectivity test. */
    const val MANIFEST = "steam_client_steamdeck_publicbeta_linuxarm64"
}
