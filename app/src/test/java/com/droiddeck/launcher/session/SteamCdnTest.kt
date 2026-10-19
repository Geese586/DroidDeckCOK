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

    @Test
    fun `edges come back fastest first, silent ones last`() {
        val slow = SteamCdnProbe.Edge(SteamCdn.FASTLY_HOST, true, 200, 300L, 900_000, null)
        val fast = SteamCdnProbe.Edge(SteamCdn.AKAMAI_HOST, true, 200, 300L, 6_000_000, null)
        val dead = SteamCdnProbe.Edge(SteamCdn.FASTLY_HOST, false, 0, -1L, 0L, SteamCdnProbe.TIMED_OUT)
        assertEquals(
            listOf(fast, slow, dead),
            SteamCdnProbe.ranked(listOf(dead, slow, fast)),
        )
        // A budget of nothing measures nothing, and says so rather than throwing.
        assertEquals(emptyList<SteamCdnProbe.Edge>(), SteamCdnProbe.measureAll(emptyList(), 1_000))
    }

    @Test
    fun `edges are named by network, not by DNS`() {
        assertEquals("Fastly", SteamCdnProbe.label(SteamCdn.FASTLY_HOST))
        assertEquals("Akamai", SteamCdnProbe.label(SteamCdn.AKAMAI_HOST))
        assertEquals("edge.example.com", SteamCdnProbe.label("edge.example.com"))
    }

    @Test
    fun `a measurement outranks the setting for one run only`() {
        try {
            SteamCdn.setOverride(SteamCdn.AKAMAI)
            assertEquals(SteamCdn.AKAMAI, SteamCdn.resolved(SteamCdn.FASTLY))
            assertEquals(SteamCdn.AKAMAI, SteamCdn.resolved(SteamCdn.AUTO))
            // The next run that measures nothing goes back to the setting - the measurement is
            // never a standing preference, and an unknown choice is the same as none.
            SteamCdn.setOverride(SteamCdn.AUTO)
            assertEquals(SteamCdn.FASTLY, SteamCdn.resolved(SteamCdn.FASTLY))
            SteamCdn.setOverride("edge.example.com")
            assertNull(SteamCdn.guestValue(SteamCdn.resolved(SteamCdn.AUTO)).takeIf { it.isNotEmpty() })
        } finally {
            SteamCdn.setOverride(SteamCdn.AUTO)
        }
    }

    @Test
    fun `a measurement keeps its numbers for the run it was taken for`() {
        try {
            val ranking = "1. Fastly - 3.2 MB/s \u00b7 2. Akamai - 1.1 MB/s"
            // Nothing measured yet, so the loading screen has nothing to show.
            assertEquals("", SteamCdn.report())
            SteamCdn.setMeasured(SteamCdn.FASTLY, ranking)
            assertEquals(SteamCdn.FASTLY, SteamCdn.resolved(SteamCdn.AKAMAI))
            assertEquals(ranking, SteamCdn.report())
            // An edge picked by hand carries no numbers: the ranking belongs to the run that
            // measured it, and a later install must not be shown the old one's reasons.
            SteamCdn.setOverride(SteamCdn.AUTO)
            assertEquals("", SteamCdn.report())
            // So does a run that measured nothing, and an edge nobody measured is no edge at all.
            SteamCdn.setMeasured("edge.example.com", ranking)
            assertEquals(SteamCdn.AUTO, SteamCdn.resolved(SteamCdn.AUTO))
            assertEquals(ranking, SteamCdn.report())
        } finally {
            SteamCdn.setOverride(SteamCdn.AUTO)
        }
    }

    /**
     * A lead short of a pin still decides which edge is tried first. 1.0 MB/s against 675 KB/s is
     * 1.5x, and the rule that only acted on 2x made the script download through the slower edge
     * while the report named the faster one. The measurement now reaches the script as an order -
     * fastest first, the other kept as the fallback - and a pin is only for a lead in another
     * league, or for the user's own choice.
     */
    @Test
    fun aMeasuredLeadShortOfAPinStillDecidesTheOrder() {
        try {
            SteamCdn.setMeasured(
                SteamCdn.AUTO, "ranking",
                listOf(SteamCdn.AKAMAI_HOST, SteamCdn.FASTLY_HOST),
            )
            assertEquals(
                listOf(SteamCdn.AKAMAI_HOST, SteamCdn.FASTLY_HOST),
                SteamCdn.orderForSession(),
            )
            assertEquals(
                "client-update.akamai.steamstatic.com client-update.fastly.steamstatic.com",
                SteamCdn.orderValue(SteamCdn.orderForSession()),
            )
            // The order lives for one run, like the ranking it came from.
            SteamCdn.setOverride(SteamCdn.AUTO)
            assertTrue(SteamCdn.orderForSession().isEmpty())
        } finally {
            SteamCdn.setOverride(SteamCdn.AUTO)
        }
    }

    /**
     * An order is only passed when both edges are in it: one host alone would be a pin without the
     * reason a pin needs, and the script's own order at least switches when a host refuses a file.
     * Anything the app does not recognise is dropped rather than forwarded to the guest.
     */
    @Test
    fun anOrderNeedsBothEdgesOrItIsNotPassed() {
        assertEquals("", SteamCdn.orderValue(emptyList()))
        assertEquals("", SteamCdn.orderValue(listOf(SteamCdn.FASTLY_HOST)))
        assertEquals("", SteamCdn.orderValue(listOf("edge.example.com", SteamCdn.AKAMAI_HOST)))
        assertEquals(
            "client-update.fastly.steamstatic.com client-update.akamai.steamstatic.com",
            SteamCdn.orderValue(listOf(SteamCdn.FASTLY_HOST, SteamCdn.AKAMAI_HOST)),
        )
    }

    /**
     * The menu the loading screen shows while a client download runs: the edges this run measured,
     * fastest first, and both of them in the script's order when it measured nothing. "Automatic"
     * is not a row of this list - it is the absence of a tap - so the list never contains it.
     */
    @Test
    fun theMenuOffersTheMeasuredEdgesFastestFirst() {
        try {
            SteamCdn.setMeasured(
                SteamCdn.AUTO, "1. Akamai 1.0 MB/s", listOf(SteamCdn.AKAMAI_HOST, SteamCdn.FASTLY_HOST),
            )
            assertEquals(listOf(SteamCdn.AKAMAI, SteamCdn.FASTLY), SteamCdn.choicesForSession())

            SteamCdn.setOverride(SteamCdn.AUTO)
            assertEquals(listOf(SteamCdn.FASTLY, SteamCdn.AKAMAI), SteamCdn.choicesForSession())
        } finally {
            SteamCdn.setOverride(SteamCdn.AUTO)
        }
    }
}
