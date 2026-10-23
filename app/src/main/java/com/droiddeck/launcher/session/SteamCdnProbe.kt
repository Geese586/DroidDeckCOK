package com.droiddeck.launcher.session

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Times Valve's two client-update edges from the device, so the CDN choice in Setup is an
 * informed one rather than a guess.
 *
 * Two measurements per edge, both over HTTPS the way the client itself fetches:
 *
 * 1. the manifest - a few KB - for whether the edge answers at all, and how long the first byte
 *    takes (a blocked or reset edge shows up here, as the timeout or the exception it raises);
 * 2. one megabyte of a real component, named by that manifest, for throughput - what a user on a
 *    cross-border link actually feels, and what "fastly is quicker for me" means in numbers.
 *
 * The measurement runs off whatever thread calls it and touches nothing but the network: no pref
 * is written and no file is left behind. Reading a megabyte per edge costs the user two at most,
 * which is nothing against the 344 MB a client install moves.
 *
 * [measureAll] is what the two moments that fetch the client use (Setup's test and the start of a
 * Steam session with no client in place): every edge at once, fastest first, so the report and the
 * install that follows it are about the same order rather than about two separate probes.
 */
object SteamCdnProbe {
    /** How one edge came out. [bytesPerSecond] is the component sample's rate, 0 when not measured. */
    data class Edge(
        val host: String,
        val reachable: Boolean,
        val status: Int,
        /** Milliseconds to the manifest's first byte; -1 when the edge never answered. */
        val manifestMillis: Long,
        val bytesPerSecond: Long,
        val error: String?,
    )

    /** The throughput sample, in bytes; small enough to keep the test light, big enough to time. */
    private const val SAMPLE_BYTES = 1024 * 1024

    /** Timeouts per request: an unreachable edge is an answer too, and this is how long it takes. */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * How an edge comes out when it was still going when the caller's budget ran out. Not the same
     * as unreachable: it is a verdict about this test's patience, not about the network, and it is
     * never turned into a recommendation.
     */
    const val TIMED_OUT = "timed out"

    fun measure(host: String): Edge = try {
        val manifest = fetch(host, SteamCdn.MANIFEST, null)
        val component = manifest.body?.let { firstComponent(it) }
        val rate = if (component == null) 0L else sample(host, component)
        Edge(host, true, manifest.status, manifest.millis, rate, null)
    } catch (e: Exception) {
        Edge(host, false, 0, -1L, 0L, e.message ?: e.javaClass.simpleName)
    }

    /**
     * Every edge in [hosts], measured at once and returned fastest first.
     *
     * [budgetMs] bounds the whole run rather than each edge: two edges on a working network answer
     * in a second or two, and the caller of this is about to put a session on screen, so a run that
     * has not answered by then is abandoned and reported as [TIMED_OUT] - the install still starts,
     * it just starts without a pin. The abandoned threads are left to time out on their own
     * connections; they write nothing anywhere.
     */
    @JvmStatic
    fun measureAll(hosts: List<String>, budgetMs: Long): List<Edge> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(hosts.size)
        val deadline = System.currentTimeMillis() + budgetMs
        val edges = try {
            hosts.map { host -> host to pool.submit(Callable { measure(host) }) }.map { (host, future) ->
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) timedOut(host)
                else try {
                    future.get(left, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    timedOut(host)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return ranked(edges)
    }

    /** What an edge that outlasted the budget reads as; see [TIMED_OUT]. */
    private fun timedOut(host: String) = Edge(host, false, 0, -1L, 0L, TIMED_OUT)

    /** [edges] fastest first; anything that moved no bytes - timed out or refused - goes last. */
    @JvmStatic
    fun ranked(edges: List<Edge>): List<Edge> = edges.sortedByDescending { it.bytesPerSecond }

    /** The edge's name in the report: the network, because a DNS name means nothing to a user. */
    @JvmStatic
    fun label(host: String): String = when (host) {
        SteamCdn.FASTLY_HOST -> "Fastly"
        SteamCdn.AKAMAI_HOST -> "Akamai"
        else -> host
    }

    private class Response(val status: Int, val millis: Long, val body: String?)

    private fun fetch(host: String, file: String, range: String?): Response {
        val connection = URL("https://$host/$file").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            if (range != null) connection.setRequestProperty("Range", range)
            val started = System.currentTimeMillis()
            connection.connect()
            val status = connection.responseCode
            val millis = System.currentTimeMillis() - started
            val body = if (status in 200..299) connection.inputStream.use { input ->
                input.readBytes().toString(Charsets.ISO_8859_1)
            } else null
            return Response(status, millis, body)
        } finally {
            connection.disconnect()
        }
    }

    /** One megabyte of [file], timed - the number the user compares between the two edges. */
    private fun sample(host: String, file: String): Long {
        val connection = URL("https://$host/$file").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Range", "bytes=0-${SAMPLE_BYTES - 1}")
            val started = System.currentTimeMillis()
            val input = if (connection.responseCode in 200..299) connection.inputStream else return 0
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            while (read < SAMPLE_BYTES) {
                val n = input.read(buffer, 0, buffer.size)
                if (n < 0) break
                read += n
            }
            input.close()
            val millis = System.currentTimeMillis() - started
            return if (millis <= 0) 0L else read * 1000L / millis
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The first component's file name in a manifest - the `file` line of any block, the same shape
     * the guest script's awk reads. A manifest without one is still evidence the edge answers.
     */
    internal fun firstComponent(manifest: String): String? =
        Regex("\"file\"\\s+\"([^\"]+\\.zip\\.[0-9a-f]{40})\"").find(manifest)?.groupValues?.get(1)

    /** US-locale on purpose: the dialog reads the same in every language the app ships. */
    internal fun rateToString(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1_048_576.0)
        bytesPerSecond >= 1024 -> "${bytesPerSecond / 1024} KB/s"
        bytesPerSecond > 0 -> "$bytesPerSecond B/s"
        else -> ""
    }

    internal fun millisToString(millis: Long): String =
        String.format(java.util.Locale.US, "%.2f s", millis / 1000.0)

    /**
     * Which edge to pin, from the measurements: the faster of two reachable edges when it is
     * clearly faster, and nothing when one is unreachable (pinning an unreachable edge trades a
     * slow install for a failed one), when only one answered (the other is the fallback anyway),
     * or when the two are close enough that the script's own order is as good a choice.
     */
    internal fun recommendation(edges: List<Edge>): String? {
        val reachable = edges.filter { it.reachable && it.bytesPerSecond > 0 }
        if (reachable.size < 2) return null
        val best = reachable.maxBy { it.bytesPerSecond }
        val rest = reachable.filter { it !== best }
        return if (best.bytesPerSecond >= rest.minOf { it.bytesPerSecond } * 2) {
            when (best.host) {
                SteamCdn.FASTLY_HOST -> SteamCdn.FASTLY
                SteamCdn.AKAMAI_HOST -> SteamCdn.AKAMAI
                else -> null
            }
        } else null
    }
}
