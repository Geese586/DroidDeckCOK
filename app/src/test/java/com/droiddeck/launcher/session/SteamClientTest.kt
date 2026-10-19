package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.GuestOutput
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a folder-only install needs from the folder it is handed, which is Valve's manifest and
 * everything that manifest names.
 *
 * The manifest is what the guest verifies every package against, and the app has to judge the
 * folder by the same name the guest looks for - and, offline, by the same list the guest builds out
 * of it. Getting either wrong is quiet: call a folder ready when it is not and the switch that keeps
 * the install off the network is never set, so the script goes back to Valve's CDN for the manifest
 * it was promised - a download, on the one path that exists for a device that has no network to
 * download over. Call one that is not ready when it is, and a step fails on a folder the script
 * would have installed from.
 */
class SteamClientTest {
    private lateinit var folder: File

    @Before fun setUp() { folder = Files.createTempDirectory("steam-client").toFile() }
    @After fun tearDown() { folder.deleteRecursively() }

    private fun put(name: String): File = File(folder, name).apply { writeText("x") }

    /** The name the guest script tests the folder for; the app has to ask about the same one. */
    @Test fun theManifestIsTheOneTheScriptLooksFor() {
        assertEquals("steam_client_steamdeck_publicbeta_linuxarm64", SteamClient.manifestName())
    }

    @Test fun aFolderHoldingItIsReadyForAnOfflineInstall() {
        put(SteamClient.manifestName())
        assertTrue(SteamClient.hasManifest(folder))
    }

    @Test fun aFolderHoldingOnlyTheComponentsIsNot() {
        // Including the client's own package, the one component whose name starts the same way.
        put("bins_hardware_all.zip.c52878402b36211c3c356c9f4d672aed6e024512")
        put("steam_client_steamdeck_publicbeta_linuxarm64.zip.0123456789abcdef0123456789abcdef01234567")
        assertFalse(SteamClient.hasManifest(folder))
    }

    @Test fun neitherIsTheOtherBranchsCopyOrARenamedOne() {
        // The script reads one name: another branch's manifest, or the same file saved as text by
        // a browser, leaves it with nothing to verify against while the folder looks filled.
        put("steam_client_publicbeta_linuxarm64")
        assertFalse(SteamClient.hasManifest(folder))
        File(folder, "steam_client_publicbeta_linuxarm64").delete()
        put(SteamClient.manifestName() + ".txt")
        assertFalse(SteamClient.hasManifest(folder))
    }

    @Test fun anEmptyFolderIsNotEither() {
        assertFalse(SteamClient.hasManifest(folder))
    }

    @Test fun theInstallerIsNotGivenUpOnBeforeTwentyMinutes() {
        // The read of the installer's output kills it after a silence, and the silence a healthy
        // install can hold is minutes: the 344 MB inside proot are unpacked component by component,
        // and ten minutes over the lot is a device behaving normally. A deadline below that turns a
        // slow install into a failed one, which is the opposite of what it is for.
        assertTrue(SteamClient.SILENCE_MS > 20 * 60_000L)
        // And it is the long one of the two, not the one every other guest command gets.
        assertTrue(SteamClient.SILENCE_MS > GuestOutput.SILENCE_MS)
    }

    /**
     * Valve's manifest in miniature, with the shapes that decide the question: a component block
     * whose name ends in `_all` or `_linuxarm64_linuxarm64`, and three that do not - among them
     * Valve's own `bins_androidarm64_linuxarm64`, which carries a `file` just like the others and
     * is one `_linuxarm64` short of the pattern. The guest's awk filter excludes it, so this side
     * has to as well: including it would refuse a folder of all seventeen as incomplete.
     */
    private fun manifestText(newline: String = "\n") = listOf(
        "\"linuxarm64\"",
        "{",
        "\t\"version\"\t\t\"1791415817\"",
        "\t\"tenfoot_images_all\"",
        "\t{",
        "\t\t\"file\"\t\t\"tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc\"",
        "\t\t\"size\"\t\t\"6582204\"",
        "\t}",
        "\t\"bins_sdk_linuxarm64_linuxarm64\"",
        "\t{",
        "\t\t\"file\"\t\t\"bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784\"",
        "\t\t\"size\"\t\t\"15914519\"",
        "\t}",
        "\t\"bins_androidarm64_linuxarm64\"",
        "\t{",
        "\t\t\"file\"\t\t\"bins_androidarm64_linuxarm64.zip.92af42efdc993b514b346c4dde379ca0f25a81fb\"",
        "\t\t\"size\"\t\t\"17944844\"",
        "\t}",
        "\t\"runtime_steamrt_linuxarm64\"",
        "\t{",
        "\t\t\"file\"\t\t\"runtime_steamrt_linuxarm64.zip.e9edd7325258d74533681f158780da72e5c1c333\"",
        "\t}",
        "\t\"steam_linuxarm64\"",
        "\t{",
        "\t\t\"file\"\t\t\"steam_linuxarm64.zip.985cca1e87ad39aade1bbf9dbc351bad33a1fce3\"",
        "\t\t\"IsBootstrapperPackage\"\t\t\"1\"",
        "\t}",
        "}",
    ).joinToString(newline)

    private fun putManifest(newline: String = "\n") =
        File(folder, SteamClient.manifestName()).writeText(manifestText(newline))

    @Test fun onlyTheBlocksTheScriptInstallsAreComponents() {
        putManifest()
        assertEquals(
            listOf(
                "tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc",
                "bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784",
            ),
            SteamClient.manifestComponents(folder),
        )
    }

    @Test fun aManifestCopiedThroughWindowsParsesTheSame() {
        // A \r left on the end would be part of the name for anything reading this line by line.
        putManifest("\r\n")
        assertEquals(
            listOf(
                "tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc",
                "bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784",
            ),
            SteamClient.manifestComponents(folder),
        )
    }

    @Test fun whatTheFolderIsShortOfIsWhatItsOwnManifestNames() {
        putManifest()
        // Nothing beside it yet: the two the manifest names, and none of the three it does not.
        assertEquals(
            listOf(
                "tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc",
                "bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784",
            ),
            SteamClient.missingComponents(folder),
        )
        put("tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc")
        assertEquals(
            listOf("bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784"),
            SteamClient.missingComponents(folder),
        )
        put("bins_sdk_linuxarm64_linuxarm64.zip.ac752439168e8a4ab747592f124d6370c68b8784")
        assertTrue(SteamClient.missingComponents(folder).isEmpty())
    }

    @Test fun aFolderWithNoManifestHasNothingToBeShortOf() {
        // The gate in installFromDir hangs off this being empty: a reading this side cannot make
        // must never be the reason a folder the script could have installed from is turned away.
        put("tenfoot_images_all.zip.86419c7a56c12dd107b5e0d46f50c8a9b121f3cc")
        assertTrue(SteamClient.manifestComponents(folder).isEmpty())
        assertTrue(SteamClient.missingComponents(folder).isEmpty())
    }

    /**
     * The guest command opens with "env -i", which throws away the environment proot was handed:
     * anything the script itself reads has to ride the command line as a NAME=value element. The
     * offline switch spent its whole life set on the proot process instead - set faithfully on
     * every first-time install, read by no one, and every one of those installs downloaded the
     * client from Valve with the packages sitting in the folder beside it. These two pin the
     * entries themselves, because the channel they travel by is the fix.
     */
    @Test fun theOfflineSwitchRidesTheCommandLine() {
        assertEquals(listOf("BL_STEAM_LOCAL_ONLY=1"), SteamClient.guestEnvEntries(true, ""))
    }

    @Test fun anInstallThatMayDownloadSetsNoSwitch() {
        assertTrue(SteamClient.guestEnvEntries(false, "").isEmpty())
    }

    @Test fun aChosenEdgeRidesAlongAndAnAutomaticOneAddsNothing() {
        // The script reads any value it does not know as a host to use alone, so "automatic"
        // (an empty value) must never become an entry.
        assertEquals(listOf("BL_STEAM_LOCAL_ONLY=1", "BL_STEAM_CDN=fastly"),
            SteamClient.guestEnvEntries(true, SteamCdn.FASTLY))
        assertTrue(SteamClient.guestEnvEntries(false, SteamCdn.AUTO).isEmpty())
    }
}
