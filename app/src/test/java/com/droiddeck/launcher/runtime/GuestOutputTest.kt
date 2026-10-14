package com.droiddeck.launcher.runtime

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The deadline on a guest command's output - the thing that turns a stuck install into a reported
 * one. A child of ours that goes quiet used to be waited on for ever, because the read ends at
 * EOF and a wedged proot child never gets there; here it is a real second JVM that says nothing
 * and never exits, and the watchdog has to kill it and say so.
 *
 * Plain JVM: the read takes a process and an already-open log, not a Context.
 */
class GuestOutputTest {
    private lateinit var work: File
    private lateinit var log: File

    @Before fun setUp() {
        work = Files.createTempDirectory("GuestOutput").toFile()
        log = File(work, "guest.log")
    }

    @After fun tearDown() { work.deleteRecursively() }

    /** A second JVM running [GuestOutputTestChild], as itself - the child every machine here has. */
    private fun child(mode: String): Process {
        val home = File(System.getProperty("java.home"), "bin")
        val java = File(home, "java").takeIf { it.isFile } ?: File(home, "java.exe")
        return ProcessBuilder(java.path, "-cp", System.getProperty("java.class.path"),
            "com.droiddeck.launcher.runtime.GuestOutputTestChild", mode)
            .redirectErrorStream(true)
            .start()
    }

    private fun openLog(): PrintWriter = PrintWriter(log)

    @Test fun everyLineIsHandedOver_andTheExitStatusIsTheChilds() {
        val lines = mutableListOf<String>()
        val result = GuestOutput.read(child("print"), "printing child", openLog(), 5_000) { lines += it }

        assertEquals(listOf("first line", "second line"), lines)
        assertFalse(result.timedOut)
        assertEquals(0, result.status)
        assertTrue(result.ok)
        // And the same lines are in the file, which is what leaves the device with a report.
        assertTrue(log.readText(), log.readText().contains("second line"))
    }

    @Test fun aChildThatGoesQuietIsKilledAndReported() {
        val child = child("silent")
        val result = GuestOutput.read(child, "silent child", openLog(), 1_500) { }

        assertTrue(result.timedOut)
        // It never exited, so there is no status to report - and the silence is what we measured.
        assertEquals(-1, result.status)
        assertTrue("silent for ${result.silentFor} ms", result.silentFor >= 1_500)
        // The point of the exercise: the child is gone, rather than left waiting behind the app.
        val gone = runCatching { child.exitValue() }.isSuccess
        assertTrue("child still alive after the timeout", gone)
        assertTrue(log.readText(), log.readText().contains("TIMEOUT"))
    }
}
