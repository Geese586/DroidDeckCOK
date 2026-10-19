package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the mirror report that are pure: how a rate is spelled, and how a measured
 * order is applied to the candidate list. Neither touches the network, so these run in
 * milliseconds - the measurement itself cannot be tested here, and does not need to be.
 */
class MirrorTestTest {

    @Test
    fun `rate spells each magnitude the way the report shows it`() {
        assertEquals("-", MirrorTest.rate(0))
        assertEquals("-", MirrorTest.rate(-1))
        assertEquals("512 B/s", MirrorTest.rate(512))
        assertEquals("2 KB/s", MirrorTest.rate(2048))
        assertEquals("1023 KB/s", MirrorTest.rate(1024 * 1023))
        // One decimal above a megabyte, and the decimal point is a point in every language.
        assertEquals("1.5 MB/s", MirrorTest.rate(1024 * 1024 * 3 / 2))
        assertEquals("5.0 MB/s", MirrorTest.rate(1024 * 1024 * 5))
    }

    @Test
    fun `anyReachable is false only when nothing answered`() {
        assertFalse(MirrorTest.anyReachable(listOf(
            MirrorTest.Line("gh-proxy.com", false, 0),
            MirrorTest.Line("github.com", false, 0),
        )))
        assertTrue(MirrorTest.anyReachable(listOf(
            MirrorTest.Line("gh-proxy.com", false, 0),
            MirrorTest.Line("github.com", true, 4_000_000),
        )))
        assertFalse(MirrorTest.anyReachable(emptyList()))
    }

    @Test
    fun `a measured order moves the fastest mirror first and leaves the rest in place`() {
        val candidates = listOf(
            "https://gh-proxy.com/https://github.com/x/y",
            "https://ghproxy.net/https://github.com/x/y",
            "https://ghfile.geekertao.top/https://github.com/x/y",
            "https://ghfast.top/https://github.com/x/y",
            "https://github.com/x/y",
        )
        // What a user on a fast GitHub line measures: the direct fetch wins, an accelerator second.
        val ordered = DownloadSource.inOrder(candidates, listOf("github.com", "ghproxy.net"))
        assertEquals(listOf(
            "https://github.com/x/y",
            "https://ghproxy.net/https://github.com/x/y",
            "https://gh-proxy.com/https://github.com/x/y",
            "https://ghfile.geekertao.top/https://github.com/x/y",
            "https://ghfast.top/https://github.com/x/y",
        ), ordered)
    }

    @Test
    fun `an order that names nothing leaves the candidate list alone`() {
        val candidates = listOf(
            "https://gh-proxy.com/https://github.com/x/y",
            "https://github.com/x/y",
        )
        assertEquals(candidates, DownloadSource.inOrder(candidates, emptyList()))
        assertEquals(candidates, DownloadSource.inOrder(candidates, listOf("example.invalid")))
    }

    @Test
    fun `mirrors names the accelerators and GitHub itself`() {
        val labels = DownloadSource.mirrors().map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertEquals("github.com", labels.last())
        assertTrue(labels.contains("gh-proxy.com"))
        assertTrue(labels.contains("ghproxy.net"))
        // The direct entry is the empty prefix: what the report calls a fetch that bypassed them all.
        assertEquals("", DownloadSource.mirrors().last().prefix)
    }
}
