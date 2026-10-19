package com.droiddeck.launcher.session

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file a tap on the loading screen writes and the install script reads (DroidDeckCOK).
 *
 * No Android here: the rule is about the file's contents and lifetime, which is what the guest
 * actually depends on. Reading it needs a Context only because the session root does.
 */
class SteamLineSwitchTest {
    private val dir: File =
        File(System.getProperty("java.io.tmpdir"), "steam-line-" + System.nanoTime()).apply { mkdirs() }

    private val file: File get() = File(dir, SteamLineSwitch.FILE)

    @After
    fun cleanUp() {
        file.delete()
        File(dir, SteamLineSwitch.FILE + ".tmp").delete()
        dir.delete()
    }

    @Test
    fun `a tap is what the guest reads back`() {
        SteamLineSwitch.write(file, SteamCdn.AKAMAI)
        assertEquals(SteamCdn.AKAMAI, SteamLineSwitch.read(file))
        assertEquals("akamai", file.readText().trim())
    }

    @Test
    fun `automatic removes the file instead of naming it`() {
        SteamLineSwitch.write(file, SteamCdn.FASTLY)
        assertTrue(file.isFile)
        SteamLineSwitch.write(file, SteamLineSwitch.AUTO)
        assertFalse(file.exists())
        assertEquals(SteamLineSwitch.AUTO, SteamLineSwitch.read(file))
    }

    /**
     * The guest only knows two edges. Anything else - a hand-edited file, a half-written name, an
     * obsolete one - has to read as "decide it yourself" rather than as an edge the script will
     * fail to resolve.
     */
    @Test
    fun `an unknown or empty name reads as automatic`() {
        file.writeText("edge.example.com\n")
        assertEquals(SteamLineSwitch.AUTO, SteamLineSwitch.read(file))
        file.writeText("")
        assertEquals(SteamLineSwitch.AUTO, SteamLineSwitch.read(file))
        file.writeText("akamai\n\n")
        assertEquals(SteamCdn.AKAMAI, SteamLineSwitch.read(file))
    }

    /** Written through a temporary name: the guest polls this file while a download is running. */
    @Test
    fun `no half-written file is ever left behind`() {
        SteamLineSwitch.write(file, SteamCdn.FASTLY)
        assertFalse(File(dir, SteamLineSwitch.FILE + ".tmp").exists())
        assertEquals(listOf(SteamLineSwitch.FILE), dir.list()!!.toList())
    }
}
