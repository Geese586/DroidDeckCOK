package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one-click install's log, which is the only thing that can come back from a device whose
 * install stopped. What has to hold: it appends rather than replacing (a second run must not throw
 * the first one's evidence away), it stays inside its cap however long a run is, and the copy that
 * leaves the device takes the guest commands' own logs with it - those are where a stuck step's
 * last words are.
 *
 * Plain JVM: the writer takes a file, not a Context, so nothing Android has to be downloaded.
 */
class FirstInstallLogTest {
    private lateinit var work: File
    private lateinit var logs: File

    @Before fun setUp() {
        work = Files.createTempDirectory("FirstInstallLog").toFile()
        logs = File(work, "logs").apply { mkdirs() }
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun installLog() = FirstInstallLog.fileIn(logs)

    @Test fun whatIsWrittenIsReadBack_andASecondRunDoesNotThrowTheFirstAway() {
        FirstInstallLog.writeTo(installLog(), "run one")
        FirstInstallLog.writeTo(installLog(), "run two")

        val text = installLog().readText()
        assertTrue(text, text.contains("run one"))
        assertTrue(text, text.contains("run two"))
        assertEquals("run two", FirstInstallLog.tailOf(installLog(), 1))
    }

    @Test fun theFileStaysInsideItsCap_andKeepsTheNewestLines() {
        val filler = "x".repeat(4096)
        // Past the cap by two times over, so the trim has to have happened at least twice.
        repeat((FirstInstallLog.MAX_BYTES / filler.length * 3).toInt()) {
            FirstInstallLog.writeTo(installLog(), filler)
        }
        FirstInstallLog.writeTo(installLog(), "the newest line")

        val text = installLog().readText()
        assertTrue("log is ${installLog().length()} B", installLog().length() <= FirstInstallLog.MAX_BYTES)
        assertTrue(text, text.startsWith("(earlier lines dropped"))
        // The end of the run is what a report needs, so the trim must never take that.
        assertTrue(text, text.trimEnd().endsWith("the newest line"))
    }

    @Test fun theCopyTakesTheGuestCommandsLogsToo() {
        FirstInstallLog.writeTo(installLog(), "the run")
        val tools = File(logs, SessionPaths.TOOLS_DIR)
        File(tools, "first-install-fonts.log").writeText("fc-cache said something")
        File(tools, "not-a-log.txt").writeText("left alone")
        val into = File(work, "picked").apply { mkdirs() }

        val written = FirstInstallLog.saveFrom(logs, into).map { it.name }.sorted()

        assertEquals(listOf("first-install-fonts.log", "first-install.log"), written)
        // The copy is beside the original rather than replacing it: the device keeps its log.
        assertTrue(installLog().isFile)
        assertEquals(
            "fc-cache said something",
            File(File(into, "DroidDeck-first-install"), "first-install-fonts.log").readText().trim(),
        )
    }
}
