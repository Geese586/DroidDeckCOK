package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local-only install reads Valve's manifest out of the folder, and the folder holds that manifest
 * beside the component zips - the two are told apart by name alone. Getting it wrong is quiet
 * either way, which is why it is worth a test: call the manifest a component and the flag is never
 * passed, so the install goes back to waiting on Valve's CDN - the one thing this path exists to
 * avoid; call a component a manifest and the script is put into local-only mode over a folder it
 * then refuses, and the step fails outright.
 */
class SteamClientTest {
    @Test fun theManifestIsNotOneOfTheComponents() {
        assertTrue(SteamClient.isManifest("steam_client_steamdeck_publicbeta_linuxarm64"))
    }

    @Test fun aComponentIsNotTheManifest() {
        assertFalse(
            SteamClient.isManifest("bins_hardware_all.zip.c52878402b36211c3c356c9f4d672aed6e024512")
        )
        // The one component whose name does start with "steam_client_": the client's own package.
        assertFalse(
            SteamClient.isManifest(
                "steam_client_steamdeck_publicbeta_linuxarm64.zip.0123456789abcdef0123456789abcdef01234567"
            )
        )
    }

    @Test fun neitherIsAnythingElseInTheFolder() {
        assertFalse(SteamClient.isManifest("drivers.txt"))
        assertFalse(SteamClient.isManifest("bins_hardware_all.zip"))
        assertFalse(SteamClient.isManifest("steam_client"))
    }
}
