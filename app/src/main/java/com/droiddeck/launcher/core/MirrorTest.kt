package com.droiddeck.launcher.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Times every mirror this app can reach GitHub through, so the choice the downloader then makes
 * is one the user can see the reason for.
 *
 * <p>One real file, fetched through every mirror at once, each reading up to [SAMPLE_BYTES]; the
 * order that comes out is by measured throughput, fastest first, with the mirrors that answered
 * nothing at the end. It is deliberately not a latency ping: what this is trying to predict is how
 * long a 790 MB rootfs will take, and a mirror that answers a HEAD in 20 ms and then dribbles is
 * exactly the case that made this necessary.
 *
 * <p>The winner is written back through [DownloadSource.succeeded], which is what makes the
 * report honest rather than decorative - the download that starts seconds later prefers the mirror
 * the user was just shown as the fastest, instead of measuring again and possibly disagreeing with
 * itself. (Upstream 0.5.0 replaced the fork's own ranking tables with that one call; the fork keeps
 * its measurement, which weighs throughput rather than latency.)
 *
 * <p>The cost is the sample times the number of mirrors, once per test: a few megabytes against
 * the hundreds of megabytes the test exists to protect, and only on the two moments where a big
 * download is about to start or a mirror is being chosen.
 */
object MirrorTest {
    /** One mirror's result. [bytesPerSecond] is 0 when nothing came down inside the deadline. */
    data class Line(val label: String, val reachable: Boolean, val bytesPerSecond: Long)

    /**
     * Fetched when the caller has no better URL - when the runtime catalog itself cannot be
     * reached, which is the case this whole mechanism exists for. It is a real release asset of
     * the project's own components repository: a tagged release is not deleted, and if upstream
     * ever prunes it the test falls back to reporting nothing rather than to reporting a lie.
     */
    const val FALLBACK_URL =
        "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linuxfs-r9/linuxfs.tar.zst"

    /** What each mirror reads at most: enough to tell a fast mirror from a slow one. */
    private const val SAMPLE_BYTES = 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * Every mirror measured against [url], fastest first. Blocks until they have all answered or
     * timed out - the whole set runs in parallel, so that is [READ_TIMEOUT_MS] at worst, not
     * [READ_TIMEOUT_MS] per mirror. Records the order in [DownloadSource] before returning it.
     */
    @JvmStatic
    fun measure(url: String): List<Line> {
        val mirrors = DownloadSource.MIRRORS
        if (mirrors.isEmpty()) return emptyList()
        val lines = try {
            val pool = Executors.newFixedThreadPool(mirrors.size)
            try {
                pool.invokeAll(mirrors.map { mirror ->
                    Callable { time("https://${mirror.host}/$url") }
                }).mapIndexed { index, future ->
                    val rate = try {
                        future.get(READ_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        -1L
                    }
                    Line(mirrors[index].host, rate > 0, if (rate > 0) rate else 0L)
                }
            } finally {
                pool.shutdownNow()
            }
        } catch (e: Exception) {
            emptyList()
        }
        val ranked = lines.sortedByDescending { it.bytesPerSecond }
        // The next download prefers what was just measured: succeeded() is upstream's own hook for
        // "this host answered", and its candidates() puts that host's proxy first.
        ranked.firstOrNull { it.reachable }?.let { DownloadSource.succeeded("https://${it.label}/") }
        return ranked
    }

    /**
     * Whether anything answered at all. A network with no GitHub route at all - the offline case -
     * comes back all zeros, and "nothing answered" is a more useful thing to say than a list of
     * zeroes.
     */
    @JvmStatic
    fun anyReachable(lines: List<Line>): Boolean = lines.any { it.reachable }

    /**
     * MB/s with one decimal, KB/s whole, US locale on purpose so the report reads the same in
     * every language the app ships rather than picking up a comma decimal separator.
     */
    @JvmStatic
    fun rate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0)
        bytesPerSecond >= 1024 -> "${bytesPerSecond / 1024} KB/s"
        bytesPerSecond > 0 -> "$bytesPerSecond B/s"
        else -> "-"
    }

    /** Throughput of [url] over up to [SAMPLE_BYTES], B/s; negative when nothing came down. */
    private fun time(url: String): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        var bytes = 0L
        val start = System.nanoTime()
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DroidDeck-Android")
            // GET with a range, not HEAD: an accelerator may not answer a HEAD at all, and one
            // that ignores the range just streams the whole file - the byte cap below stops it.
            connection.setRequestProperty("Range", "bytes=0-${SAMPLE_BYTES - 1}")
            if (connection.responseCode / 100 != 2) return -1L
            val deadline = start + READ_TIMEOUT_MS * 1_000_000L
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (bytes < SAMPLE_BYTES && System.nanoTime() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                }
            }
        } catch (e: Exception) {
            // Partial reads still count: a mirror that sent half the sample before stalling is
            // slower than one that sent all of it, and both beat one that sent nothing.
        } finally {
            connection.disconnect()
        }
        val seconds = (System.nanoTime() - start) / 1e9
        return if (bytes > 0 && seconds > 0) (bytes / seconds).toLong() else -1L
    }
}
