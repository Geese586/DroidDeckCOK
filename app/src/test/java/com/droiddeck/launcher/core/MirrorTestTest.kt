package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the mirror report that are pure: how a rate is spelled, whether anything answered at
 * all, and the accelerator table the report is measured over. None touches the network, so these
 * run in milliseconds - the measurement itself cannot be tested here, and does not need to be.
 *
 * <p>Rewritten for 0.5.0, which brought upstream's own `DownloadSource` back: the fork's
 * `inOrder()` / `mirrors()` helpers and their label-plus-prefix tables are gone with the engine
 * they belonged to, and the ranking is now upstream's `succeeded()` hook (see [MirrorTest.measure],
 * which calls it with the winner). What is still the fork's, and therefore still worth a test, is
 * the table those measurements are taken over.
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
    fun `the measured table covers the forks accelerators once each`() {
        val hosts = DownloadSource.MIRRORS.map { it.host }
        assertEquals(hosts.size, hosts.distinct().size)
        assertTrue(hosts.contains("gh-proxy.com"))
        assertTrue(hosts.contains("ghproxy.net"))
        // The fifth accelerator: surveyed from mainland China and answering, where ghfast.top did not.
        assertTrue(hosts.contains("ghfile.geekertao.top"))
        // The pick when nothing has been measured - mirror mode leads with this one.
        assertEquals("gh-proxy.com", hosts.first())
    }

    @Test
    fun `a mirror is measured through its own host and an api mirror may carry raw files only`() {
        val api = DownloadSource.MIRRORS.filter { it.api }.map { it.host }
        assertTrue(api.contains("gh-proxy.com"))
        // ghproxy.net and the two smaller accelerators answer blobs, not the API's JSON.
        assertFalse(api.contains("ghproxy.net"))
        assertFalse(api.contains("ghfast.top"))
    }

    @Test
    fun `a catalog and the GitHub API are never sent through a mirror`() {
        // Catalogs carry the digests everything else is checked against, so a mirror must not see one.
        val catalog = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-CI/index.json"
        assertTrue(DownloadSource.isCatalog(catalog))
        assertEquals(listOf(catalog), DownloadSource.candidates(catalog))
        val api = "https://api.github.com/repos/Droid-Deck/DroidDeck/releases"
        assertEquals(listOf(api), DownloadSource.candidates(api))
        // A URL on nobody's list is left exactly as it is.
        assertEquals(listOf("https://example.invalid/x"), DownloadSource.candidates("https://example.invalid/x"))
    }

    @Test
    fun `github hosts are recognised and everything else is not`() {
        assertTrue(DownloadSource.isGithub("https://github.com/Droid-Deck/DroidDeck/releases/download/0.5.0/x.apk"))
        assertTrue(DownloadSource.isGithub("https://raw.githubusercontent.com/a/b/c"))
        assertTrue(DownloadSource.isGithub("https://api.github.com/repos/a/b"))
        assertFalse(DownloadSource.isGithub("https://gh-proxy.com/https://github.com/a/b"))
        assertFalse(DownloadSource.isGithub("https://github.com.evil.example/a/b"))
    }
}
