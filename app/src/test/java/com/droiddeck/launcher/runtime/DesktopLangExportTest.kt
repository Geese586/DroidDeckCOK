package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the language dump carries. The zip is the only thing that can be handed over when the
 * desktop comes up in the wrong language, so what it says about the device has to be right: the
 * markers, how many catalogues each locale holds, and the config Plasma actually reads.
 *
 * The report is built from a rootfs and a [DesktopLangExport.Facts] rather than a Context, so it
 * runs as a plain JVM test - a Robolectric one would have to fetch Android images first.
 */
class DesktopLangExportTest {
    private lateinit var work: File
    private lateinit var root: File

    @Before fun setUp() {
        work = Files.createTempDirectory("DesktopLangExport").toFile()
        root = File(work, "root").apply { mkdirs() }
        // A desktop in - KWin there and the marker carrying the pinned tag - with our l10n marker
        // absent: the state this dump exists to tell apart from one where the pack went in.
        file("usr/bin/kwin_wayland")
        file(".droiddeck-pkg-desktop-kde", "desktop-kde-r2")
        file("root/.config/plasma-localerc", "[Translations]\nLANGUAGE=zh_TW\n")
        // glibc's own catalogue is what the runtime ships; kwin.mo could only be the l10n payload.
        file("usr/share/locale/zh_TW/LC_MESSAGES/glib20.mo", "stock")
        file("usr/share/locale/zh_TW/LC_MESSAGES/kwin.mo", "kde")
        file("usr/share/locale/zh_CN/LC_MESSAGES/glib20.mo", "stock")
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun file(relative: String, body: String = ""): File =
        File(root, relative).apply { parentFile?.mkdirs(); writeText(body) }

    /** What the app would fill in, with the rootfs above under it. */
    private fun facts(desktopInstalled: Boolean = true, l10nInstalled: Boolean = false) =
        DesktopLangExport.Facts(
            packageLine = "4.0 (13), COK 0.4.0.1",
            deviceLine = "Test Device, Android 14",
            desktopTag = "desktop-kde-r2",
            desktopInstalled = desktopInstalled,
            l10nInstalled = l10nInstalled,
            scripts = listOf(
                DesktopLangExport.Facts.Script("droiddeck-desktop", "5123 B, sha256 aabb", "5123 B", true),
            ),
            folder = "/sdcard/DroidDeck/FirstLocalInstall",
            folderEntries = listOf("file desktop-l10n.tar.zst  667854 B", "file linuxfs.tar.zst  790000000 B"),
            folderSteps = listOf("DESKTOP  desktop-kde.tar.zst", "L10N  desktop-l10n.tar.zst"),
        )

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
    }

    @Test fun theReportSaysWhatIsInAndCountsOnlyTheKdeCataloguesAsThePayloads() {
        val summary = DesktopLangExport.summary(root, facts())
        assertTrue(summary, summary.contains("desktopInstalled()  = true"))
        assertTrue(summary, summary.contains("l10nInstalled()     = false"))
        // The two counts: glibc's one is in both, the payload's names only in one.
        assertTrue(summary, summary.contains("usr/share/locale/zh_TW/LC_MESSAGES: 2 .mo, of them KDE: 1"))
        assertTrue(summary, summary.contains("usr/share/locale/zh_CN/LC_MESSAGES: 1 .mo, of them KDE: 0"))
        // The config itself, inline, so the answer is often in this one file.
        assertTrue(summary, summary.contains("[Translations]\nLANGUAGE=zh_TW"))
        // And whose language it is: a config the app never set reads as the device's own.
        assertTrue(summary, summary.contains("plasma-localerc LANGUAGE = zh_TW"))
        assertTrue(summary, summary.contains("this app last set it to  = (never)"))
        assertTrue(summary, summary.contains("so the language is       = the device's own"))
        // And that the session script is not the one setting it: only a line that would set a
        // language counts, not the name of the file the comments explain it does not write.
        assertTrue(summary, summary.contains("desktop script writes the language: false"))
        // And what the installer made of the folder, so a pack it never saw is visible.
        assertTrue(summary, summary.contains("desktop-l10n.tar.zst"))
        assertTrue(summary, summary.contains("L10N  desktop-l10n.tar.zst"))
    }

    @Test fun aConfigThatIsNotThereIsNamedAbsentRatherThanLeftOut() {
        val summary = DesktopLangExport.summary(root, facts(desktopInstalled = false))
        assertTrue(summary, summary.contains("desktopInstalled()  = false"))
        assertTrue(summary, summary.contains("### root/.config/kdeglobals - ABSENT"))
    }

    @Test fun theZipCarriesTheConfigAtItsRootfsPathBesideTheTwoReports() {
        val zip = File(work, "dump.zip")
        DesktopLangExport.write(root, facts(), zip) {}
        val entries = entries(zip)
        assertTrue(entries.keys.toString(), entries.containsKey("README.txt"))
        assertTrue(entries.keys.toString(), entries.containsKey("summary.txt"))
        assertTrue(entries.keys.toString(), entries.containsKey("locale-manifest.txt"))
        assertEquals("[Translations]\nLANGUAGE=zh_TW\n", entries["files/root/.config/plasma-localerc"])
        assertTrue(entries["locale-manifest.txt"]!!.contains("kwin.mo"))
        // The catalogues are listed, not carried: the manifest is what says they are in.
        assertTrue(entries.keys.toString(), entries.keys.none { it.endsWith(".mo") })
    }
}
