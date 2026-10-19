package com.droiddeck.launcher.core

import android.content.Context

/**
 * Where the app fetches the things that live on GitHub: the runtime catalog and the ~790 MB
 * rootfs beside it, the desktop catalog, Steam's seed, Proton builds, components and the app's
 * own updates. Every one of them is a github.com, raw.githubusercontent.com or api.github.com
 * URL, and from mainland China none of those answer - the fetch either times out or is reset.
 *
 * <p>The academic and vendor mirrors do not help, and this was measured rather than assumed:
 * mirrors.tuna.tsinghua.edu.cn/github-release, mirrors.aliyun.com, mirrors.cloud.tencent.com,
 * mirrors.ustc.edu.cn and mirror.nju.edu.cn all answer 404 for this project's repositories -
 * their github-release trees sync only the repositories on their own lists. mirrors.huaweicloud.com
 * answers 200 with an empty single-page-app shell for *any* path, which is not a mirror of
 * anything. TUNA, USTC, Aliyun and Tencent do carry Arch Linux ARM's package repository, which is
 * the runtime's base, but the runtime ships as one prebuilt tarball on GitHub, so their package
 * trees are not on this path either. What does work is a GitHub accelerator: a public HTTPS
 * endpoint that fetches the original URL on its own side and streams the bytes back through it,
 * which carries both raw.githubusercontent and the release assets.
 *
 * <p>Which accelerator is fastest is a property of the user's own network, not of this list - a
 * user on a 5 MB/s GitHub line has watched a download slow to 1 MB/s because it was routed through
 * an accelerator that was the right answer on somebody else's connection. So the order is not
 * trusted: [MirrorTest] measures every mirror on the device, [remember] keeps that measurement,
 * and [ranked] hands the download the measured order, fastest first. The Toast the test shows is
 * therefore the reason for the choice the download then makes - not a guess dressed up as one.
 *
 * <p>One honest limitation: the accelerators ignore a Range request and answer 200 with the whole
 * file, so a download through one cannot resume where it stopped - [Downloader] detects the 200
 * and starts the file again rather than corrupting it, and the sha256 still has to match before
 * anything is unpacked.
 */
object DownloadSource {
    /** Straight to GitHub, for a network that reaches it. */
    const val OFFICIAL = "official"

    /** Through a GitHub accelerator, the default: most of this app's audience cannot reach GitHub. */
    const val CHINA = "china"

    /**
     * One way of reaching GitHub: the host a report names it by, and the prefix that turns an
     * original URL into the one to fetch through it. GitHub itself is the empty prefix.
     */
    data class Mirror(val label: String, val prefix: String)

    /**
     * The accelerators, in the order they are tried before anything has been measured. The ones
     * that answered when last surveyed come first, because the ones that did not cost a connect
     * timeout every time they are reached; the direct URL is deliberately not in this list - it
     * goes last, so an accelerator outage still installs.
     */
    private val ACCELERATORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfile.geekertao.top/",
        // Did not answer when last surveyed (TLS handshake failure); kept last as a spare, since a
        // mirror that comes back is one more chance at a fast install and costs nothing behind the
        // three that do answer.
        "https://ghfast.top/",
    )

    /** GitHub itself: what the report calls a fetch that went straight to the source. */
    private val DIRECT = Mirror("github.com", "")

    /**
     * The hosts an accelerator carries. Anything else - Flathub, a game's own CDN, a driver
     * vendor - is fetched directly whatever the source is set to.
     */
    private val GITHUB_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "codeload.github.com",
        "gist.githubusercontent.com",
    )

    private const val PREFS = "session"
    private const val KEY = "downloadSource"

    /** What one probe reads at most, and how long it may take: enough to rank, not enough to hurt. */
    private const val PROBE_BYTES = 512 * 1024L
    private const val PROBE_TIMEOUT_MS = 4_000L

    /**
     * How long a [MirrorTest] measurement stays good. Long enough to cover the download it was run
     * for - the two moments the test runs are seconds away from one - and short enough that a
     * network that has changed since is measured again rather than believed.
     */
    private const val ORDER_TTL_MS = 300_000L

    /** Set once from App.onCreate; without it every URL resolves to its direct form. */
    @Volatile
    private var appContext: Context? = null

    /** The hosts a [MirrorTest] measured, fastest first, and when it did. */
    @Volatile
    private var measuredOrder: List<String> = emptyList()

    @Volatile
    private var measuredAt = 0L

    /** Set while a [MirrorTest] is running: see [awaitMeasurement] for why a download waits on it. */
    @Volatile
    private var measuring = false

    private val measureLock = java.lang.Object()

    /**
     * How long a download will wait for a measurement already under way. Beyond the test's own
     * timeouts plus a margin - a download that waits longer than that is waiting on a bug.
     */
    private const val MEASURE_WAIT_MS = 20_000L

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * [OFFICIAL] or [CHINA]. A fresh install defaults to [CHINA]: the audience is mainland
     * devices, and from there the direct GitHub hosts answer slowly or not at all, while the
     * accelerators answer first thing. A user who reaches GitHub fine switches back in Setup -
     * the choice is remembered for the life of the install.
     */
    @JvmStatic
    fun current(context: Context): String =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) {
            OFFICIAL -> OFFICIAL
            CHINA -> CHINA
            // Never stored: new users start on the mainland mirror.
            else -> CHINA
        }

    @JvmStatic
    fun set(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (source == CHINA) CHINA else OFFICIAL).apply()
    }

    /**
     * Every mirror the app can fetch a GitHub URL through, GitHub itself last. The report names
     * them by [Mirror.label]; the order is the fallback order, not the recommended one - that
     * comes from measurement.
     */
    @JvmStatic
    fun mirrors(): List<Mirror> =
        ACCELERATORS.mapNotNull { prefix -> host(prefix)?.let { Mirror(it, prefix) } } + DIRECT

    /**
     * The URLs to try for [url], in order: every accelerator ahead of the original one, or just
     * the original when the source is [OFFICIAL], the URL is not one an accelerator carries, or
     * [init] has not run. The original comes last so an accelerator outage still installs.
     */
    @JvmStatic
    fun candidates(url: String): List<String> {
        if (url.isEmpty()) return listOf(url)
        val context = appContext ?: return listOf(url)
        if (current(context) != CHINA) return listOf(url)
        if (host(url)?.let { it in GITHUB_HOSTS } != true) return listOf(url)
        return mirrors().map { it.prefix + url }
    }

    /**
     * [candidates] with the order settled by measurement rather than by the list: the order a
     * [MirrorTest] has just measured if it is still fresh, otherwise every candidate probed in
     * parallel (up to [PROBE_BYTES] in [PROBE_TIMEOUT_MS]) with the fastest that answered first and
     * the silent ones last. This is what a big download runs before its first byte - a few seconds
     * spent finding out which mirror actually answers from this network, instead of minutes
     * watching the first one in the list sit at 0%. Small fetches stay on [candidates]: probing
     * them costs more than the download.
     */
    @JvmStatic
    fun ranked(url: String): List<String> {
        val list = candidates(url)
        if (list.size < 2) return list
        // A measurement the user is watching is about to say which mirror is fastest. Waiting for
        // it costs the seconds it is already taking and buys a download that takes the order that
        // was reported - probing in parallel with it would race the report the user is reading.
        awaitMeasurement()
        freshOrder()?.let { order -> return inOrder(list, order) }
        return byProbe(list)
    }

    /**
     * Opens a measurement window. [MirrorTest] brackets its run with this pair so a download
     * starting while the user is reading "testing mirror speed" waits for the answer rather than
     * measuring a second time and possibly disagreeing with it.
     */
    @JvmStatic
    fun beginMeasure() {
        synchronized(measureLock) {
            measuring = true
        }
    }

    @JvmStatic
    fun endMeasure() {
        synchronized(measureLock) {
            measuring = false
            measureLock.notifyAll()
        }
    }

    private fun awaitMeasurement() {
        if (!measuring) return
        synchronized(measureLock) {
            val deadline = System.currentTimeMillis() + MEASURE_WAIT_MS
            while (measuring && System.currentTimeMillis() < deadline) {
                try {
                    measureLock.wait(250L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * Records the order [MirrorTest] just measured, by host, fastest first. The next download
     * takes it instead of probing again, so the mirror the user was shown as the fastest is the
     * one the download actually uses.
     */
    @JvmStatic
    fun remember(order: List<String>) {
        if (order.isEmpty()) return
        measuredOrder = order
        measuredAt = System.currentTimeMillis()
    }

    /** Drops a measurement: what switching to the direct source does, so the next one re-measures. */
    @JvmStatic
    fun forget() {
        measuredOrder = emptyList()
        measuredAt = 0L
    }

    private fun freshOrder(): List<String>? =
        if (measuredOrder.isEmpty() || System.currentTimeMillis() - measuredAt > ORDER_TTL_MS) null
        else measuredOrder

    /**
     * [list] sorted the way [order] (hosts, best first) says; an unlisted host keeps its place.
     * Internal rather than private so a unit test can pin the ordering without a network.
     */
    internal fun inOrder(list: List<String>, order: List<String>): List<String> {
        val rank = order.withIndex().associate { (i, h) -> h to i }
        return list.withIndex().sortedBy { (i, u) -> rank[host(u)] ?: (order.size + i) }
            .map { it.value }
    }

    /** The measured order when nothing has measured one yet: probe every candidate in parallel. */
    private fun byProbe(list: List<String>): List<String> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(list.size)
        try {
            val scored = pool.invokeAll(list.map { candidate ->
                java.util.concurrent.Callable { candidate to probe(candidate) }
            }).map { future ->
                try {
                    future.get(PROBE_TIMEOUT_MS + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    null
                }
            }.filterNotNull()
            if (scored.isEmpty()) return list
            val answered = scored.filter { it.second > 0 }.sortedByDescending { it.second }
            val silent = scored.filter { it.second <= 0 }
            return (answered + silent).map { it.first }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Throughput of [url] over up to [PROBE_BYTES], B/s; negative when nothing came down. */
    private fun probe(url: String): Double {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        var bytes = 0L
        val start = System.nanoTime()
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = PROBE_TIMEOUT_MS.toInt()
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DroidDeck-Android")
            // GET, not HEAD: an accelerator may not answer a HEAD at all. A server that ignores
            // the range just streams the whole file, and the deadline below caps what is read.
            connection.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
            if (connection.responseCode / 100 != 2) return -1.0
            val deadline = start + PROBE_TIMEOUT_MS * 1_000_000L
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (System.nanoTime() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    if (bytes >= PROBE_BYTES) break
                }
            }
        } catch (e: Exception) {
            return if (bytes > 0) bytes / ((System.nanoTime() - start) / 1e9) else -1.0
        } finally {
            connection.disconnect()
        }
        val seconds = (System.nanoTime() - start) / 1e9
        return if (bytes > 0 && seconds > 0) bytes / seconds else -1.0
    }

    private fun host(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
}
