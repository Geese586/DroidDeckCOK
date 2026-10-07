package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a FirstLocalInstall folder reads as. The order is the contract the run depends on - the
 * runtime first, the fonts after the desktop - and the shapes tested are the ones the project lays
 * out, so a folder the class's own documentation describes has to come out as the steps it says.
 */
class FirstLocalInstallTest {
    private lateinit var folder: File

    @Before fun setUp() { folder = Files.createTempDirectory("FirstLocalInstall").toFile() }
    @After fun tearDown() { folder.deleteRecursively() }

    private fun dir(name: String): File = File(folder, name).apply { mkdirs() }

    /** A file of [size] bytes, so what is written is a real archive as far as the reading is concerned. */
    private fun File.put(name: String, size: Int = 8): File = File(this, name).apply { writeBytes(ByteArray(size)) }

    /** A component as Valve's manifest names it, plus the manifest itself - what the client step sees. */
    private fun steamClient(): File = dir("steam-client").apply {
        put("bins_linuxarm64_linuxarm64.zip." + "a".repeat(40))
        put("steam_client_steamdeck_publicbeta_linuxarm64", 32)
    }

    private fun parts(): List<FirstLocalInstall.Part> = FirstLocalInstall.found(folder).map { it.part }

    @Test fun theWholeFolderReadsAsTheDocumentedOrder() {
        folder.put("linuxfs-r9.tar.zst")
        folder.put("proton-experimental-arm64-25502785.tar.zst")
        steamClient()
        folder.put("desktop.tar.zst", 64)
        dir("fonts").put("LXGWWenKai-Regular.ttf")
        folder.put("GE-Proton11-7-aarch64.tar.gz", 128)
        assertEquals(
            listOf(
                FirstLocalInstall.Part.RUNTIME, FirstLocalInstall.Part.PROTON,
                FirstLocalInstall.Part.CLIENT, FirstLocalInstall.Part.DESKTOP,
                FirstLocalInstall.Part.FONTS, FirstLocalInstall.Part.EXTRAS,
            ),
            parts(),
        )
    }

    @Test fun theDesktopArchiveIsNeitherTheRuntimeNorTheSeed() {
        folder.put("desktop.tar.zst")
        assertEquals(listOf(FirstLocalInstall.Part.DESKTOP), parts())
    }

    @Test fun aFontsFolderPickedOnItsOwnIsTheFontStepAndNothingElse() {
        // What picking FirstLocalInstall\fonts itself looks like: the folder read as its contents.
        folder.put("LXGWWenKai-Regular.ttf", 16)
        folder.put("LXGWWenKaiTC-Regular.ttf", 12)
        folder.put("LxgwWenKai.txt", 43)
        assertEquals(listOf(FirstLocalInstall.Part.FONTS), parts())
        val found = FirstLocalInstall.found(folder).single()
        assertEquals(folder.absolutePath, found.source.absolutePath)
        assertEquals(2, Fonts.fontFiles(folder).size)
    }

    @Test fun everyFontInTheFolderCountsWhateverItIsCalled() {
        // The step is about a folder, not about Chinese: a name never decides anything, and neither
        // does the language the font draws. Japanese, Korean and a file that is not a font at all,
        // in an extension in either case - what comes out is the four font files, by extension.
        val fonts = dir("fonts").apply {
            put("LXGWWenKai-Regular.ttf")
            put("NotoSansJP-Regular.ttf")
            put("NanumGothic.ttf")
            put("SourceHanSansKR-Regular.OTF")
            put("readme.md", 43)
            put("checksums.txt", 43)
        }
        assertEquals(
            listOf("LXGWWenKai-Regular.ttf", "NanumGothic.ttf", "NotoSansJP-Regular.ttf",
                "SourceHanSansKR-Regular.OTF"),
            Fonts.fontFiles(fonts).map { it.name },
        )
        assertEquals(listOf(FirstLocalInstall.Part.FONTS), parts())
    }

    @Test fun aFontsFolderOfNothingButNotesIsNoStep() {
        // The folder the fonts were downloaded into carries a note or two; they are not fonts.
        dir("fonts").put("LxgwWenKai.txt", 43)
        assertTrue(FirstLocalInstall.found(folder).isEmpty())
    }

    @Test fun oneArchivePerThingAndTheSameOneEveryRun() {
        folder.put("desktop-r2.tar.zst", 32)
        folder.put("desktop.tar.zst", 16)
        val found = FirstLocalInstall.found(folder)
        assertEquals(1, found.size)
        assertEquals("desktop-r2.tar.zst", found.single().source.name)
    }

    @Test fun anEmptyFolderHoldsNothing() {
        assertTrue(FirstLocalInstall.found(folder).isEmpty())
    }
}
