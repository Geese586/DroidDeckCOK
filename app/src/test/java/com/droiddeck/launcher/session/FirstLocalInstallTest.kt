package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** The drivers tab's export: one zip per driver, a bundle's two halves in one of them. */
    private fun driverZips(): File = dir("GPUDrivers").apply {
        put("android-Mesa_Turnip_v26.3.0.zip", 1024)
        put("linux-Mesa_Turnip_v26.3.0-Linux.zip", 2048)
        put("bundle-DD-Turnip_0.1.0.zip", 4096)
        put("drivers.txt", 64)
    }

    private fun parts(): List<FirstLocalInstall.Part> = FirstLocalInstall.found(folder).map { it.part }

    @Test fun theWholeFolderReadsAsTheDocumentedOrder() {
        folder.put("linuxfs-r9.tar.zst")
        folder.put("proton-experimental-arm64-25502785.tar.zst")
        steamClient()
        folder.put("desktop.tar.zst", 64)
        dir("fonts").put("LXGWWenKai-Regular.ttf")
        folder.put("GE-Proton11-7-aarch64.tar.gz", 128)
        driverZips()
        assertEquals(
            listOf(
                FirstLocalInstall.Part.RUNTIME, FirstLocalInstall.Part.PROTON,
                FirstLocalInstall.Part.CLIENT, FirstLocalInstall.Part.DESKTOP,
                FirstLocalInstall.Part.FONTS, FirstLocalInstall.Part.EXTRAS,
                FirstLocalInstall.Part.GPU,
            ),
            parts(),
        )
    }

    @Test fun everyThirdPartyProtonIsAStepOfItsOwn() {
        // The three the project points at, which is what the folder the user fills holds: they are
        // three builds, not three copies of one, so all three are steps and each is its own.
        folder.put("GE-Proton11-7-aarch64.tar.gz", 128)
        folder.put("proton-cachyos-11.0-20261005-slr-arm64.tar.xz", 96)
        folder.put("DroidDeck-Proton-11-7.5-aarch64.tar.gz", 160)
        // And Valve's own, which is the seed and not one of them.
        folder.put("proton-experimental-arm64-25502785.tar.zst", 32)
        val found = FirstLocalInstall.found(folder)
        assertEquals(
            listOf(FirstLocalInstall.Part.PROTON, FirstLocalInstall.Part.EXTRAS,
                FirstLocalInstall.Part.EXTRAS, FirstLocalInstall.Part.EXTRAS),
            found.map { it.part },
        )
        assertEquals(
            listOf("ge", "cachyos", "droiddeck"),
            found.filter { it.part == FirstLocalInstall.Part.EXTRAS }.map { it.tool?.id },
        )
    }

    @Test fun aThirdPartyProtonThatWentInIsNotReportedAsAnAbsentTool() {
        // The installer answers null for "the build went in", while the step's own tool is null
        // only when found() never made the step at all - and those two mean opposite things. A "?:"
        // over the pair read every success as the second, which is what put
        // "安装失败：no compatibility tool matches that archive" under three Protons the run's own
        // log had just recorded as "droiddeck-proton-extra: installed ...". Held apart here so the
        // fold cannot come back.
        val tool = ProtonExtras.tools.first()
        assertNull(FirstLocalInstall.extrasProblem(tool, null))
        assertEquals(
            "That file cannot be read",
            FirstLocalInstall.extrasProblem(tool, "That file cannot be read"),
        )
        assertEquals(
            FirstLocalInstall.NO_COMPATIBILITY_TOOL,
            FirstLocalInstall.extrasProblem(null, null),
        )
    }

    @Test fun aRunWhereEverythingWentInHasNothingToReport() {
        // Ten steps, all of them in: a dialog listing ten "installed" lines tells a user who just
        // watched the run nothing, and buries the one line that would matter on a run that half
        // worked - so the caller shows nothing at all, and this is what it asks.
        val outcomes = (1..10).map { step(null) }
        assertTrue(!FirstLocalInstall.worthReporting(outcomes, null, false))
        assertTrue(FirstLocalInstall.failedSteps(outcomes).isEmpty())
    }

    @Test fun theReportNamesTheStepsThatFailedAndOnlyThose() {
        // "which step failed" is the whole question a report exists to answer, so the failing ones
        // come out by their 1-based number, in the order they ran, with the rest left out.
        val outcomes = listOf(step(null), step("boom"), step(null), step("nope"), step(null))
        assertTrue(FirstLocalInstall.worthReporting(outcomes, null, false))
        assertEquals(listOf(2, 4), FirstLocalInstall.failedSteps(outcomes))
    }

    @Test fun aStepThatWentQuietIsWorthARunEvenWhenItFinished() {
        // The watchdog's step is not a failure of its own - the run may still have gone on to
        // finish - but it is the one thing the log cannot say for itself, so it is reported.
        val outcomes = listOf(step(null), step(null))
        assertTrue(FirstLocalInstall.worthReporting(outcomes, null, true))
        assertTrue(FirstLocalInstall.failedSteps(outcomes).isEmpty())
    }

    @Test fun anErrorOutsideTheStepsIsWorthAReportWithNoOutcomeToItsName() {
        // An Error thrown out of a step takes the step's own outcome with it, so the report has
        // nothing but the message - and still has to open.
        assertTrue(FirstLocalInstall.worthReporting(emptyList(), "OutOfMemoryError", false))
        assertTrue(FirstLocalInstall.failedSteps(emptyList()).isEmpty())
    }

    private fun step(problem: String?): FirstLocalInstall.Outcome = FirstLocalInstall.Outcome(
        FirstLocalInstall.Found(FirstLocalInstall.Part.FONTS, File(folder, "fonts")), problem)

    @Test fun aFolderOfExportedDriversIsOneStepWhateverIsInIt() {
        // Three drivers, one of them a bundle: the step is the folder, so a run of them is one line
        // of progress rather than three, and it is the folder that is handed to the install.
        val drivers = driverZips()
        val found = FirstLocalInstall.found(folder)
        assertEquals(listOf(FirstLocalInstall.Part.GPU), found.map { it.part })
        assertEquals(drivers.absolutePath, found.single().source.absolutePath)
    }

    @Test fun aFolderOfDriversPickedOnItsOwnIsTheDriverStep() {
        // What picking FirstLocalInstall\GPUDrivers itself looks like.
        val drivers = driverZips()
        assertEquals(listOf(FirstLocalInstall.Part.GPU),
            FirstLocalInstall.found(drivers).map { it.part })
    }

    @Test fun onlyTheZipsTheExportWroteAreDrivers() {
        // A folder of drivers alongside whatever else was downloaded into it: the zips that are not
        // one of the three names are not read at all, so a folder of nothing but those is no step -
        // an id nothing can be sure of is how a first-time install comes to offer itself forever.
        dir("GPUDrivers").apply {
            put("Mesa_Turnip_v26.3.0.zip", 1024)
            put("WN-Turnip-1.19.zip", 1024)
            put("drivers.txt", 64)
            put("readme.md", 64)
        }
        assertTrue(FirstLocalInstall.found(folder).isEmpty())
    }

    @Test fun theRoomAFolderNeedsIsCountedFromWhatIsInIt() {
        // The archives themselves, at the multiple they unpack by, with the flat headroom over the
        // lot - which is what a run that cannot finish is refused with. Only the folder's own bytes
        // are added, so the difference between two folders is theirs and nothing else's.
        val flat = FirstLocalInstall.spaceNeeded(emptyList())
        folder.put("linuxfs-r9.tar.zst", 1000)
        folder.put("desktop.tar.zst", 2000)
        assertEquals(3000L * 3, FirstLocalInstall.spaceNeeded(FirstLocalInstall.found(folder)) - flat)
        // A folder-shaped part counts every file it holds, not the folder.
        driverZips()
        val before = FirstLocalInstall.spaceNeeded(FirstLocalInstall.found(folder))
        File(folder, "GPUDrivers").put("android-More.zip", 500)
        assertEquals(500L * 3, FirstLocalInstall.spaceNeeded(FirstLocalInstall.found(folder)) - before)
    }

    @Test fun theDesktopArchiveIsNeitherTheRuntimeNorTheSeed() {
        folder.put("desktop.tar.zst")
        assertEquals(listOf(FirstLocalInstall.Part.DESKTOP), parts())
    }

    @Test fun theDesktopTranslationsAreTheirOwnStepRightAfterTheDesktop() {
        // Upstream's desktop ships English only, and the translations ride beside it as their own
        // archive: a device whose desktop already went in must still be offered them, so the step
        // exists whether or not the desktop archive is in the same folder.
        val pack = folder.put("desktop-l10n.tar.zst", 16)
        folder.put("desktop-kde.tar.zst", 64)
        assertEquals(
            listOf(FirstLocalInstall.Part.DESKTOP, FirstLocalInstall.Part.L10N),
            parts(),
        )
        // And on its own, which is the folder of someone who installed the desktop before the pack
        // existed: the step is still there, and with it the play page's button.
        File(folder, "desktop-kde.tar.zst").delete()
        assertEquals(listOf(FirstLocalInstall.Part.L10N), parts())
        assertEquals(pack.absolutePath, FirstLocalInstall.found(folder).single().source.absolutePath)
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
