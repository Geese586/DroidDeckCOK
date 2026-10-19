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

    /**
     * The edge the line test just measured fastest, for the install that is about to run. In memory
     * and for one run only: it is not a setting, so it is never written to prefs and the menu never
     * shows it - a measurement is the reason for one install, not a standing preference. Reset on
     * every run that does not measure ([setOverride] with [AUTO]), so it cannot outlive the session
     * it was made for and pin an edge months later.
     */
    @Volatile
    private var measured = AUTO

    /**
     * The same run's numbers, as one line, already worded for the user. The Toast carries them the
     * moment they are taken; the loading screen carries them again for as long as the install they
     * were taken for lasts (see LoadingState), because "which line is this download using, and why"
     * is a question asked while watching it, not in the three seconds before it starts. Empty
     * whenever nothing was measured.
     */
    @Volatile
    private var measuredReport = ""

    /** Pins [choice] for the next install, or [AUTO] to go back to whatever Setup holds. */
    fun setOverride(choice: String) {
        measured = choice.takeIf { it in choices } ?: AUTO
        // A choice made by hand, or the reset at the end of a run that measured nothing, leaves no
        // numbers behind: the ranking belongs to the run it was measured for and to no other.
        measuredReport = ""
        measuredOrder = emptyList()
    }

    /**
     * The measured order for the same one run: the two edges this device timed, fastest first.
     * [measured] pins a single edge and is used alone, which is only ever right when one edge is in
     * a different league - so everything short of that used to fall back to the script's own order,
     * and a device whose edges measured 1.0 MB/s and 675 KB/s downloaded through the slow one while
     * the report said the other was fastest. An order is the honest form of that measurement: the
     * fastest edge is tried first and the other follows it when a host dies, which no pin allows.
     */
    @Volatile
    private var measuredOrder: List<String> = emptyList()

    /** Pins [choice], and keeps [report] - the ranking it came from - for the run it is for. */
    fun setMeasured(choice: String, report: String, order: List<String> = emptyList()) {
        measured = choice.takeIf { it in choices } ?: AUTO
        measuredReport = report
        measuredOrder = order
    }

    /** This run's measured ranking, or empty when it was not measured. */
    fun report(): String = measuredReport

    /** This run's measured order, fastest edge first; empty when it was not measured. */
    fun orderForSession(): List<String> = measuredOrder

    /**
     * The menu the loading screen offers while a client download runs, fastest first: this run's
     * measured order, or both edges in the script's own order when nothing was measured. As
     * [FASTLY]/[AKAMAI] values, since that is what a tap writes - the hosts themselves never leave
     * the app.
     */
    fun choicesForSession(): List<String> =
        orderForSession().ifEmpty { hosts(AUTO) }.mapNotNull { host ->
            when (host) {
                FASTLY_HOST -> FASTLY
                AKAMAI_HOST -> AKAMAI
                else -> null
            }
        }

    /**
     * [orderForSession] as BL_STEAM_CDN_ORDER's value: both known edges, fastest first, or empty
     * when the measurement did not cover both. A one-host order would be a pin without its reason -
     * the script's own order at least switches edges when a host refuses a file - so only a full
     * pair is ever passed. Hosts the app does not know are dropped rather than forwarded.
     */
    internal fun orderValue(order: List<String>): String {
        val known = order.filter { it == FASTLY_HOST || it == AKAMAI_HOST }.distinct()
        return if (known.size == 2) known.joinToString(" ") else ""
    }

    /** What an install starting right now should use: this run's measurement, else the setting. */
    fun forSession(context: Context): String = resolved(current(context))

    /**
     * [forSession]'s decision, with the stored setting passed in: the measurement wins for the one
     * run it was made for, and [AUTO] - the state nothing is measured in - falls through to the
     * setting. Split out so the rule can be tested without a Context.
     */
    internal fun resolved(setting: String): String = measured.takeIf { it.isNotEmpty() } ?: setting
}
