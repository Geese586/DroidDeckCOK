package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind the Steam line test and the CDN choice (DroidDeckCOK): which edges a
 * choice tries, what BL_STEAM_CDN carries, which component a manifest names, when the numbers say
 * to pin an edge. No Android and no network - the probe's IO is left to the device.
 */
class SteamCdnTest {
    @Test
    fun `automatic passes nothing to the guest`() {
        assertEquals("", SteamCdn.guestValue(SteamCdn.AUTO))
    }

    @Test
    fun `a pin is passed as its own id`() {
        assertEquals("fastly", SteamCdn.guestValue(SteamCdn.FASTLY))
        assertEquals("akamai", SteamCdn.guestValue(SteamCdn.AKAMAI))
    }

    @Test
    fun `an unknown choice reads as automatic`() {
        assertEquals("", SteamCdn.guestValue("edge.example.com"))
        assertEquals(listOf(SteamCdn.FASTLY_HOST, SteamCdn.AKAMAI_HOST), SteamCdn.hosts("edge.example.com"))
    }

    @Test
    fun `automatic tries both edges in the guest's own order`() {
        assertEquals(listOf(SteamCdn.FASTLY_HOST, SteamCdn.AKAMAI_HOST), SteamCdn.hosts(SteamCdn.AUTO))
    }

    @Test
    fun `a pin measures its own edge only`() {
        assertEquals(listOf(SteamCdn.FASTLY_HOST), SteamCdn.hosts(SteamCdn.FASTLY))
        assertEquals(listOf(SteamCdn.AKAMAI_HOST), SteamCdn.hosts(SteamCdn.AKAMAI))
    }

    @Test
    fun `the manifest's component line is found`() {
        val manifest = """
            "linuxarm64"
            {
            ${'\t'}"version"${'\t'}${'\t'}"1700000000"
            ${'\t'}"bins_linuxarm64_linuxarm64"
            ${'\t'}{
            ${'\t'}${'\t'}"file"${'\t'}${'\t'}"bins_linuxarm64_linuxarm64.zip.5f1e0d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e"
            ${'\t'}${'\t'}"size"${'\t'}${'\t'}"104857600"
            ${'\t'}${'\t'}"sha2"${'\t'}${'\t'}"abc"
            ${'\t'}}
            }
        """.trimIndent()
        assertEquals(
            "bins_linuxarm64_linuxarm64.zip.5f1e0d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e",
            SteamCdnProbe.firstComponent(manifest),
        )
    }

    @Test
    fun `a manifest without components names none`() {
        assertNull(SteamCdnProbe.firstComponent("garbage"))
        // A partial download cut mid-name is not a component either.
        assertNull(SteamCdnProbe.firstComponent("\"file\"\t\"bins_linuxarm64_linuxarm64.zip.5f1e\""))
    }

    @Test
    fun `no recommendation until two edges have been measured`() {
        val ok = { host: String, rate: Long -> SteamCdnProbe.Edge(host, true, 200, 300L, rate, null) }
        assertNull(SteamCdnProbe.recommendation(emptyList()))
        assertNull(SteamCdnProbe.recommendation(listOf(ok(SteamCdn.FASTLY_HOST, 4_000_000))))
        // One edge unreachable: pinning the other trades a fallback for a failure.
        val down = SteamCdnProbe.Edge(SteamCdn.AKAMAI_HOST, false, 0, -1L, 0L, "timeout")
        assertNull(SteamCdnProbe.recommendation(listOf(ok(SteamCdn.FASTLY_HOST, 4_000_000), down)))
    }

    @Test
    fun `the clearly faster edge is recommended`() {
        val fast = SteamCdnProbe.Edge(SteamCdn.AKAMAI_HOST, true, 200, 300L, 4_000_000, null)
        val slow = SteamCdnProbe.Edge(SteamCdn.FASTLY_HOST, true, 200, 300L, 500_000, null)
        assertEquals(SteamCdn.AKAMAI, SteamCdnProbe.recommendation(listOf(slow, fast)))
    }

    @Test
    fun `two edges at similar speeds leave the choice automatic`() {
        val a = SteamCdnProbe.Edge(SteamCdn.AKAMAI_HOST, true, 200, 300L, 4_000_000, null)
        val b = SteamCdnProbe.Edge(SteamCdn.FASTLY_HOST, true, 200, 300L, 3_000_000, null)
        assertNull(SteamCdnProbe.recommendation(listOf(a, b)))
    }

    @Test
    fun `rates read as a human would say them`() {
        assertEquals("", SteamCdnProbe.rateToString(0))
        assertEquals("512 B/s", SteamCdnProbe.rateToString(512))
        assertEquals("1023 KB/s", SteamCdnProbe.rateToString(1_048_576 - 512))
        assertEquals("3.1 MB/s", SteamCdnProbe.rateToString(3_250_539))
    }

    @Test
    fun `the choices the menu shows are exactly the values the prefs accept`() {
        // SteamCdn.set stores anything in choices and reads anything else back as AUTO; this pins
        // the invariant the menu and the storage rely on.
        assertTrue(SteamCdn.AUTO in SteamCdn.choices)
        assertTrue(SteamCdn.FASTLY in SteamCdn.choices)
        assertTrue(SteamCdn.AKAMAI in SteamCdn.choices)
        assertFalse("official" in SteamCdn.choices)
    }
}
