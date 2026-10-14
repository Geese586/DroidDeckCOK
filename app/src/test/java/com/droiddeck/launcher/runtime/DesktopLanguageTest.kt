package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Setting the desktop's language, and telling whose it is afterwards. The write has to land on
 * exactly what KDE's own settings page leaves behind - a device has been read to get that text (see
 * the archive, section Q) - and the dump's reading of it has to keep saying "the user's" once the
 * user has picked another language, because that is what stops the app setting it again.
 *
 * Plain JVM: the write takes a rootfs, not a Context, so no Android image has to be fetched.
 */
class DesktopLanguageTest {
    private lateinit var work: File
    private lateinit var root: File

    @Before fun setUp() {
        work = Files.createTempDirectory("DesktopLanguage").toFile()
        root = File(work, "rootfs").apply { mkdirs() }
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun localerc() = File(root, "root/.config/plasma-localerc")

    @Test fun theFileWrittenIsTheOneKdeLeavesBehind_byteForByte() {
        assertTrue(DesktopLanguage.write(root))
        // 241 bytes and the same text KDE's settings page wrote on the device, [Formats] and all.
        assertEquals(241, localerc().length())
        assertEquals(DesktopLanguage.TRADITIONAL_CHINESE, localerc().readText())
        assertEquals("zh_TW", DesktopLanguage.currentLanguage(root))
        assertEquals("zh_TW", DesktopLanguage.recordedLanguage(root))
    }

    @Test fun theWriteMakesTheConfigDirectoryWhenTheDesktopHasNeverRun() {
        assertFalse(File(root, "root/.config").exists())
        assertTrue(DesktopLanguage.write(root))
        assertTrue(File(root, "root/.config").isDirectory)
    }

    @Test fun aLanguageTheUserPicksAfterwardsReadsAsTheirsAndIsLeftAlone() {
        DesktopLanguage.write(root)
        // What KDE's settings write when the user picks Japanese there, later.
        localerc().writeText("[Formats]\nLANG=ja_JP.UTF-8\n\n[Translations]\nLANGUAGE=ja\n")
        assertEquals("ja", DesktopLanguage.currentLanguage(root))
        // Our note stays at what we set - it is how "the user changed it" is known at all.
        assertEquals("zh_TW", DesktopLanguage.recordedLanguage(root))
        assertEquals(
            "the user's own - picked in KDE's settings after this app set zh_TW",
            DesktopLangExport.whoseLanguage(DesktopLanguage.currentLanguage(root), DesktopLanguage.recordedLanguage(root)),
        )
    }

    @Test fun aFileWithNoTranslationsSectionNamesNoLanguage() {
        // An earlier build's own output: formats set, and no language anywhere.
        localerc().apply { parentFile?.mkdirs(); writeText("[Locale]\nLanguage=zh_TW\n") }
        assertNull(DesktopLanguage.currentLanguage(root))
    }

    @Test fun withNothingSetNeitherSideClaimsALanguage() {
        assertNull(DesktopLanguage.currentLanguage(root))
        assertNull(DesktopLanguage.recordedLanguage(root))
        assertEquals("nobody's - the desktop comes up in the system's own",
            DesktopLangExport.whoseLanguage(null, null))
    }

    @Test fun aRootfsThatIsNotThereIsRefusedRatherThanCreated() {
        assertFalse(DesktopLanguage.write(File(work, "not-installed")))
    }
}
