package com.droiddeck.launcher

import androidx.compose.foundation.layout.fillMaxSize
import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.DesktopLangExport
import com.droiddeck.launcher.runtime.DesktopLanguage
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.PackageRow
import com.droiddeck.launcher.ui.exposeTestTags
import com.droiddeck.launcher.session.OfflineMode
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.FirstLocalInstall
import com.droiddeck.launcher.session.FirstInstallLog
import com.droiddeck.launcher.ui.ComponentsPage
import com.droiddeck.launcher.session.GameSaves
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamClient
import com.droiddeck.launcher.session.SteamCdn
import com.droiddeck.launcher.session.SteamCdnProbe
import com.droiddeck.launcher.session.SteamRepair
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.core.WifiDiscovery
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.ui.CoreRow
import com.droiddeck.launcher.ui.PerformancePage
import com.droiddeck.launcher.ui.ModeSettings
import com.droiddeck.launcher.ui.ModeSettingsActions
import com.droiddeck.launcher.ui.ConfirmDialog
import com.droiddeck.launcher.ui.ControllerActions
import com.droiddeck.launcher.ui.ControllerMappingPage
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.ControllerEditorActivity
import com.droiddeck.launcher.ui.FrontEndScreen
import com.droiddeck.launcher.ui.FrontEndState
import com.droiddeck.launcher.ui.FrontEndActions
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.GameFiles
import com.droiddeck.launcher.frontend.GameFileSync
import com.droiddeck.launcher.frontend.GameLaunchIntent
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.RomsDialog
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays

/**
 * The whole app outside a session: is the runtime installed, is there a newer one, frame
 * generation, and one button that starts Steam. Everything a Steam client can do - the library,
 * the store, downloads, settings - is the client's own job once [SessionActivity] has it on screen.
 */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val drivers = DriverMenus(this, ui)
    private val components = ComponentsMenu(this, ui)
    private val decky = DeckyMenu(this, ui)
    private val updates = UpdatesMenu(this, ui)
    private val protons = ProtonMenu(this, ui, ::askProtonArchiveUnverified)

    // The screen's state. Compose redraws whatever reads these when they change.
    private var installed by mutableStateOf<String?>(null)
    /** A session launch waiting on its button to fill the page (LaunchFlood). */
    private var flood by mutableStateOf<PendingFlood?>(null)
    private var floodProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
    private class PendingFlood(val from: androidx.compose.ui.geometry.Rect, val intent: Intent)
    /** A session stopped behind a flood: its blue, drawn back into the button it started from (FloodReturn). */
    private var returning by mutableStateOf<ReturningFlood?>(null)
    private class ReturningFlood(val color: Int, val to: androidx.compose.ui.geometry.Rect?)
    private var ready by mutableStateOf(false)
    private var removalPending by mutableStateOf(false)
    private var available by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var percent by mutableIntStateOf(-1)
    private var failed by mutableStateOf(false)
    private var frameGenLabel by mutableStateOf("")
    private var lossless by mutableStateOf(Lossless.State.NONE)
    private var showRemove by mutableStateOf(false)
    private var showNonAdreno by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    /**
     * A picked runtime archive the catalog could not be reached to verify: nothing can be checked
     * against it, so the user is told that before it is installed rather than after.
     */
    private var showLocalRuntimeUnverified by mutableStateOf<File?>(null)
    /**
     * Valve's Steam client as the Steam settings page shows it: whether it is in the runtime, and a
     * local install's progress. Null stage = nothing running.
     */
    private var steamClientPresent by mutableStateOf(true)
    private var steamClientStage by mutableStateOf<String?>(null)
    private var steamClientPercent by mutableIntStateOf(-1)
    /**
     * Valve's Proton (ARM64) seed as the Steam settings page shows it: whether it is on the device,
     * and a local install's progress. Null stage = nothing running.
     */
    private var protonSeedPresent by mutableStateOf(true)
    private var protonSeedStage by mutableStateOf<String?>(null)
    private var protonSeedPercent by mutableIntStateOf(-1)
    /** A picked seed the catalog could not be reached to verify; asked about before installing it. */
    private var showLocalProtonUnverified by mutableStateOf<File?>(null)
    /** The GE-Proton / proton-cachyos row a file pick was made for, so the result lands on it. */
    private var protonFilePick by mutableStateOf<String?>(null)
    /** A picked build no release publishes a checksum for; asked about before installing it. */
    private var protonExtraUnverified by mutableStateOf<Pair<String, File>?>(null)
    /**
     * Whether a first-time install from a folder could still put something in. Computed with the
     * rest of the page's state, and what the play page's button is shown on.
     */
    private var firstLocalNeeded by mutableStateOf(false)
    private var glThread by mutableStateOf(true)
    private var noGlError by mutableStateOf(true)
    private var steamRepairQueued by mutableStateOf(false)
    private var mangoapp by mutableStateOf(true)
    private var steamController by mutableStateOf(SessionPrefs.CONTROLLER_DECK)
    // Components page: FEX / DXVK / VKD3D-Proton per Proton (ComponentsManager).
    private var showComponents by mutableStateOf(false)
    private var focusComponentsContent by mutableStateOf(true)
    private var showMapping by mutableStateOf(false)
    private var saveBusy: String? = null
    /** What to do with the zip or folder the file picker hands back after a game page's Manage saves. */
    private var onSavePicked: ((File) -> Unit)? = null
    /** A driver export waiting for the storage permission, to open its folder picker once answered. */
    private var exportAfterPermission = false
    /** The same wait, for the desktop's language dump. */
    private var langExportAfterPermission = false
    /** The same wait again, for copying the one-click install's log out. */
    private var firstLocalLogsAfterPermission = false
    private var controllerSettings by mutableStateOf<ControllerPrefs.Settings?>(null)
    private var catalog by mutableStateOf<List<DesktopCatalog.Entry>?>(null)
    private var catalogLoading by mutableStateOf(false)
    private var packageRows by mutableStateOf<List<PackageRow>?>(null)
    private var pkgId by mutableStateOf<String?>(null)
    private var pkgStage by mutableStateOf<String?>(null)
    private var pkgPercent by mutableIntStateOf(-1)
    private var desktopInstalled by mutableStateOf(false)
    private var desktopPresent by mutableStateOf(false)
    private var offlineAccount by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var showPerformance by mutableStateOf(false)
    private var clientOverride by mutableStateOf(false)
    private var clientCores by mutableStateOf<Set<Int>>(emptySet())
    private var gameCores by mutableStateOf<Set<Int>>(emptySet())
    private var tuSysmem by mutableStateOf(false)
    private var zinkLazy by mutableStateOf(false)
    private var noXalia by mutableStateOf(true)
    private var fastSync by mutableStateOf(false)
    private var syncFallback by mutableStateOf(true)
    private var fsyncFirst by mutableStateOf(false)
    private var gamescopeRealtime by mutableStateOf(false)
    private var gpuClockPin by mutableStateOf(false)
    private var prootNoSeccomp by mutableStateOf(false)
    private var prootFastPath by mutableStateOf(true)
    /** The one-click install's last report, on screen until it is dismissed: a Toast was too short. */
    private var firstLocalReport by mutableStateOf<String?>(null)
    private var guestHostname by mutableStateOf(SessionPrefs.DEFAULT_GUEST_HOSTNAME)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var phantomProcessStatus by mutableStateOf(PhantomProcessStatus.NOT_APPLICABLE)
    private var showPhantomGate by mutableStateOf(false)
    private var directAudio by mutableStateOf(false)
    private var stretch16x9 by mutableStateOf(false)
    private var orientation by mutableStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    private var launcherFullscreen by mutableStateOf(true)
    private var animationsEnabled by mutableStateOf(true)
    /** Where GitHub-hosted downloads go: DownloadSource.OFFICIAL or DownloadSource.CHINA. */
    private var downloadSource by mutableStateOf(DownloadSource.OFFICIAL)
    /** Which of Valve's two client-update edges Steam client installs pin (DroidDeckCOK). */
    private var steamCdn by mutableStateOf(com.droiddeck.launcher.session.SteamCdn.AUTO)
    private var steamLineTestBusy by mutableStateOf(false)
    private var steamLineTestEdges by mutableStateOf<List<com.droiddeck.launcher.session.SteamCdnProbe.Edge>?>(null)
    private var gameStoresEnabled by mutableStateOf(true)
    private var gameStoresSpeedTier by mutableStateOf("fast")
    private var storesOpenTab by mutableStateOf(SessionPrefs.STORES_OPEN_LIBRARY)
    /** A page a notification asked for ("stores:downloads", "app:<id>"), until the front end has gone there. */
    private var navRequest by mutableStateOf<String?>(null)
    private var storesShowMature by mutableStateOf(false)
    private var mic by mutableStateOf(false)
    private var wifiDiscovery by mutableStateOf(false)
    private var wifiDiscoveryPermission by mutableStateOf(false)
    private var wifiDiscoveryLocation by mutableStateOf(false)
    private var wifiDiscoveryAsked by mutableStateOf(false)
    private var wifiDiscoveryBlocked by mutableStateOf(false)
    private val wifiLocationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refreshWifiDiscovery()
        }
    }
    private val mediaRefresh = Runnable {
        refresh()
        refreshAddedGames()
    }
    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            ui.removeCallbacks(mediaRefresh)
            ui.postDelayed(mediaRefresh, MEDIA_SETTLE_MS)
        }
    }
    private val wifiLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SessionPrefs.setWifiDiscoveryEnabled(this, WifiDiscovery.permissionGranted(this))
        refreshWifiDiscovery()
    }
    private val wifiPermissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SessionPrefs.setWifiDiscoveryEnabled(this, WifiDiscovery.permissionGranted(this))
        refreshWifiDiscovery()
    }

    // The app's own picker (files/), once per kind of pick: the two driver lists validate
    // differently, and the reason a zip is refused names the list it belongs in.
    private val pickComponent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importComponent(it) }
    }
    private val pickLinuxDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = true) }
    }
    private val pickAndroidDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = false) }
    }
    private val pickDeckyPluginZip = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { decky.importPluginZip(it) }
    }
    private val pickAnyDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = null) }
    }
    private val pickExportDriversDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { drivers.exportTo(it) }
    }
    /** The one pick the desktop's language dump needs: the folder it is written into. */
    private val pickDesktopLangDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { exportDesktopLangTo(it) }
    }
    /** The one pick the install log needs: the folder the files are copied into. */
    private val pickFirstLocalLogDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { saveFirstLocalLogsTo(it) }
    }
    private val pickLossless = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { importLossless(it) }
    }
    private val pickRuntimeArchive = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { installRuntimeFrom(it) }
    }
    private val pickSteamClientDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { installSteamClientFrom(it) }
    }
    private val pickProtonSeed = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { installProtonSeedFrom(it) }
    }
    private val pickProtonArchive = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Which row asked is read first: the picker is one control shared by both of them.
        val id = protonFilePick.also { protonFilePick = null } ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { protons.installProtonFromFile(id, it) }
    }
    /** The one pick the first-time install needs: the folder the releases were saved into. */
    private val pickFirstLocalDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { planFirstLocalInstall(it) }
    }
    private val pickSaveZip = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val then = onSavePicked.also { onSavePicked = null } ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let(then)
    }
    private val pickSaveDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val then = onSavePicked.also { onSavePicked = null } ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let(then)
    }

    /**
     * Copy every installed GPU driver into a folder the user picks. The write goes to shared
     * storage, which on this targetSdk still needs the old permission: ask for it first, then open
     * the folder picker - a refusal is not asked again, the write itself says what went wrong.
     */
    private fun exportDrivers() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            exportAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), EXPORT_PERMISSION)
            return
        }
        pickExportDriversDir.launch(InAppFilePicker.buildDirIntent(
            this, getString(R.string.driversexport_pick),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
        ))
    }

    /**
     * Pack the desktop's language files into a folder the user picks. Same shape as [exportDrivers]:
     * the write goes to shared storage, which on this targetSdk still needs the old permission, so
     * ask for it first and open the folder picker once answered.
     */
    private fun exportDesktopLang() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            langExportAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), LANG_PERMISSION)
            return
        }
        pickDesktopLangDir.launch(InAppFilePicker.buildDirIntent(
            this, getString(R.string.desktop_lang_export_pick),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
        ))
    }

    /** Builds the dump into [folder] on a worker thread and says where it landed, or that it did not. */
    private fun exportDesktopLangTo(folder: File) {
        DesktopLangExport.prepare(this, folder) { zip ->
            val text = if (zip == null) getString(R.string.desktop_lang_export_failed)
                else getString(R.string.desktop_lang_export_saved, zip.path.removePrefix("/storage/emulated/0/"))
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Puts the desktop on Traditional Chinese: the same two small files the one-click install
     * writes as the Chinese pack goes in, so a device whose pack arrived earlier - or whose
     * language was picked in KDE since - can be set without going through the whole install again.
     * Plasma reads the file as it starts, so the desktop wants a restart to show it.
     */
    private fun setDesktopLanguage() {
        val ok = DesktopLanguage.applyTraditionalChinese(this)
        android.widget.Toast.makeText(
            this,
            getString(if (ok) R.string.desktop_lang_applied else R.string.desktop_lang_apply_failed),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Copies the one-click install's log - this run's, and the guest commands' own files beside it -
     * into a folder the user picks, so a device with no shell can still hand the run over. Same
     * storage prompt as the other exports, which this targetSdk still needs.
     */
    private fun saveFirstLocalLogs() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            firstLocalLogsAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), LOG_PERMISSION)
            return
        }
        pickFirstLocalLogDir.launch(InAppFilePicker.buildDirIntent(
            this, getString(R.string.first_local_logs_pick),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
        ))
    }

    /** The copy itself, off the main thread, with the folder it landed in said back. */
    private fun saveFirstLocalLogsTo(folder: File) {
        Thread({
            val written = FirstInstallLog.save(this, folder)
            ui.post {
                android.widget.Toast.makeText(
                    this,
                    if (written.isEmpty()) getString(R.string.first_local_logs_failed)
                    else getString(R.string.first_local_logs_saved, written.size, folder.path.removePrefix("/storage/emulated/0/")),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }, "first-install-logs").start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == EXPORT_PERMISSION && exportAfterPermission) {
            exportAfterPermission = false
            exportDrivers()
        }
        if (requestCode == LANG_PERMISSION && langExportAfterPermission) {
            langExportAfterPermission = false
            exportDesktopLang()
        }
        if (requestCode == LOG_PERMISSION && firstLocalLogsAfterPermission) {
            firstLocalLogsAfterPermission = false
            saveFirstLocalLogs()
        }
    }

    private fun refreshWifiDiscovery() {
        wifiDiscoveryPermission = WifiDiscovery.permissionGranted(this)
        wifiDiscoveryLocation = WifiDiscovery.locationEnabled(this)
        wifiDiscoveryAsked = SessionPrefs.wifiDiscoveryAsked(this)
        wifiDiscoveryBlocked = !wifiDiscoveryPermission && wifiDiscoveryAsked &&
            WifiDiscovery.permissions.none { shouldShowRequestPermissionRationale(it) }
        // A revoked grant must not leave an enabled switch behind.
        if (!wifiDiscoveryPermission && SessionPrefs.wifiDiscoveryEnabled(this)) {
            SessionPrefs.setWifiDiscoveryEnabled(this, false)
        }
        wifiDiscovery = SessionPrefs.wifiDiscoveryEnabled(this)
    }

    private fun openWifiDiscoverySettings() {
        if (!WifiDiscovery.permissionGranted(this)) {
            wifiPermissionSettings.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } else {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    /** Takes a new or updated Lossless Scaling from Steam and shows what LSFG can use. */
    private fun syncLossless() {
        Thread({
            Lossless.sync(this)
            val state = Lossless.state(this)
            ui.post { lossless = state }
        }, "lossless-sync").start()
    }

    private fun importLossless(dll: File) {
        Thread({
            val message = Lossless.message(this, Lossless.import(this, dll))
            val state = Lossless.state(this)
            ui.post {
                lossless = state
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "lossless-import").start()
    }

    /** Import a save zip into [game]: pick it in the app's file picker, then back up and unzip off the main thread. */
    private fun importSaves(name: String, game: () -> GameSaves.Game) {
        if (SessionState.running) {
            android.widget.Toast.makeText(this, R.string.main_saves_close_session, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        onSavePicked = { zip ->
            saveAction(getString(R.string.main_saves_importing, name)) {
                val (written, backup) = GameSaves.import(this, game(), zip)
                val kind = GameSaves.layoutOf(zip)?.label(this) ?: getString(R.string.saves_zip)
                resources.getQuantityString(
                    if (backup != null) R.plurals.main_saves_imported_backup else R.plurals.main_saves_imported,
                    written, written, kind, name,
                )
            }
        }
        pickSaveZip.launch(InAppFilePicker.buildIntent(this, listOf("zip"), getString(R.string.main_saves_pick_zip, name), GameSaves.savesDir().parentFile?.parentFile?.path))
    }

    /** Export [game]'s saves in [layout] to a folder picked in the app's file picker. */
    private fun exportSaves(name: String, layout: GameSaves.Layout, game: () -> GameSaves.Game) {
        onSavePicked = { dir ->
            saveAction(getString(R.string.main_saves_exporting, name)) {
                val (zip, count) = GameSaves.export(this, game(), layout, dir)
                resources.getQuantityString(R.plurals.main_saves_exported, count, count, layout.label(this), zip.path.removePrefix("/storage/emulated/0/"))
            }
        }
        GameSaves.savesDir().mkdirs()
        pickSaveDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_saves_pick_folder, name, layout.label(this)), GameSaves.savesDir().path))
    }
    /** The Games tab's +: the picked .exe becomes a game (or selects / switches the one it belongs to). */
    private fun addGameExe(path: String) {
        val exe = java.io.File(path)
        Thread({
            val result = try { com.droiddeck.launcher.frontend.AddedGames.addExe(this, exe) } catch (e: Exception) {
                Log.w(TAG, "add game: ${e.message}"); com.droiddeck.launcher.frontend.AddedGames.AddResult.NotFound
            }
            val game = when (result) {
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Added -> result.game
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Existing -> result.game
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Switched -> result.game
                else -> null
            }
            ui.post {
                if (game == null) {
                    if (result is com.droiddeck.launcher.frontend.AddedGames.AddResult.Unreachable)
                        android.widget.Toast.makeText(this, R.string.games_add_unreachable, android.widget.Toast.LENGTH_LONG).show()
                    return@post
                }
                updateAddedGames(listOf(game.folder.path), then = { fetchAddedGameArt() })
                navRequest = "app:${game.steamAppId ?: game.appId.toInt()}"
            }
        }, "add-game-exe").start()
    }
    private var addedGamesArt by mutableStateOf(true)
    @Volatile private var artFetchRunning = false
    /** The user entered a SteamGridDB API key of their own. */
    private var sgdbUserKey by mutableStateOf(false)
    private val pickRomsDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setRomsDir(this, path)
            romsDir = path
        }
    }
    // The mode whose lifted sheet last read its settings, with what it shows; refreshed by loadModeSettings().
    private var settingsMode by mutableStateOf<String?>(null)
    private var resolution by mutableStateOf(com.droiddeck.launcher.session.SessionDisplay.DEFAULT_RESOLUTION)
    private var fexPreset by mutableStateOf("")
    private var forceSsbs by mutableStateOf(false)
    /**
     * Which copy of the app this is: the version, when the APK that is running was written, and
     * this fork's own build number, on a line of its own.
     *
     * Two lines rather than one because neither number names a build on its own. The package
     * version follows the upstream release this copy carries (0.4.0 -> "4.0"), which is what keeps
     * it comparable with an official release, and it therefore cannot also count this fork's own
     * builds - every build carrying the same upstream release reports the same version, and the
     * versionCode follows upstream alone (13 throughout 0.4.0). So the moment the APK was written
     * separates two builds of it, and the "COK(...)" line is the number this checkout publishes
     * that build under ([BuildConfig.FORK_VERSION]). Shown in the mode settings so a device's
     * build can be named from the device itself.
     */
    private val buildStamp: String by lazy {
        val parts = mutableListOf<String>()
        try {
            packageManager.getPackageInfo(packageName, 0).versionName?.let { parts += it }
        } catch (e: Exception) {
        }
        try {
            parts += java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(File(applicationInfo.sourceDir).lastModified()))
        } catch (e: Exception) {
        }
        // The fork's own line, never merged into the version above: it is not part of the package
        // version and must not be read as if it were.
        val cok = "COK(" + BuildConfig.FORK_VERSION + ")"
        val build = parts.joinToString(" - ")
        if (build.isEmpty()) cok else build + "\n" + cok
    }
    private var runSteamAtStartup by mutableStateOf(false)
    private var theme by mutableStateOf("graphite")
    private var appScale by mutableStateOf(com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE)
    private var hdrOn by mutableStateOf(false)
    private var fpsLimit by mutableStateOf(0)
    private var upscaler by mutableStateOf(0)
    private var upscaleSharpness by mutableStateOf(75)
    private var hdrReason by mutableStateOf<String?>(null)
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var suspendPolicy by mutableStateOf(SessionPrefs.SUSPEND_MANUAL)
    private var steamDownloadsInBackground by mutableStateOf(false)
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var backActionsInverted by mutableStateOf(false)
    private var gameStorage by mutableStateOf("")
    private var storageDiagnostics by mutableStateOf(false)
    private var storageOptions by mutableStateOf<List<GameStorage.Option>>(emptyList())
    private val pickGameStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path -> setGameStorage(path, GameStorage.labelFor(this, path)) }
    }
    private var romsDir by mutableStateOf<String?>(null)
    private var steamGames by mutableStateOf<List<Library.SteamGame>>(emptyList())
    @Volatile private var componentSweepDone = false
    @Volatile private var componentSweepSession: String? = null
    private var emulatorList by mutableStateOf<List<Library.Emulator>>(emptyList())
    private var runningLabel by mutableStateOf<String?>(null)
    private var logsEnabled by mutableStateOf(true)
    private var agentCommands by mutableStateOf(false)
    private var showRoms by mutableStateOf(false)
    private var homeAppSelected by mutableStateOf(false)
    private var homeScreenEnabled by mutableStateOf(false)
    private var defaultHomeLabel by mutableStateOf<String?>(null)
    private var androidApps by mutableStateOf<List<HomeApp.LaunchableApp>>(emptyList())
    private var secondScreenDisplays by mutableStateOf<List<SecondScreenDisplay>>(emptyList())
    private lateinit var displayManager: DisplayManager
    private val secondScreenDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshSecondScreenDisplays()
    }

    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeAppState()
    }

    /** The session surface rises over the front end instead of cutting to it. */
    override fun startActivity(intent: Intent?) {
        if (intent?.component?.className == SessionActivity::class.java.name) {
            when {
                busy || LinuxRuntimeInstaller.isBusy() -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_runtime, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                protons.protonBusyId != null || ProtonExtras.installInProgress -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_proton_install, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                pkgStage != null -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_desktop_install, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        if (intent?.component?.className == SessionActivity::class.java.name) {
            if (flood != null) return
            // Started by a blue button: it floods the page first. A running session is re-joined
            // as before, rising over the front end.
            val from = com.droiddeck.launcher.ui.LaunchOrigin.take()
            if (from != null && !SessionState.running && com.droiddeck.launcher.ui.Motion.scale != 0f) {
                flood = PendingFlood(from, intent)
                return
            }
            com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
        }
        super.startActivity(intent)
        if (intent?.component?.className == SessionActivity::class.java.name) overridePendingTransition(R.anim.session_rise, R.anim.session_hold)
    }

    /** The page is all blue: the session opens on the same blue, with no animation of its own. */
    private fun launchFlooded(f: PendingFlood) {
        if (busy || LinuxRuntimeInstaller.isBusy()) { flood = null; floodProgress = 0f; return }
        val signal = com.droiddeck.launcher.ui.Themes.byId(theme).signal
        super.startActivity(f.intent.putExtra(com.droiddeck.launcher.ui.EXTRA_FLOOD, signal.toArgb()))
        overridePendingTransition(0, 0)
    }

    private var shortcutPicker by mutableStateOf(false)
    private var shortcutLibraryScanning by mutableStateOf(false)
    @Volatile private var libraryScanGeneration = 0
    private var pendingGameLink: String? = null
    private var gameSyncFolder by mutableStateOf<String?>(null)
    private fun pickGameExport(game: Library.SteamGame?) {
        onSavePicked = { folder ->
            saveAction(getString(R.string.game_frontend_files)) {
                val saved = if (game != null) GameFiles.export(this, folder, game)
                else {
                    GameFileSync.enable(this, folder)
                    folder
                }
                ui.post {
                    gameSyncFolder = GameFileSync.folder(this)
                }
                getString(R.string.game_file_exported, saved.path)
            }
        }
        pickSaveDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.game_file_folder), gameSyncFolder))
    }

    private fun readGameIntent(request: Intent) {
        request.getStringExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV)?.let { navRequest = it; request.removeExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV) }
        shortcutPicker = request.action == Intent.ACTION_CREATE_SHORTCUT
        if (shortcutPicker) {
            // The shortcut picker must only offer games from the scan started for this request.
            steamGames = emptyList()
            shortcutLibraryScanning = true
        } else shortcutLibraryScanning = false
        pendingGameLink = null
        if (GameLaunchIntent.accepts(request.action)) {
            val copy = Intent(request)
            Thread({
                val id = GameLaunchIntent.read(this, copy)
                ui.post {
                    if (intent === request && !isDestroyed) {
                        pendingGameLink = id
                        if (id == null) {
                            android.widget.Toast.makeText(this, R.string.game_link_invalid, android.widget.Toast.LENGTH_LONG).show()
                        } else refresh()
                    }
                }
            }, "game-launch-file").start()
        }
    }

    private fun launchGame(game: Library.SteamGame, returnHome: Boolean = false): Boolean {
        if (protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null) {
            android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (SessionState.running) {
            if (SessionState.mode != SessionService.MODE_STEAM || SessionState.stopRequested) {
                android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                return true
            }
            if (!SessionService.launchGame(this, game.gameIdString)) {
                android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        return startSession(Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .putExtra(SessionService.EXTRA_STEAM_URL,
                com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(game.gameIdString)), steamSession = true)
    }

    private fun handleGameLink(games: List<Library.SteamGame>) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val id = pendingGameLink ?: return
        val game = games.firstOrNull { it.gameIdString == id }
        if (game == null) {
            pendingGameLink = null
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(GameLaunchIntent.EXTRA_APP_ID)
            android.widget.Toast.makeText(this, R.string.game_link_missing, android.widget.Toast.LENGTH_LONG).show()
        } else if (launchGame(game, returnHome = true)) {
            pendingGameLink = null
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(GameLaunchIntent.EXTRA_APP_ID)
        }
    }

    private fun chooseGameShortcut(game: Library.SteamGame) {
        setResult(RESULT_OK, com.droiddeck.launcher.frontend.GameShortcuts.result(this, game))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readGameIntent(intent)
        gameSyncFolder = GameFileSync.folder(this)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        refreshPhantomStatus()
        theme = SessionPrefs.theme(this)
        frameGenLabel = getString(R.string.frame_gen_off)
        appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)
        // Last start's game list, so the Steam wall is up on the first frame; refresh() replaces it.
        steamGames = if (shortcutPicker) emptyList() else com.droiddeck.launcher.frontend.LibraryCache.load(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        orientation = SessionPrefs.orientation(this)
        requestedOrientation = orientation
        launcherFullscreen = SessionPrefs.launcherFullscreen(this)
        animationsEnabled = SessionPrefs.animationsEnabled(this)
        gameStoresEnabled = SessionPrefs.gameStoresEnabled(this)
        gameStoresSpeedTier = SessionPrefs.gameStoresSpeedTier(this)
        storesOpenTab = SessionPrefs.storesOpenTab(this)
        storesShowMature = SessionPrefs.storesShowMature(this)
        applyLauncherFullscreen()
        updates.start()
        setContent {
            DroidDeckTheme(theme, appScale = appScale) {
            com.droiddeck.launcher.ui.FocusGlideHost(androidx.compose.ui.Modifier.fillMaxSize().exposeTestTags()) {
                val page: (@Composable () -> Unit)? = when {
                    showPerformance -> { { PerformanceHost() } }
                    showComponents -> { { ComponentsHost() } }
                    showMapping -> { { MappingHost() } }
                    else -> null
                }
                com.droiddeck.launcher.ui.FloodBehind({ floodProgress }) {
                FrontEndScreen(
                    FrontEndState(
                        installed = installed, ready = ready, available = available?.version, removalPending = removalPending, runtimeActionsBlocked = runtimeChangesBlocked(),
                        shortcutPicker = shortcutPicker,
                        shortcutLibraryScanning = shortcutLibraryScanning,
                        gameSyncFolder = gameSyncFolder,
                        busy = busy, stage = stage, percent = percent,
                        desktopInstalled = desktopInstalled, desktopPresent = desktopPresent,
                        offlineAccount = offlineAccount, offline = offline,
                        frameGenLabel = frameGenLabel, romsDir = romsDir, logsEnabled = logsEnabled, agentCommands = agentCommands,
                        steamGames = steamGames, emulators = emulatorList, running = runningLabel,
                        frameGen = FrameGen.mode(this),
                        lossless = lossless,
                        pageKey = if (showPerformance) "performance" else if (showComponents) "components" else if (showMapping) "controller-mapping" else null,
                        theme = theme,
                        language = com.droiddeck.launcher.core.AppLanguage.chosen(this),
                        appScale = appScale,
                        isHomeApp = homeAppSelected,
                        homeScreenEnabled = homeScreenEnabled,
                        defaultHomeLabel = defaultHomeLabel,
                        androidApps = androidApps,
                        secondScreenDisplays = secondScreenDisplays,
                        packages = packageRows,
                        packageCatalogLoading = catalogLoading,
                        packageBusyId = pkgId,
                        packageStage = pkgStage,
                        packagePercent = pkgPercent,
                        sessionRunning = SessionState.running,
                        backActionsInverted = backActionsInverted,
                        buildLabel = BuildConfig.BUILD_LABEL,
                        oscMode = oscMode,
                        controller = controllerSettings,
                        updates = updates.state(),
                        phantomProcessStatus = phantomProcessStatus,
                        showPhantomGate = showPhantomGate,
                        orientation = orientation,
                        launcherFullscreen = launcherFullscreen,
                        animationsEnabled = animationsEnabled,
                        downloadSource = downloadSource,
                        steamCdn = steamCdn,
                        steamLineTestBusy = steamLineTestBusy,
                        steamLineTestEdges = steamLineTestEdges,
                        firstLocalInstallNeeded = firstLocalNeeded,
                        gameStoresEnabled = gameStoresEnabled,
                        gameStoresSpeedTier = gameStoresSpeedTier,
                        storesOpenTab = storesOpenTab,
                        navigate = navRequest,
                        addedGamesArt = addedGamesArt,
                        sgdbUserKey = sgdbUserKey,
                        storesShowMature = storesShowMature,
                        storeDownloadsActive = com.droiddeck.launcher.stores.StoresState.activeDownloads,
                    ),
                    FrontEndActions(
                        onPlay = { startSteamSession() },
                        // The client's desktop UI, with gamescope's Deck integration disabled.
                        onPlayDesktopUi = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"), steamSession = true)
                        },
                        // A store game may need something done first (an Epic game its sign-in code).
                        onSteamGame = { g ->
                            if (shortcutPicker) chooseGameShortcut(g)
                            else {
                                // The launch takes the Windows components already on; the game's list is
                                // looked at again in the background once the session is over.
                                com.droiddeck.launcher.frontend.AutoComponents.beforeLaunch(this, g)
                                com.droiddeck.launcher.stores.StoreLaunch.prepare(this, g) { launchGame(g) }
                            }
                        },
                        onGameShortcut = { g -> com.droiddeck.launcher.frontend.GameShortcuts.pin(this, g) },
                        onExportGameFile = { g -> pickGameExport(g) },
                        onSyncGameFiles = { pickGameExport(null) },
                        onAddGameExe = { path -> addGameExe(path) },
                        onAddedGamesChanged = { folders ->
                            // New games want art; an edit does not.
                            val known = steamGames.mapNotNull { it.gameFiles?.path }.toSet()
                            updateAddedGames(folders, then = { if (folders.any { it !in known }) fetchAddedGameArt() })
                        },
                        onAddedGameRemoved = { folder, appId, name -> removeAddedGame(folder, appId, name) },
                        onAddedGamesArt = { on -> SessionPrefs.setAddedGamesArt(this, on); addedGamesArt = on; if (on) refreshAddedGames() },
                        onSgdbKey = { key ->
                            Thread({
                                com.droiddeck.launcher.frontend.SteamGridDb.setUserKey(this, key)
                                ui.post { sgdbUserKey = key.isNotBlank(); refreshAddedGames() }
                            }, "sgdb-key").start()
                        },
                        onStopGameFileSync = {
                            Thread({
                                GameFileSync.disable(this)
                                ui.post { gameSyncFolder = null }
                            }, "game-file-stop-sync").start()
                        },
                        onCopyGameLink = { g ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText(g.name, com.droiddeck.launcher.frontend.GameLaunchLink.uri(g.gameIdString)))
                            android.widget.Toast.makeText(this, R.string.game_link_copied, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDesktop = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP))
                        },
                        onEmulator = { e -> launchProgram(e.program) },
                        // An app added on the Desktop page, full screen under gamescope like an emulator.
                        onUserApp = { app ->
                            app.args.firstOrNull()?.let { Library.flatpakNames[it] = app.name }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, app.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, app.args.toTypedArray()))
                        },
                        // A Flatpak app from the store, full screen under gamescope like an emulator.
                        onFlatpakApp = { id, name ->
                            Library.flatpakNames[id] = name
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, com.droiddeck.launcher.runtime.FlatpakManager.LAUNCHER)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, arrayOf(id)))
                        },
                        onRom = { g ->
                            val e = emulatorList.first { it.id == g.emulatorId }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, e.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, Library.launchArgs(e.id, g.guestPath).toTypedArray()))
                        },
                        // The activity re-attaches to the session that is running; nothing restarts.
                        onResume = { startActivity(Intent(this, SessionActivity::class.java)) },
                        onSteamSettings = { loadModeSettings(SessionService.MODE_STEAM) },
                        // The releases are fetched elsewhere and copied over, so the picker opens at
                        // the folder the last run read from - and where a first copy usually lands.
                        onFirstLocalInstall = {
                            pickFirstLocalDir.launch(InAppFilePicker.buildDirIntent(
                                this, getString(R.string.first_local_pick),
                                FirstLocalInstall.rememberedFolder(this)?.path
                                    ?: android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS).path,
                            ))
                        },
                        onRemoveDesktop = { removeDesktop() },
                        onDesktopSettings = { loadModeSettings(SessionService.MODE_DESKTOP) },
                        modeSheet = { mode, tab, extras -> ModeSheetHost(mode, tab, extras) },
                        onInstallPackage = { id -> installPackage(id) },
                        onRemovePackage = { id -> removePackage(id) },
                        onRuntime = { onRuntimeButton() },
                        // The archive is fetched elsewhere and put on the device, so the picker
                        // opens where it usually lands.
                        onInstallRuntimeFromFile = {
                            pickRuntimeArchive.launch(InAppFilePicker.buildIntent(
                                this, listOf("zst"), getString(R.string.setup_runtime_local_pick),
                                android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS).path,
                            ))
                        },
                        onFrameGenPick = { mode ->
                            FrameGen.set(this, mode)
                            frameGenLabel = FrameGen.label(this)
                        },
                        onImportLossless = {
                            pickLossless.launch(InAppFilePicker.buildIntent(this, listOf("dll"), getString(R.string.lsfg_pick_title)))
                        },
                        onProtons = { openProtons() },
                        onComponents = { focusContent -> openComponents(focusContent) },
                        onComponentsTab = { tab -> openComponents(focusContent = true, tab = tab) },
                        // A game page's Manage saves: the game's Proton and saves are read when the work runs, off the main thread.
                        onSaveImport = { sg -> importSaves(sg.name) { GameSaves.game(sg) } },
                        onSaveExport = { sg, layout -> exportSaves(sg.name, layout) { GameSaves.game(sg) } },
                        onPerformance = { refreshCores(); showComponents = false; showMapping = false; showPerformance = true },
                        onRoms = { showRoms = true },
                        onFiles = { startActivity(Intent(this, com.droiddeck.launcher.files.FileManagerActivity::class.java)) },
                        onBrowseFiles = { dir ->
                            startActivity(Intent(this, com.droiddeck.launcher.files.FileManagerActivity::class.java)
                                .putExtra(com.droiddeck.launcher.files.FileManagerActivity.EXTRA_START_DIR, dir.absolutePath))
                        },
                        onExportDesktopLang = { exportDesktopLang() },
                        onSetDesktopLanguage = { setDesktopLanguage() },
                        onExportInstallLog = { saveFirstLocalLogs() },
                        onLogs = {
                            SessionPrefs.setLogsEnabled(this, !SessionPrefs.logsEnabled(this))
                            logsEnabled = SessionPrefs.logsEnabled(this)
                        },
                        onAgentCommands = {
                            com.droiddeck.launcher.agent.AgentAccess.setCommandsEnabled(this, !com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this))
                            agentCommands = com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this)
                        },
                        onShareLogs = {
                            SessionLogShare.prepare(this, { SessionLogShare.latest(this) }) { zip ->
                                if (zip == null) android.widget.Toast.makeText(this, R.string.main_no_logs_yet, android.widget.Toast.LENGTH_LONG).show()
                                else startActivity(SessionLogShare.shareIntent(this, zip))
                            }
                        },
                        onClearLogs = {
                            Thread({
                                val cleared = runCatching { SessionArtifacts.clearAll(this) }.getOrDefault(0)
                                ui.post {
                                    android.widget.Toast.makeText(this,
                                        if (cleared == 0) getString(R.string.main_no_logs_to_clear) else resources.getQuantityString(R.plurals.main_logs_cleared, cleared, cleared),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }, "clear-logs").start()
                        },
                        onOffline = {
                            OfflineMode.setEnabled(this, !OfflineMode.enabled(this))
                            offline = OfflineMode.enabled(this)
                        },
                        onPageBack = { showPerformance = false; showComponents = false; showMapping = false },
                        onGameStoresEnabled = { on -> SessionPrefs.setGameStoresEnabled(this, on); gameStoresEnabled = on },
                        onTheme = { id -> SessionPrefs.setTheme(this, id); theme = id },
                        onLanguage = { tag ->
                            if (tag != com.droiddeck.launcher.core.AppLanguage.chosen(this)) {
                                com.droiddeck.launcher.core.AppLanguage.setChosen(this, tag)
                                // Redrawn in the new language; Setup's page and tab are saved state.
                                recreate()
                            }
                        },
                        onAppScale = { percent ->
                            com.droiddeck.launcher.core.AppUiPrefs.setScale(this, percent)
                            appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)
                        },
                        onOrientation = { value ->
                            SessionPrefs.setOrientation(this, value)
                            orientation = value
                            requestedOrientation = value
                        },
                        onLauncherFullscreen = { on ->
                            SessionPrefs.setLauncherFullscreen(this, on)
                            launcherFullscreen = on
                            applyLauncherFullscreen()
                        },
                        // The runtime, its catalog and every other GitHub fetch resolve through
                        // this, so a change takes effect on the next download with no restart.
                        onDownloadSource = { id ->
                            DownloadSource.set(this, id)
                            downloadSource = id
                        },
                        onSteamCdn = { id ->
                            com.droiddeck.launcher.session.SteamCdn.set(this, id)
                            steamCdn = id
                        },
                        onSteamLineTest = { runSteamLineTest() },
                        onSteamLineTestDismiss = { steamLineTestEdges = null },
                        onGameStoresSpeedTier = { tier -> SessionPrefs.setGameStoresSpeedTier(this, tier); gameStoresSpeedTier = tier },
                        onStoresOpenTab = { tab -> SessionPrefs.setStoresOpenTab(this, tab); storesOpenTab = tab },
                        onNavigated = { navRequest = null },
                        onStoresShowMature = { show -> SessionPrefs.setStoresShowMature(this, show); storesShowMature = show },
                        onLibraryChanged = { refreshAddedGames(); refresh() },
                        onAnimationsEnabled = { on ->
                            SessionPrefs.setAnimationsEnabled(this, on)
                            animationsEnabled = on
                            com.droiddeck.launcher.ui.Motion.refresh(this)
                        },
                        onHomeApp = { manageHomeApp() },
                        onHomeScreen = { on ->
                            HomeApp.setHomeScreenEnabled(this, on)
                            refreshHomeAppState()
                        },
                        onAndroidApp = { app, displayId -> launchAndroidApp(app, displayId) },
                        onBackActionsInverted = { inverted ->
                            SessionPrefs.setBackActionsInverted(this, inverted)
                            backActionsInverted = inverted
                        },
                        onRefreshPhantomStatus = { refreshPhantomStatus() },
                        onOpenDeveloperOptions = { displayId -> openDeveloperOptions(displayId) },
                        onWirelessAdbPair = { host, port, code, complete ->
                            Thread({
                                val result = runCatching {
                                    kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@MainActivity, host, port, code) }
                                }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_pair_failed) }
                                ui.post { complete(error) }
                            }, "wireless-adb-pair").start()
                        },
                        onFindWirelessAdbPort = { host, complete ->
                            Thread({
                                val port = WirelessAdbFix.findConnectPort(this@MainActivity, host)
                                ui.post { complete(port) }
                            }, "wireless-adb-discovery").start()
                        },
                        onWirelessAdbApply = { host, port, enabled, complete ->
                            Thread({
                                val result = runCatching { WirelessAdbFix.setChildProcessLimit(this@MainActivity, host, port, enabled) }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_command_failed) }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-fix").start()
                        },
                        onSetPhantomProcessLimit = { enabled, complete ->
                            Thread({
                                val result = runCatching { WirelessAdbFix.setUsingSavedPairing(this@MainActivity, enabled) }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_unavailable) }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-setting").start()
                        },
                        onCopyPhantomCommand = { enabled ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText(getString(R.string.main_adb_clip_label), PhantomProcessLimit.adbCommand(enabled)),
                            )
                            android.widget.Toast.makeText(this, R.string.main_adb_copied, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDismissPhantomGate = { showPhantomGate = false },
                        onStartWirelessAdbPairing = { WirelessAdbPairingService.start(this) },
                        onOpenNotificationSettings = {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        updates = updates.actions(),
                        controller = ControllerActions(
                            onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                            onTint = { t -> ControllerPrefs.setTint(this, t); refreshController() },
                            onOpacity = { o -> ControllerPrefs.setOpacity(this, o); refreshController() },
                            onSize = { v -> ControllerPrefs.setSize(this, v); refreshController() },
                            onStickClick = { on -> ControllerPrefs.setStickClick(this, on); refreshController() },
                            onAdaptiveSticks = { on -> ControllerPrefs.setAdaptiveSticks(this, on); refreshController() },
                            onRumble = { on -> ControllerPrefs.setRumble(this, on); refreshController() },
                            onSteamButton = { on -> ControllerPrefs.setSteamButton(this, on); refreshController() },
                            onQamButton = { on -> ControllerPrefs.setQamButton(this, on); refreshController() },
                            onKeyboardButton = { on -> ControllerPrefs.setKeyboardButton(this, on); refreshController() },
                            onSelectSteam = { on -> ControllerPrefs.setSelectSteam(this, on); refreshController() },
                            onEditLayout = { startActivity(Intent(this, ControllerEditorActivity::class.java)) },
                            onResetLayout = { ControllerPrefs.resetAllLayouts(this); refreshController() },
                            onMapping = { showPerformance = false; showComponents = false; showMapping = true },
                            onResetAll = { ControllerPrefs.resetAll(this); refreshController() },
                        ),
                    ),
                    page = page,
                )
                }
                if (showRoms) RomsDialog(
                    path = romsDir,
                    onChoose = {
                        showRoms = false
                        pickRomsDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_pick_roms), romsDir))
                    },
                    onClear = { SessionPrefs.setRomsDir(this, ""); romsDir = null; showRoms = false },
                    onDismiss = { showRoms = false },
                )
                showNonAdreno?.let { release ->
                    ConfirmDialog(
                        title = androidx.compose.ui.res.stringResource(R.string.main_non_adreno_title),
                        text = androidx.compose.ui.res.stringResource(R.string.main_non_adreno_text, com.droiddeck.launcher.core.DeviceSupport.gpuName(this@MainActivity), release.size / 1e6),
                        confirm = androidx.compose.ui.res.stringResource(R.string.main_install_anyway),
                        onConfirm = { showNonAdreno = null; install(release) },
                        onDismiss = { showNonAdreno = null },
                    )
                }
                if (showRemove) ConfirmDialog(
                    title = androidx.compose.ui.res.stringResource(R.string.main_remove_runtime_title),
                    text = androidx.compose.ui.res.stringResource(R.string.main_remove_runtime_text),
                    confirm = androidx.compose.ui.res.stringResource(R.string.store_remove),
                    onConfirm = { showRemove = false; removeRuntime() },
                    onDismiss = { showRemove = false },
                )
                // An unverified local install is the one way in without a checksum, so it is asked
                // about rather than done quietly.
                showLocalRuntimeUnverified?.let { archive ->
                    ConfirmDialog(
                        title = getString(R.string.setup_runtime_local_unverified_title),
                        text = getString(R.string.setup_runtime_local_unverified_text, archive.name),
                        confirm = getString(R.string.setup_runtime_local_unverified_confirm),
                        onConfirm = { showLocalRuntimeUnverified = null; install(null, archive) },
                        onDismiss = { showLocalRuntimeUnverified = null },
                    )
                }
                // The same question for the Proton build: without its catalog there is nothing to
                // check the picked archive against either.
                showLocalProtonUnverified?.let { archive ->
                    ConfirmDialog(
                        title = getString(R.string.setup_runtime_local_unverified_title),
                        text = getString(R.string.setup_runtime_local_unverified_text, archive.name),
                        confirm = getString(R.string.setup_runtime_local_unverified_confirm),
                        onConfirm = { showLocalProtonUnverified = null; installProtonSeed(null, archive) },
                        onDismiss = { showLocalProtonUnverified = null },
                    )
                }
                // The same question for a GE-Proton / proton-cachyos archive: only the builds a
                // release publishes have anything to check against.
                protonExtraUnverified?.let { (id, archive) ->
                    ConfirmDialog(
                        title = getString(R.string.setup_runtime_local_unverified_title),
                        text = getString(R.string.proton_local_unverified_text, archive.name),
                        confirm = getString(R.string.setup_runtime_local_unverified_confirm),
                        onConfirm = { protonExtraUnverified = null; protons.installProtonArchive(id, archive, null) },
                        onDismiss = { protonExtraUnverified = null },
                    )
                }
                // How the one-click install ended, and the way to the log it wrote: the run is long
                // enough that a Toast is not a report (see reportFirstLocalInstall).
                firstLocalReport?.let { report ->
                    com.droiddeck.launcher.ui.FirstInstallReportDialog(
                        text = report,
                        onSave = { saveFirstLocalLogs() },
                        onDismiss = { firstLocalReport = null },
                    )
                }
                flood?.let { f -> com.droiddeck.launcher.ui.LaunchFlood(f.from, onProgress = { floodProgress = it }) { launchFlooded(f) } }
                returning?.let { r ->
                    com.droiddeck.launcher.ui.FloodReturn(androidx.compose.ui.graphics.Color(r.color), r.to, onProgress = { floodProgress = it }) {
                        returning = null
                        floodProgress = 0f
                        com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
                    }
                }
            }
        }
        }

        if (savedInstanceState == null) ui.post { startSteamAtStartupIfEnabled() }

        // The session's logs land in Downloads so a failed run can be handed over as a folder
        // rather than dug out of app-private storage. targetSdk 28 means the old permission still
        // grants exactly that.
        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // The microphone is off by default. Someone who turned it on before the permission was
        // granted is asked once here, with the storage prompt; a refusal is not asked again - the
        // toggle asks when used.
        if (SessionPrefs.micEnabled(this) && !SessionPrefs.micAsked(this)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
            SessionPrefs.setMicAsked(this)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        // A store sign-in an earlier build left in plain text is sealed with the Keystore key.
        Thread({ com.droiddeck.launcher.stores.StoreAccounts.encryptAll(this) }, "store-credentials").start()
        // A session folder left without its ending - the process was killed - gets it now.
        if (!SessionState.running) Thread({
            com.droiddeck.launcher.session.LogMigration.run(this)
            com.droiddeck.launcher.frontend.GamesFolderMigration.run(this)
            SessionLogShare.clear(this)
            SessionArtifacts.finishAbandoned(this)
            SessionArtifacts.scrubOlder(this)
            SessionArtifacts.prune(this)
        }, "finish-abandoned").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readGameIntent(intent)
        refresh()
        startSteamAtStartupIfEnabled()
    }

    private fun startSteamAtStartupIfEnabled() {
        if (intent.action != Intent.ACTION_MAIN) return
        if (SessionPrefs.runSteamAtStartup(this) && !isFinishing && !isDestroyed) startSteamSession()
    }

    private fun startSteamSession() {
        startSession(Intent(this, SessionActivity::class.java), steamSession = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyLauncherFullscreen()
    }

    private fun applyLauncherFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (launcherFullscreen) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (launcherFullscreen) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    /** On the Components page the pad's LB / RB step through the stack's layers, wrapping around. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) com.droiddeck.launcher.ui.HeldKeys.confirm = event.action == KeyEvent.ACTION_DOWN
        if (showComponents && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                components.compComp = com.droiddeck.launcher.ui.layerStep(components.compComp, if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
            }
            return true
        }
        // Compose throws when its focus tree has lost the focused node; one key is dropped
        // instead of the app.
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("active focus target") != true) throw e
            android.util.Log.w("MainActivity", "key ${event.keyCode} dropped: ${e.message}")
            true
        }
    }

    override fun onResume() {
        super.onResume()
        orientation = SessionPrefs.orientation(this)
        requestedOrientation = orientation
        refreshWifiDiscovery()
        // Back from a store's sign-in page: the Stores chips show the account at once. A store
        // install finishing while this screen is up rebuilds the Games list through the listener.
        com.droiddeck.launcher.stores.StoresState.refresh(this)
        com.droiddeck.launcher.stores.StoresState.libraryListener = { refreshAddedGames(); refresh() }
        com.droiddeck.launcher.ui.Motion.refresh(this)
        // Back from a session stopped behind a flood: open on its blue, before the first frame.
        com.droiddeck.launcher.ui.QuitFlood.take()?.let { c ->
            returning = ReturningFlood(c, com.droiddeck.launcher.ui.LaunchOrigin.takeReturn())
        }
        refreshPhantomStatus()
        syncLossless()
        // Opening the app and coming back from a session both land here.
        updates.onResume()
        // Auto keeps this GPU's recommended driver pair installed and set: once per app start.
        drivers.ensureAuto(force = false)
        // Newer GE-Proton / proton-cachyos / DroidDeck-Proton releases: looked up every few hours at most.
        protons.checkUpdates()
        // Swaps queued while a game ran on that Proton go in once nothing uses it (usually the
        // session has just ended). Cheap when nothing is queued.
        if (!SessionState.running) Thread({
            val applied = runCatching { ComponentsManager.applyQueued(this) }.getOrDefault(emptyList())
            if (applied.isNotEmpty()) ui.post {
                android.widget.Toast.makeText(this, getString(R.string.main_applied, applied.joinToString(", ")), android.widget.Toast.LENGTH_LONG).show()
            }
            ui.post { if (showComponents) components.refreshComponents() }
        }, "components-queue").start()
        else if (showComponents) components.refreshComponents()
        oscMode = SessionPrefs.oscMode(this)
        refreshController()
        refreshHomeAppState()
        refreshSecondScreenDisplays()
        refresh()
        decky.deckyInstalled = DeckyManager.installed(this)
        DeckyManager.syncCefMarker(this)
        decky.deckySupervisor = DeckyManager.supervisorEnabled(this)
        // Added games' art (a store lookup for what the folders lack) starts here, not only when
        // the cog opens.
        refreshAddedGames()
        if (catalog == null) loadDesktopCatalog()
        if (!busy) Thread({ checkCatalog() }, "catalog").start()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(wifiLocationReceiver, android.content.IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION))
        registerReceiver(mediaReceiver, android.content.IntentFilter().apply {
            for (action in listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_BAD_REMOVAL)) addAction(action)
            addDataScheme("file")
        })
        displayManager.registerDisplayListener(secondScreenDisplayListener, ui)
        refreshSecondScreenDisplays()
    }

    override fun onDestroy() {
        updates.unregister()
        super.onDestroy()
    }

    override fun onStop() {
        unregisterReceiver(wifiLocationReceiver)
        unregisterReceiver(mediaReceiver)
        ui.removeCallbacks(mediaRefresh)
        // An install finishing while this screen is away is picked up by onResume's refresh.
        com.droiddeck.launcher.stores.StoresState.libraryListener = null
        displayManager.unregisterDisplayListener(secondScreenDisplayListener)
        // The session covers the page by now; coming back finds it as it was.
        flood = null
        returning = null
        floodProgress = 0f
        com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
        super.onStop()
    }

    private fun refreshHomeAppState() {
        homeScreenEnabled = HomeApp.isHomeScreenEnabled(this)
        homeAppSelected = homeScreenEnabled && HomeApp.isDefault(this)
        defaultHomeLabel = HomeApp.defaultLabel(this)
        androidApps = if (homeAppSelected) HomeApp.launchableApps(this) else emptyList()
    }

    private fun refreshSecondScreenDisplays() {
        if (!::displayManager.isInitialized) return
        secondScreenDisplays = SecondScreenDisplays.available(displayManager)
    }

    private fun launchAndroidApp(app: HomeApp.LaunchableApp, displayId: Int?) {
        try {
            HomeApp.launch(this, app, displayId)
        } catch (_: Exception) {
            val label = secondScreenDisplays.firstOrNull { it.id == displayId }?.label
            val message = when {
                displayId == null || displayId == Display.DEFAULT_DISPLAY -> getString(R.string.android_app_open_failed_primary, app.label)
                label != null -> getString(R.string.android_app_open_failed_on, app.label, label)
                else -> getString(R.string.android_app_open_failed_display, app.label, displayId)
            }
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun manageHomeApp() {
        val request = HomeApp.roleRequestIntent(this)
        if (request != null) {
            homeRoleRequest.launch(request)
        } else {
            HomeApp.openSystemHomeSettings(this)
        }
    }

    private fun loadDesktopCatalog() {
        if (catalogLoading || catalog != null) return
        catalogLoading = true
        Thread({
            val fetched = DesktopCatalog.fetch()
            ui.post {
                catalog = fetched
                catalogLoading = false
                refreshPackages()
            }
        }, "catalog-desktop").start()
    }

    /** Runs a save import or export off the main thread, one at a time, and says how it went. */
    private fun saveAction(label: String, work: () -> String) {
        if (busy || LinuxRuntimeInstaller.isBusy() || saveBusy != null) return
        saveBusy = label
        Thread({
            val message = runCatching(work).getOrElse { e -> getString(R.string.main_action_failed, label, e.message ?: e.javaClass.simpleName) }
            ui.post {
                saveBusy = null
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "game-saves-action").start()
    }

    /** The Protons DroidDeck installs are the Components page's Proton layer. */
    private fun openProtons() = openComponents(focusContent = true, tab = com.droiddeck.launcher.ui.PROTON_TAB)

    private fun refreshController() {
        controllerSettings = ControllerPrefs.read(this)
    }

    @Composable
    private fun MappingHost() {
        val settings = controllerSettings ?: return
        ControllerMappingPage(
            mapping = settings.mapping,
            onPick = { id, target -> ControllerPrefs.setTarget(this, id, target); refreshController() },
            onReset = { ControllerPrefs.resetMapping(this); refreshController() },
            onBack = { showMapping = false },
        )
    }

    private fun openComponents(focusContent: Boolean = true, tab: String? = null) {
        focusComponentsContent = focusContent
        if (tab != null) components.compComp = tab
        showPerformance = false
        showMapping = false
        showComponents = true
        // The Proton layer's options for every Proton, as they are now.
        fexPreset = SessionPrefs.fexPreset(this)
        forceSsbs = SessionPrefs.forceSsbs(this)
        fastSync = SessionPrefs.fastSync(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        syncFallback = SessionPrefs.syncFallback(this)
        components.refreshComponents(snapshotFirst = true)
        drivers.refreshDrivers()
        protons.refreshProtons()
        protons.checkUpdates()
    }

    private fun importComponent(uri: Uri) {
        val name = displayNameOf(uri) ?: "imported.wcp"
        components.componentAction(getString(R.string.comp_busy_importing)) {
            val tmp = File(cacheDir, "component-import.wcp")
            contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error(getString(R.string.comp_cannot_read))
            try { getString(R.string.comp_imported, ComponentsManager.importPackage(this, tmp, name).version) } finally { tmp.delete() }
        }
    }

    @Composable
    private fun ComponentsHost() {
        ComponentsPage(
            snapshot = components.compSnapshot,
            catalog = components.compCatalog,
            catalogAt = components.compCatalogAt,
            protonId = components.compProton,
            comp = components.compComp,
            checking = components.compChecking,
            busy = if (busy) stage else components.compBusy,
            downloads = components.compDownloads,
            requestInitialFocus = focusComponentsContent,
            onProton = { components.chooseProton(it) },
            onComp = { components.compComp = it },
            onSwap = { file -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_swapping)) { ComponentsManager.swap(this, pid, file) } } },
            onRestore = { version -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_restoring)) { ComponentsManager.restore(this, pid, components.compComp, version) } } },
            onCancelQueued = { components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_cancelling)) { ComponentsManager.cancelQueued(this, pid, components.compComp); getString(R.string.comp_swap_cancelled) } } },
            onDeletePackage = { file -> components.componentAction(getString(R.string.comp_busy_deleting)) { ComponentsManager.deletePackage(this, file) } },
            onDeleteOriginal = { version -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_deleting)) { ComponentsManager.deleteOriginal(this, pid, components.compComp, version) } } },
            onDownload = { components.downloadComponent(it) },
            onRefresh = { components.refreshComponentCatalog() },
            onImport = { pickComponent.launch(InAppFilePicker.buildIntent(this, WCP_EXT, getString(R.string.comp_pick_wcp))) },
            // Reached by a hop (a game's Components card, Play's Graphics drivers): back the way it came.
            onBack = { if (!com.droiddeck.launcher.ui.Hops.back { showComponents = false }) showComponents = false },
            games = steamGames,
            generalFexPreset = fexPreset,
            onGeneralFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
            gpu = drivers.state(),
            gpuActions = com.droiddeck.launcher.ui.GpuDriversActions(
                onAuto = { on -> drivers.setMode(on) },
                onPair = { key -> drivers.selectPair(key) },
                onRefresh = { if (drivers.mode == SessionPrefs.GPU_DRIVERS_AUTO) drivers.ensureAuto(force = true) else drivers.checkLatestTurnip() },
                // Picking one driver on its own is Manual: Auto would put its pair back.
                onSelectLinux = { id -> drivers.selectDriver(id, linux = true) },
                onSelectAndroid = { id -> drivers.selectDriver(id, linux = false) },
                onRemoveLinux = { id -> drivers.deleteDriver(id, linux = true) },
                onRemoveAndroid = { id -> drivers.deleteDriver(id, linux = false) },
                onDownloadDriver = { name -> drivers.downloadReleaseDriver(name) },
                onImportLinux = { pickLinuxDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_linux))) },
                onImportAndroid = { pickAndroidDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_android))) },
                onImportZip = { pickAnyDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_any))) },
                onExportDrivers = { exportDrivers() },
                onRestoreBundled = { TurnipDriver(this).restoreBundled(); drivers.refreshDrivers() },
            ),
            protons = com.droiddeck.launcher.ui.ProtonsState(
                rows = protons.protonRows, busyId = protons.protonBusyId, stage = protons.protonStage, percent = protons.protonPercent,
                runtimeReady = ready && !busy, sessionRunning = SessionState.running,
            ),
            protonActions = com.droiddeck.launcher.ui.ProtonsActions(
                onInstall = { id -> protons.installProton(id) },
                // A build carried over by hand: fetched on a computer, put on the device, picked
                // here. The picker opens where such a file usually lands.
                onInstallFromFile = { id ->
                    protonFilePick = id
                    val tool = ProtonExtras.tools.firstOrNull { it.id == id }
                    pickProtonArchive.launch(InAppFilePicker.buildIntent(
                        this, listOf("gz", "xz"),
                        getString(R.string.proton_local_pick, tool?.name ?: id),
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS).path,
                    ))
                },
                onCancel = { id -> ProtonExtras.tools.firstOrNull { it.id == id }?.let { ProtonExtras.unqueue(this, it) }; protons.refreshProtons() },
                onRemove = { id, dir -> protons.removeProton(id, dir) },
                onRemoveOlder = { id -> protons.removeProton(id, older = true) },
            ),
            protonOptions = {
                com.droiddeck.launcher.ui.ProtonOptions(
                    syncBackend = SessionPrefs.syncBackendOf(fastSync, fsyncFirst, syncFallback), fexPreset = fexPreset, forceSsbs = forceSsbs,
                    onSyncBackend = { id ->
                        SessionPrefs.setSyncBackend(this, id)
                        fastSync = SessionPrefs.fastSync(this)
                        fsyncFirst = SessionPrefs.fsyncFirst(this)
                        syncFallback = SessionPrefs.syncFallback(this)
                    },
                    onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                    onForceSsbs = { on -> SessionPrefs.setForceSsbs(this, on); forceSsbs = on },
                )
            },
        )
        // A Proton installed or removed changes the stack's list of Protons.
        androidx.compose.runtime.LaunchedEffect(protons.protonBusyId) { if (protons.protonBusyId == null) components.refreshComponents() }
    }

    private fun refreshPackages() {
        packageRows = catalog?.map { PackageRow(it.id, it.kind, it.notes) }
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        desktopPresent = DesktopCatalog.desktopPresent(this)
    }

    private fun launchProgram(path: String) {
        startActivity(Intent(this, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, path))
    }

    private fun installPackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (busy || LinuxRuntimeInstaller.isBusy() || pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = getString(R.string.store_starting); pkgPercent = -1
        Thread({
            val problem = DesktopCatalog.install(this, entry) { stage, percent ->
                ui.post { pkgStage = stage; pkgPercent = percent }
            }
            ui.post {
                pkgStage = null; pkgId = null
                if (problem != null) android.widget.Toast.makeText(this, getString(R.string.main_pkg_problem, entry.name, problem), android.widget.Toast.LENGTH_LONG).show()
                refreshPackages()
                refresh()
            }
        }, "install-pkg").start()
    }

    private fun removePackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (busy || LinuxRuntimeInstaller.isBusy() || pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = getString(if (entry.kind == "appimage") R.string.main_removing_named else R.string.main_forgetting_named, entry.name); pkgPercent = -1
        Thread({
            DesktopCatalog.remove(this, entry)
            ui.post {
                pkgStage = null; pkgId = null
                refreshPackages()
                refresh()
            }
        }, "remove-pkg").start()
    }

    @Composable
    private fun ModeSheetHost(mode: String, tab: String, extras: com.droiddeck.launcher.ui.SheetExtras) {
        // Read for the other mode last (or not yet): what this one shows is read as it opens.
        if (settingsMode != mode) return
        com.droiddeck.launcher.ui.ModeSheetRows(
            ModeSettings(
                mode = mode, resolution = resolution,
                panelSize = com.droiddeck.launcher.session.SessionDisplay.panelSize(this),
                hdr = hdrOn, hdrReason = hdrReason, fpsLimit = fpsLimit,
                upscaler = upscaler, upscaleSharpness = upscaleSharpness,
                gpuDrivers = drivers.summary(),
                touchMode = touchMode,
                suspendPolicy = suspendPolicy,
                steamDownloadsInBackground = steamDownloadsInBackground,
                pipSupported = com.droiddeck.launcher.session.SessionPipController.supported(this),
                pipAutoEnter = pipAutoEnter,
                unfoldedControls = if (mode == SessionService.MODE_STEAM &&
                    com.droiddeck.launcher.session.SessionDisplay.foldable(this)) unfoldedControls else null,
                oscMode = if (mode == SessionService.MODE_STEAM) oscMode else null,
                backActionsInverted = backActionsInverted,
                directAudio = if (mode == SessionService.MODE_STEAM) directAudio else null,
                stretch16x9 = if (mode == SessionService.MODE_STEAM) stretch16x9 else null,
                mic = if (mode == SessionService.MODE_STEAM) mic else null,
                gameStorage = if (mode == SessionService.MODE_STEAM) gameStorage else null,
                storageOptions = storageOptions,
                storageDiagnostics = mode == SessionService.MODE_STEAM && storageDiagnostics,
                fexPreset = if (mode == SessionService.MODE_STEAM) fexPreset else null,
                forceSsbs = forceSsbs,
                syncBackend = if (mode == SessionService.MODE_STEAM) SessionPrefs.syncBackendOf(fastSync, fsyncFirst, syncFallback) else null,
                buildStamp = buildStamp,
                steamRepairQueued = steamRepairQueued,
                mangoapp = mangoapp,
                steamController = if (mode == SessionService.MODE_STEAM) steamController else null,
                runSteamAtStartup = mode == SessionService.MODE_STEAM && runSteamAtStartup,
                wifiDiscovery = if (mode == SessionService.MODE_STEAM) wifiDiscovery else null,
                wifiDiscoveryPermission = wifiDiscoveryPermission,
                wifiDiscoveryLocation = wifiDiscoveryLocation,
                wifiDiscoveryAsked = wifiDiscoveryAsked,
                wifiDiscoveryBlocked = wifiDiscoveryBlocked,
                deckyInstalled = if (mode == SessionService.MODE_STEAM) decky.deckyInstalled else null,
                deckyLatestRelease = if (mode == SessionService.MODE_STEAM) decky.deckyReleases.firstOrNull() else null,
                deckyChecking = decky.deckyChecking, deckyStage = decky.deckyStage, deckyPercent = decky.deckyPercent,
                deckyDownloadingBinary = decky.deckyDownloadingBinary,
                deckyEnabled = decky.deckySupervisor, deckySessionRunning = SessionState.running,
                steamClientPresent = mode != SessionService.MODE_STEAM || steamClientPresent,
                steamClientStage = steamClientStage, steamClientPercent = steamClientPercent,
                steamClientBlocked = runtimeChangesBlocked(),
                protonSeedPresent = mode != SessionService.MODE_STEAM || protonSeedPresent,
                protonSeedStage = protonSeedStage, protonSeedPercent = protonSeedPercent,
                protonSeedBlocked = runtimeChangesBlocked(),
            ),
            ModeSettingsActions(
                onWifiDiscovery = { on ->
                    if (!on) {
                        SessionPrefs.setWifiDiscoveryEnabled(this, false)
                        refreshWifiDiscovery()
                    } else if (WifiDiscovery.permissionGranted(this)) {
                        SessionPrefs.setWifiDiscoveryEnabled(this, true)
                        refreshWifiDiscovery()
                    } else if (wifiDiscoveryBlocked) {
                        openWifiDiscoverySettings()
                    } else {
                        SessionPrefs.setWifiDiscoveryAsked(this)
                        wifiLocationPermission.launch(WifiDiscovery.permissions)
                    }
                },
                onWifiDiscoverySettings = { openWifiDiscoverySettings() },
                onResolution = { value -> SessionPrefs.setResolutionChoice(this, mode, value); resolution = value },
                onHdr = { on -> SessionPrefs.setHdr(this, mode, on); hdrOn = on },
                onGpuDrivers = { openComponents(focusContent = true, tab = com.droiddeck.launcher.ui.GPU_TAB) },
                onFpsLimit = { fps -> SessionPrefs.setFpsLimit(this, mode, fps); fpsLimit = fps },
                onUpscaler = { m -> SessionPrefs.setUpscaler(this, m); upscaler = m },
                onUpscaleSharpness = { pct -> SessionPrefs.setUpscaleSharpness(this, pct); upscaleSharpness = pct },
                onTouch = { t -> SessionPrefs.setTouchMode(this, t); touchMode = t },
                onSuspendPolicy = { policy -> SessionPrefs.setSuspendPolicy(this, mode, policy); suspendPolicy = policy },
                onSteamDownloadsInBackground = { enabled ->
                    SessionPrefs.setSteamDownloadsInBackground(this, enabled)
                    steamDownloadsInBackground = enabled
                },
                onPipAutoEnter = { on -> SessionPrefs.setPipAutoEnter(this, on); pipAutoEnter = on },
                onUnfoldedControls = { on -> SessionPrefs.setUnfoldedControls(this, on); unfoldedControls = on },
                onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                onBackActionsInverted = { inverted ->
                    SessionPrefs.setBackActionsInverted(this, inverted)
                    backActionsInverted = inverted
                },
                onDirectAudio = { on -> SessionPrefs.setDirectAudio(this, on); directAudio = on },
                onStretch16x9 = { on -> SessionPrefs.setStretch16x9(this, on); stretch16x9 = on },
                onMic = { on ->
                    SessionPrefs.setMicEnabled(this, on)
                    mic = on
                    // The session checks the grant itself at start; asking here means the
                    // answer is in before the first session that wants it.
                    if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    }
                },
                onGameStorage = { path, label -> setGameStorage(path, label) },
                onPickGameStorageFolder = {
                    pickGameStorage.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_pick_game_storage), gameStorage.ifEmpty { null }))
                },
                onStorageDiagnostics = { on ->
                    SessionPrefs.setStorageDiagnosticsEnabled(this, on)
                    storageDiagnostics = on
                },
                onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                onForceSsbs = { on -> SessionPrefs.setForceSsbs(this, on); forceSsbs = on },
                onSyncBackend = { id ->
                    SessionPrefs.setSyncBackend(this, id)
                    fastSync = SessionPrefs.fastSync(this)
                    fsyncFirst = SessionPrefs.fsyncFirst(this)
                    syncFallback = SessionPrefs.syncFallback(this)
                },
                onSteamRepair = { steamRepairQueued = SteamRepair.queue(this) },
                // The packages are fetched on another machine and copied over as a folder, so the
                // picker starts where a transfer usually lands.
                onInstallSteamClientFromDir = {
                    pickSteamClientDir.launch(InAppFilePicker.buildDirIntent(
                        this, getString(R.string.mode_steam_client_local_pick),
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS).path,
                    ))
                },
                // The archive is fetched elsewhere and put on the device, so the picker opens
                // where it usually lands.
                onInstallProtonFromFile = {
                    pickProtonSeed.launch(InAppFilePicker.buildIntent(
                        this, listOf("zst"), getString(R.string.mode_proton_arm64_local_pick),
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS).path,
                    ))
                },
                onMangoapp = { on -> SessionPrefs.setMangoapp(this, on); mangoapp = on },
                onSteamController = { id -> SessionPrefs.setSteamController(this, id); steamController = id },
                onRunSteamAtStartup = { on ->
                    SessionPrefs.setRunSteamAtStartup(this, on)
                    runSteamAtStartup = on
                },
                onDeckyInstall = { release -> decky.installDecky(release) },
                onDeckyCheck = { decky.refreshDecky() },
                onDeckyEnabled = { enabled -> DeckyManager.setSupervisorEnabled(this, enabled); decky.deckySupervisor = enabled },
                onDeckyUninstall = {
                    DeckyManager.uninstall(this, wipeData = false)
                    decky.deckyInstalled = null
                    decky.deckySupervisor = false
                },
                onPickDeckyPluginZip = {
                    pickDeckyPluginZip.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.main_pick_decky_zip)))
                },
                onDismiss = {},
            ),
            tab, extras,
        )
    }

    @Composable
    private fun PerformanceHost() {
        PerformancePage(
            cores = CpuCores.all.map { c -> CoreRow(c, "cpu$c" + (CpuCores.maxGhz(c)?.let { String.format(java.util.Locale.US, " · %.1f GHz", it) } ?: "")) },
            clientOverride = clientOverride, clientCores = clientCores, gameCores = gameCores,
            tuSysmem = tuSysmem, zinkLazy = zinkLazy, glThread = glThread, noGlError = noGlError, noXalia = noXalia,
            fastSync = fastSync,
            syncFallback = syncFallback,
            fsyncFirst = fsyncFirst,
            gamescopeRealtime = gamescopeRealtime,
            gpuClockPin = gpuClockPin,
            prootNoSeccomp = prootNoSeccomp, prootFastPath = prootFastPath, guestHostname = guestHostname, phantomWarning = phantomWarning,
            onClientOverride = { on -> SessionPrefs.setClientCpusOverride(this, on); clientOverride = on },
            onTuSysmem = { on -> SessionPrefs.setTuSysmem(this, on); tuSysmem = on },
            onZinkLazy = { on -> SessionPrefs.setZinkLazy(this, on); zinkLazy = on },
            onGlThread = { on -> SessionPrefs.setGlThread(this, on); glThread = on },
            onNoGlError = { on -> SessionPrefs.setNoGlError(this, on); noGlError = on },
            onNoXalia = { on -> SessionPrefs.setNoXalia(this, on); noXalia = on },
            onFastSync = { on -> SessionPrefs.setFastSync(this, on); fastSync = on },
            onSyncFallback = { on -> SessionPrefs.setSyncFallback(this, on); syncFallback = on },
            onFsyncFirst = { on -> SessionPrefs.setFsyncFirst(this, on); fsyncFirst = on },
            onGamescopeRealtime = { on -> SessionPrefs.setGamescopeRealtime(this, on); gamescopeRealtime = on },
            onGpuClockPin = { on -> SessionPrefs.setGpuClockPin(this, on); gpuClockPin = on },
            onProotNoSeccomp = { on -> SessionPrefs.setProotNoSeccomp(this, on); prootNoSeccomp = on },
            onProotFastPath = { on -> SessionPrefs.setProotFastPath(this, on); prootFastPath = on },
            onGuestHostname = { name -> SessionPrefs.setGuestHostname(this, name) },
            onClientCore = { core, on ->
                clientCores = if (on) clientCores + core else clientCores - core
                SessionPrefs.setClientCpus(this, CpuCores.format(clientCores))
            },
            onGameCore = { core, on ->
                gameCores = if (on) gameCores + core else gameCores - core
                SessionPrefs.setGameCpus(this, CpuCores.format(gameCores))
            },
            onDismiss = { showPerformance = false },
        )
    }

    /**
     * The Games tab after these added games were added or edited: each read alone and put in
     * place of its old entry (or added), no library-wide scan. [then] runs after, on the main thread.
     */
    private fun updateAddedGames(folders: List<String>, then: () -> Unit = {}) {
        if (folders.isEmpty()) return
        Thread({
            val fresh = folders.mapNotNull { f ->
                com.droiddeck.launcher.frontend.AddedGames.single(this, f)?.let { com.droiddeck.launcher.frontend.Library.addedGame(this, it) }
            }
            // An added or edited game's Windows components, from its source's list.
            fresh.forEach { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(this, it) }
            ui.post {
                val paths = folders.toSet()
                steamGames = (steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path in paths } + fresh).distinctBy { it.gameId }
                then()
            }
        }, "added-games-update").start()
    }

    /** An added game removed: off the Games tab now; the rest (cache, Steam, listing) off the main thread. */
    private fun removeAddedGame(folder: String, appId: Long?, name: String) {
        steamGames = steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path == folder }
        Thread({ com.droiddeck.launcher.frontend.AddedGames.remove(this, java.io.File(folder), appId, name) }, "added-game-remove").start()
    }

    /** After the added games changed: art that is missing is fetched (off the main thread). */
    private fun refreshAddedGames() {
        sgdbKeyKnown()
        fetchAddedGameArt()
    }

    /** Whether the user has a SteamGridDB key of their own, for Setup; read off the main thread. */
    private fun sgdbKeyKnown() {
        Thread({
            val set = com.droiddeck.launcher.frontend.SteamGridDb.userKey(this).isNotEmpty()
            ui.post { sgdbUserKey = set }
        }, "sgdb-key-state").start()
    }

    private fun fetchAddedGameArt() {
        // Art the games do not have yet, from Steam's store, off the main thread; the rail
        // redraws when something arrives.
        if (SessionPrefs.addedGamesArt(this) && !artFetchRunning) {
            artFetchRunning = true
            Thread({
                try {
                    val games = com.droiddeck.launcher.frontend.AddedGames.scan(this)
                    if (com.droiddeck.launcher.frontend.AddedGameArt.fetchMissing(this, games)) ui.post { refresh() }
                } finally {
                    artFetchRunning = false
                }
            }, "added-art").start()
        }
    }

    private var pipAutoEnter by mutableStateOf(false)
    private var unfoldedControls by mutableStateOf(false)

    private fun loadModeSettings(mode: String) {
        pipAutoEnter = SessionPrefs.pipAutoEnter(this)
        unfoldedControls = SessionPrefs.unfoldedControls(this)
        resolution = SessionPrefs.resolutionChoice(this, mode, com.droiddeck.launcher.session.SessionDisplay.panelSize(this))
        fexPreset = SessionPrefs.fexPreset(this)
        forceSsbs = SessionPrefs.forceSsbs(this)
        fastSync = SessionPrefs.fastSync(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        syncFallback = SessionPrefs.syncFallback(this)
        steamRepairQueued = SteamRepair.queued(this)
        mangoapp = SessionPrefs.mangoapp(this)
        steamController = SessionPrefs.steamController(this)
        runSteamAtStartup = SessionPrefs.runSteamAtStartup(this)
        hdrOn = SessionPrefs.hdr(this, mode)
        fpsLimit = SessionPrefs.fpsLimit(this, mode)
        upscaler = SessionPrefs.upscaler(this)
        upscaleSharpness = SessionPrefs.upscaleSharpness(this)
        hdrReason = com.droiddeck.launcher.wayland.HdrSupport.probe(this).reason
        touchMode = SessionPrefs.touchMode(this)
        suspendPolicy = SessionPrefs.suspendPolicy(this, mode)
        steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
        oscMode = SessionPrefs.oscMode(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        directAudio = SessionPrefs.directAudio(this)
        stretch16x9 = SessionPrefs.stretch16x9(this)
        mic = SessionPrefs.micEnabled(this)
        refreshWifiDiscovery()
        gameStorage = SessionPrefs.gameStorage(this)
        storageDiagnostics = SessionPrefs.storageDiagnosticsEnabled(this)
        settingsMode = mode
        // The sheet lifts at once, on what was last read; the slow part (driver files, a walk of the
        // added-games folders, the storage volumes) lands while it rises.
        Thread({
            drivers.refreshDrivers()
            val storage = GameStorage.options(this)
            val deckyInstalled = if (mode == SessionService.MODE_STEAM) DeckyManager.installed(this) else null
            val deckySupervisor = mode == SessionService.MODE_STEAM && DeckyManager.supervisorEnabled(this)
            val seedNeeded = mode == SessionService.MODE_STEAM && DesktopCatalog.protonSeedNeeded(this)
            // Read off the runtime, so the row appears on a device whose client was never installed
            // - which is the only case it is for.
            val clientInstalled = SteamClient.installed(this)
            ui.post {
                storageOptions = storage
                if (mode == SessionService.MODE_STEAM) {
                    steamClientPresent = clientInstalled
                    protonSeedPresent = !seedNeeded
                    decky.deckyInstalled = deckyInstalled
                    decky.deckySupervisor = deckySupervisor
                    decky.refreshDecky()
                }
                fetchAddedGameArt()
            }
        }, "mode-settings").start()
    }

    /** A second Steam library, proven writable first; "" = internal only. */
    private fun setGameStorage(path: String, label: String) {
        if (path.isNotEmpty() && path != SessionPrefs.GAME_STORAGE_OFF) {
            val problem = GameStorage.prepare(this, path)
            if (problem != null) {
                android.widget.Toast.makeText(this, getString(R.string.main_storage_not_usable, problem), android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        SessionPrefs.setGameStorage(this, path, label)
        gameStorage = path
    }

    /** The two masks as the dialog shows them; an empty stored list shows as every core ticked. */
    private fun refreshCores() {
        clientOverride = SessionPrefs.clientCpusOverride(this)
        glThread = SessionPrefs.glThread(this)
        noGlError = SessionPrefs.noGlError(this)
        clientCores = CpuCores.parse(SessionPrefs.clientCpus(this)).ifEmpty { CpuCores.all.toSet() }
        gameCores = CpuCores.parse(SessionPrefs.gameCpus(this)).ifEmpty { CpuCores.all.toSet() }
        tuSysmem = SessionPrefs.tuSysmem(this)
        zinkLazy = SessionPrefs.zinkLazy(this)
        noXalia = SessionPrefs.noXalia(this)
        fastSync = SessionPrefs.fastSync(this)
        syncFallback = SessionPrefs.syncFallback(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        gamescopeRealtime = SessionPrefs.gamescopeRealtime(this)
        gpuClockPin = SessionPrefs.gpuClockPin(this)
        prootNoSeccomp = SessionPrefs.prootNoSeccomp(this)
        prootFastPath = SessionPrefs.prootFastPath(this)
        guestHostname = SessionPrefs.guestHostname(this)
        refreshPhantomStatus()
    }

    private fun refresh() {
        if (!busy && LinuxRuntimeInstaller.isBusy()) followRuntimeOperation { LinuxRuntimeInstaller.attach(it) }
        addedGamesArt = SessionPrefs.addedGamesArt(this)
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        desktopPresent = DesktopCatalog.desktopPresent(this)
        offlineAccount = OfflineMode.account(this)
        offline = OfflineMode.enabled(this)
        installed = LinuxRuntimeInstaller.installedVersion(this)
        ready = LinuxRuntime.isInstalled(this)
        removalPending = LinuxRuntimeInstaller.hasRemovalPending(this)
        frameGenLabel = FrameGen.label(this)
        romsDir = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }
        logsEnabled = SessionPrefs.logsEnabled(this)
        downloadSource = DownloadSource.current(this)
        steamCdn = com.droiddeck.launcher.session.SteamCdn.current(this)
        agentCommands = com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this)
        runningLabel = if (SessionState.running) when (SessionState.mode) {
            SessionService.MODE_DESKTOP -> getString(R.string.rail_desktop)
            SessionService.MODE_RUN -> Library.nameForProgram(SessionState.program)
                ?: SessionState.program?.substringAfterLast('/')?.substringBefore('.') ?: getString(R.string.main_running_program)
            else -> "Steam"
        } else null
        // The libraries, off the main thread: manifests and a folder scan.
        val scanGeneration = ++libraryScanGeneration
        if (shortcutPicker) shortcutLibraryScanning = true
        Thread({
            // The emulators first: they need no walk of the games, and Desktop's dock is made of them.
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { if (scanGeneration == libraryScanGeneration) emulatorList = emus }
            // Steam's own games next, from their manifests alone (quick), beside the added games
            // already listed: a game Steam installed during the session shows as the session ends,
            // without waiting for the added games' walk below. Its Windows components' list is
            // queued then too, on the low-priority worker, so its page is usually ready.
            if (ready) {
                val quick = runCatching { Library.steamGames(this) }.getOrNull()
                if (quick != null) ui.post {
                    if (scanGeneration == libraryScanGeneration && steamGames.isNotEmpty()) {
                        val before = steamGames.map { it.gameId }.toSet()
                        val fresh = quick.filter { it.gameId !in before }
                        if (fresh.isNotEmpty()) {
                            steamGames = (quick + steamGames.filter { it.library == Library.ADDED }).distinctBy { it.gameId }
                            fresh.forEach { com.droiddeck.launcher.frontend.AutoComponents.queueGame(this, it, asked = false) }
                        }
                    }
                }
            }
            // Then one update with the whole list: the wall places games by their position in it,
            // so a partial list first would shuffle every capsule when the rest arrived.
            // LibraryCache covers the wait.
            val games = if (ready) Library.launchableGames(this) else emptyList()
            val all = games.distinctBy { it.gameId }
            // Whether the play page's first-time install button belongs: the things a session
            // cannot do without, then the remembered folder's own contents. Off the main thread,
            // as reading that folder is a listing.
            val firstLocal = FirstLocalInstall.needed(this)
            if (ready) com.droiddeck.launcher.frontend.LibraryCache.save(this, all)
            // Once per app start: the Steam games' Windows components from Steam's lists.
            // Queued only, after the launcher has been up a little; the worker takes them one at a time.
            // And again after each session (a game installed in it, files changed).
            val endedSession = SessionState.sessionId.takeIf { !SessionState.running && it != null && it != componentSweepSession }
            if (ready && (!componentSweepDone || endedSession != null)) {
                componentSweepDone = true
                endedSession?.let { componentSweepSession = it }
                ui.postDelayed({ Thread({ com.droiddeck.launcher.frontend.AutoComponents.sweep(this, all) }, "components-sweep").start() }, 10_000)
            }
            ui.post {
                firstLocalNeeded = firstLocal
                if (scanGeneration == libraryScanGeneration) {
                    steamGames = all
                    shortcutLibraryScanning = false
                    handleGameLink(all)
                }
            }
            // Box art for the games that have none, fetched after the list is up; the list is
            // rebuilt once if any was found.
            if (!OfflineMode.enabled(this) && CoverArt.fetchMissing(this, emus.flatMap { it.games })) {
                val refreshed = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
                ui.post { emulatorList = refreshed }
            }
        }, "library").start()
    }

    private fun onRuntimeButton() {
        if (runtimeChangesBlocked()) return
        val release = available
        if (removalPending || installed != null && release?.version == installed) {
            // Nothing to install: offer the one destructive thing this screen can do.
            showRemove = true
            return
        }
        if (release == null) {
            Thread({ checkCatalog() }, "catalog").start()
            return
        }
        // The runtime draws with Turnip, an Adreno driver: on Mali, Xclipse or PowerVR the
        // compositor gets no usable Vulkan device and a session is sound over a black screen.
        // Said before the download, not after it; the user may still go ahead.
        if (installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()) { showNonAdreno = release; return }
        install(release)
    }

    /** Starts a session; with no runtime on a non-Adreno, the same warning Setup gives comes first, before any download. */
    private fun startSession(intent: Intent, steamSession: Boolean = false): Boolean {
        if (busy || LinuxRuntimeInstaller.isBusy()) return false
        refreshPhantomStatus()
        if (steamSession && PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            showPhantomGate = true
            return false
        }
        val warn = installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()
        if (warn && available != null) { showNonAdreno = available; return false }
        startActivity(intent)
        return true
    }

    private fun refreshPhantomStatus() {
        phantomProcessStatus = PhantomProcessLimit.read(this)
        phantomWarning = if (PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            getString(
                R.string.phantom_warning, PhantomProcessLimit.title(this, phantomProcessStatus),
                PhantomProcessLimit.instructions(this, phantomProcessStatus), PhantomProcessLimit.adbCommand(),
            )
        } else null
    }

    private fun openDeveloperOptions(displayId: Int?) {
        val highlight = if (PhantomProcessLimit.hasDeveloperToggle() &&
            WirelessAdbPairingService.stage.value == WirelessAdbPairingService.Stage.Idle) null else "toggle_adb_wireless"
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (highlight != null) putExtra(":settings:fragment_args_key", highlight) }
        try {
            if (displayId == null) startActivity(intent)
            else startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (error: Exception) {
            if (displayId != null) {
                runCatching { startActivity(intent) }
                android.widget.Toast.makeText(this, R.string.main_settings_main_screen, android.widget.Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(this, R.string.main_open_dev_options, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runtimeChangesBlocked(): Boolean =
        busy || LinuxRuntimeInstaller.isBusy() || SessionState.running ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
            protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null ||
            steamClientStage != null || protonSeedStage != null ||
            components.compBusy != null || saveBusy != null || com.droiddeck.launcher.store.UserAppsState.working != null

    private fun removeDesktop() {
        if (runtimeChangesBlocked()) return
        val operation = LinuxRuntimeInstaller.beginMaintenance(this, getString(R.string.desktop_removing)) ?: return
        com.droiddeck.launcher.runtime.RuntimeInstallService.keepRemovalAlive(this)
        followRuntimeOperation { listener ->
            operation.run(listener) { progress -> DesktopCatalog.removeDesktop(this, progress) }
        }
    }

    private fun removeRuntime() {
        if (runtimeChangesBlocked()) return
        val removal = LinuxRuntimeInstaller.beginUninstall(this) ?: return
        ready = false
        com.droiddeck.launcher.runtime.RuntimeInstallService.keepRemovalAlive(this)
        followRuntimeOperation { removal.run(it) }
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
        // The service keeps the process alive if the user switches away; this screen joins the
        // same install (or starts it, if it gets there first) to show the progress.
        com.droiddeck.launcher.runtime.RuntimeInstallService.start(this, release)
        followRuntimeOperation { listener -> LinuxRuntimeInstaller.install(this, release, listener) }
    }

    /**
     * Installs a runtime archive the user picked instead of letting the app fetch it: the way in
     * for a network that cannot reach GitHub at all. The catalog carries the checksum, so it is
     * asked for first; when it cannot be reached - the case this path exists for - nothing can be
     * verified, and the user is asked whether to go on rather than told afterwards.
     */
    private fun installRuntimeFrom(archive: File) {
        if (runtimeChangesBlocked()) return
        // Reaching the catalog is the only delay on this path, and on a network that cannot reach
        // GitHub it is the whole set of timeouts; shown, so the pick does not look ignored. It also
        // marks the screen busy, which is what keeps a second pick from starting over it.
        busy = true
        failed = false
        stage = "Checking the catalog…"
        percent = -1
        Thread({
            val release = runCatching { LinuxRuntimeInstaller.fetchRelease() }.getOrNull()
            ui.post {
                busy = false
                if (release == null) showLocalRuntimeUnverified = archive else install(release, archive)
            }
        }, "runtime-catalog").start()
    }

    /** As [install] above, but from an archive already on the device; [catalogue] may be null. */
    private fun install(catalogue: LinuxRuntimeInstaller.Release?, archive: File) {
        com.droiddeck.launcher.runtime.RuntimeInstallService.startLocal(this, archive, catalogue)
        followRuntimeOperation { listener ->
            LinuxRuntimeInstaller.installFromFile(this, archive, catalogue, listener)
        }
    }

    /**
     * Times both of Valve's client-update edges from this network (Setup › Diagnostics). Nothing
     * is pinned or remembered by it: the numbers are shown, and the CDN choice on the Launcher
     * tab is the user's to make from them. The probe touches nothing but the network, and a
     * second tap while one is running is ignored rather than queued.
     */
    private fun runSteamLineTest() {
        if (steamLineTestBusy) return
        steamLineTestBusy = true
        steamLineTestEdges = null
        Thread({
            val edges = SteamCdn.hosts(SteamCdn.AUTO).map { host -> SteamCdnProbe.measure(host) }
            android.util.Log.i("SteamCdn", "line test: " + edges.joinToString { "${it.host} ok=${it.reachable} ${SteamCdnProbe.rateToString(it.bytesPerSecond)}" })
            ui.post {
                steamLineTestBusy = false
                steamLineTestEdges = edges
            }
        }, "steam-line-test").start()
    }

    /**
     * Installs Valve's Steam client from a folder of packages the user fetched elsewhere, for a
     * network that cannot reach Valve's CDN at a usable speed. Nothing is checked here: the folder
     * is handed to the guest, which reads Valve's own manifest out of it and verifies every
     * component against the sha256 that manifest publishes - a local copy and a download alike.
     */
    private fun installSteamClientFrom(dir: File) {
        if (runtimeChangesBlocked()) return
        steamClientStage = "Preparing…"
        steamClientPercent = -1
        Thread({
            // Named, not positional: localOnly sits between the folder and the listener, and this
            // row is the general "from a folder" install - it still takes whatever Valve has that
            // the folder does not. The CDN is the edge the user pinned in Setup, if any.
            val problem = SteamClient.installFromDir(this, dir,
                cdn = SteamCdn.guestValue(steamCdn),
                onProgress = { s, p -> ui.post { steamClientStage = s; steamClientPercent = p } })
            ui.post {
                steamClientStage = null
                steamClientPercent = -1
                // The script's own two-file test is what decides, so a run that failed half-way
                // leaves the row offering the install again.
                if (problem == null) steamClientPresent = SteamClient.installed(this)
                else android.widget.Toast.makeText(this, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "steam-client-install").start()
    }

    /**
     * Installs Valve's Proton (ARM64) from an archive the user picked, for a network that cannot
     * reach GitHub at all. The seed's own catalog carries the checksum, so it is asked for first;
     * when it cannot be reached - the case this path exists for - nothing can be verified, and the
     * user is asked whether to go on rather than told afterwards.
     */
    private fun installProtonSeedFrom(archive: File) {
        if (runtimeChangesBlocked()) return
        // Reaching the catalog is the only delay on this path, and on a network that cannot reach
        // GitHub it is the whole set of timeouts; shown, so the pick does not look ignored. It also
        // marks the screen busy, which is what keeps a second pick from starting over it.
        protonSeedStage = "Checking the catalog…"
        protonSeedPercent = -1
        Thread({
            val entry = runCatching {
                DesktopCatalog.fetch(DesktopCatalog.STEAM_SEED_URL)
                    ?.firstOrNull { it.id == DesktopCatalog.PROTON_SEED_ID }
            }.getOrNull()
            ui.post {
                protonSeedStage = null
                if (entry == null) showLocalProtonUnverified = archive else installProtonSeed(entry, archive)
            }
        }, "proton-seed-catalog").start()
    }

    /** As [installProtonSeedFrom] above, once the checksum - or the user's answer - is in. */
    private fun installProtonSeed(entry: DesktopCatalog.Entry?, archive: File) {
        protonSeedStage = "Preparing…"
        protonSeedPercent = -1
        Thread({
            val problem = DesktopCatalog.installSeedFromFile(this, entry, archive,
                LinuxRuntimeInstaller.ProgressListener { s, p -> ui.post { protonSeedStage = s; protonSeedPercent = p } })
            ui.post {
                protonSeedStage = null
                protonSeedPercent = -1
                // The seed unpacks its own appmanifest, which is what the check reads; a run that
                // failed half-way leaves it absent, so the row goes back to offering the install.
                if (problem == null) protonSeedPresent = !DesktopCatalog.protonSeedNeeded(this)
                else android.widget.Toast.makeText(this, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "proton-seed-install").start()
    }

    /**
     * The first-time install from a folder: everything the folder holds that the device has not got
     * yet, in the order it has to go in, and then straight in.
     *
     * Nothing is looked up first. The archives were put in the folder by the person installing them,
     * and this path exists for a device that cannot reach GitHub - so a catalog request here would
     * be a request that ends in its timeout, minutes of the screen on "checking" ahead of an install
     * that was never in doubt. Each part goes in unvouched for, which is what filling the folder by
     * hand already decided, and nothing is asked of the user either.
     *
     * What is checked is the room it needs, and it is the one thing here that can say no: the folder
     * being carried is several gigabytes of archives, and what they unpack to is what sits on the
     * volume. A run that starts short of that ends part-way through with a device that is neither
     * set up nor untouched, so the figure is worked out from the folder and compared with the free
     * space first, and a short answer names both rather than starting. It is an estimate - each
     * archive is counted at about three times its size - and it errs upward, deliberately.
     *
     * Reading the folder is a listing, so this takes no time; it runs off the main thread for that
     * listing rather than for a wait, and holding the screen busy keeps a second pick from starting
     * over the first.
     */
    private fun planFirstLocalInstall(picked: File) {
        if (runtimeChangesBlocked()) return
        busy = true
        failed = false
        stage = getString(R.string.common_checking)
        percent = -1
        Thread({
            val folder = FirstLocalInstall.folder(picked)
            val held = FirstLocalInstall.found(folder)
            // Remembered only when it holds something: a pick that found nothing is a mis-tap, and
            // keeping it would move the button's judgement off a folder that works.
            if (held.isNotEmpty()) FirstLocalInstall.remember(this, folder)
            val missing = FirstLocalInstall.missing(this, held)
            // The volume the install lands on is the one under this app's own files; a runtime not
            // installed yet has no directory of its own to measure.
            val need = FirstLocalInstall.spaceNeeded(missing)
            val free = android.os.StatFs(filesDir.path).availableBytes
            // Written before anything is unpacked, so a run that never gets further - the case the
            // log exists for - still says what the device is and what it was about to do.
            FirstInstallLog.begin(
                this, folder,
                held.map { firstLocalLabel(it) + " (" + it.source.name + ")" },
                missing.map { firstLocalLabel(it) + " (" + it.source.name + ")" },
                need, free,
            )
            // Settled here rather than in the post below: it lists the folder again, and this is the
            // point where the "everything in it is already in" answer takes the button away - which
            // is what tapping it to find nothing left to do is supposed to do.
            val stillNeeded = FirstLocalInstall.needed(this)
            ui.post {
                busy = false
                stage = ""
                percent = -1
                firstLocalNeeded = stillNeeded
                when {
                    held.isEmpty() -> android.widget.Toast.makeText(this, R.string.first_local_nothing_found, android.widget.Toast.LENGTH_LONG).show()
                    missing.isEmpty() -> android.widget.Toast.makeText(this, R.string.first_local_all_installed, android.widget.Toast.LENGTH_LONG).show()
                    free < need -> android.widget.Toast.makeText(
                        this,
                        getString(
                            R.string.first_local_no_space,
                            com.droiddeck.launcher.core.FileUtils.sizeToString(need),
                            com.droiddeck.launcher.core.FileUtils.sizeToString(free),
                        ),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    // Straight in: the files were chosen by putting them in the folder, so there is
                    // nothing left to ask about any of them.
                    else -> runFirstLocalInstall(missing)
                }
            }
        }, "first-local-plan").start()
    }

    /** Runs what the plan settled on, one after another, and reports what each one ended as. */
    private fun runFirstLocalInstall(payloads: List<FirstLocalInstall.Found>) {
        busy = true
        failed = false
        stage = getString(R.string.common_working)
        percent = -1
        val started = System.currentTimeMillis()
        Thread({
            // The run has to end in a report whatever happens. Before this it could die on its own
            // thread - an Error from under an extractor, a failure in the progress callback - and
            // leave the screen on "working" for ever, which is what a stuck install looked like
            // from the outside while the work was not running at all.
            var outcomes: List<FirstLocalInstall.Outcome> = emptyList()
            var hard: String? = null
            // A step that went quiet is not a failure on its own, but it is the one thing a user
            // can tell us about that the log cannot tell itself - so it is kept for the report as
            // well as shown, and the first one is the one worth keeping.
            val stall = java.util.concurrent.atomic.AtomicReference<Pair<String, Int>?>(null)
            try {
                outcomes = FirstLocalInstall.install(this, payloads, { number, total, what, value ->
                    ui.post { stage = "$number/$total · $what"; percent = value }
                }, { what, minutes ->
                    stall.compareAndSet(null, what to minutes)
                    ui.post { stage = getString(R.string.first_local_stalled, what, minutes) }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "first-time local install", t)
                FirstInstallLog.failed(this, "the run itself", t)
                hard = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            } finally {
                val elapsed = System.currentTimeMillis() - started
                FirstInstallLog.end(this, outcomes, elapsed, hard)
                val report = hard
                val done = outcomes
                val stalled = stall.get()
                ui.post {
                    busy = false
                    stage = ""
                    percent = -1
                    // Read the device back, so the settings rows and the play page's button agree with
                    // what just happened rather than with what was there before it.
                    installed = LinuxRuntimeInstaller.installedVersion(this)
                    ready = LinuxRuntime.isInstalled(this)
                    steamClientPresent = SteamClient.installed(this)
                    protonSeedPresent = !DesktopCatalog.protonSeedNeeded(this)
                    desktopInstalled = DesktopCatalog.desktopInstalled(this)
                    protons.refreshProtons()
                    firstLocalNeeded = FirstLocalInstall.needed(this)
                    reportFirstLocalInstall(done, report, stalled, payloads.size)
                }
            }
        }, "first-local-install").start()
    }

    /**
     * What went wrong, and nothing else.
     *
     * A run that finished cleanly gets no report at all: a screen of steps saying "installed" is
     * the one thing a user who just watched the whole thing cannot use, and it buries the line that
     * matters. What is worth the screen is *which* step stopped - its number, its name, and what it
     * failed with - plus the two ways a run ends without an outcome of its own: an error raised
     * outside any step, and a step that went quiet long enough for the watchdog to say so. A clean
     * run says so in one short Toast and leaves the log on disk for anyone who asks.
     *
     * A dialog, not a Toast: the run takes minutes and its result is the only thing that says what
     * to do next, and a Toast is gone before a user who left the screen comes back. It carries the
     * way to the log file, which says the rest.
     */
    private fun reportFirstLocalInstall(
        outcomes: List<FirstLocalInstall.Outcome>, hard: String?, stall: Pair<String, Int>?, total: Int,
    ) {
        val failed = FirstLocalInstall.failedSteps(outcomes)
        if (!FirstLocalInstall.worthReporting(outcomes, hard, stall != null)) {
            android.widget.Toast.makeText(
                this, getString(R.string.first_local_ok, total), android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val lines = mutableListOf<String>()
        if (hard != null) {
            // Nothing below the throw was recorded, so how far it got is unknown - the error is the
            // only thing that can be said, and it is said first.
            lines.add(getString(R.string.first_local_crashed, hard))
        } else if (total > 0) {
            lines.add(getString(R.string.first_local_progress, total - failed.size, total))
        }
        stall?.let { (what, minutes) -> lines.add(getString(R.string.first_local_stalled, what, minutes)) }
        outcomes.forEachIndexed { index, outcome ->
            val problem = outcome.problem ?: return@forEachIndexed
            lines.add(getString(
                R.string.first_local_failed, index + 1, total, firstLocalLabel(outcome.found), problem))
        }
        firstLocalReport = lines.joinToString("\n")
    }

    /** What a step is called in a message: the parts by name, a Proton build as its own. */
    private fun firstLocalLabel(found: FirstLocalInstall.Found): String = when (found.part) {
        FirstLocalInstall.Part.RUNTIME -> getString(R.string.setup_runtime)
        FirstLocalInstall.Part.PROTON -> getString(R.string.mode_proton_arm64)
        FirstLocalInstall.Part.CLIENT -> getString(R.string.mode_steam_client)
        FirstLocalInstall.Part.DESKTOP -> getString(R.string.content_linux_desktop)
        FirstLocalInstall.Part.L10N -> getString(R.string.first_local_l10n)
        FirstLocalInstall.Part.FONTS -> getString(R.string.first_local_fonts)
        FirstLocalInstall.Part.EXTRAS -> found.tool?.name ?: found.source.name
        // The whole folder, however many drivers are in it: the step is the folder.
        FirstLocalInstall.Part.GPU -> getString(R.string.mode_gpu_drivers)
    }

    /**
     * Shown when no release publishes a checksum for a Proton build the user picked - an older or
     * hand-built one, or a release list that could not be reached. The install itself is the same
     * either way; what changes is that nothing can vouch for the bytes.
     */
    private fun askProtonArchiveUnverified(id: String, archive: File) {
        protonExtraUnverified = id to archive
    }

    /**
     * Shows a runtime operation's progress until it ends. [run] either starts one or joins the one already
     * running (null: nothing was), which is how a launcher rebuilt mid-install picks it back up.
     */
    private fun followRuntimeOperation(run: (LinuxRuntimeInstaller.ProgressListener) -> Boolean?) {
        busy = true
        failed = false
        stage = getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (LinuxRuntimeInstaller.isRemoving()) R.string.main_removing_runtime else R.string.store_starting)
        percent = -1
        Thread({
            val ok = run(LinuxRuntimeInstaller.ProgressListener { s, p ->
                ui.post { stage = s; percent = p }
            })
            ui.post {
                busy = false
                if (ok != null) failed = !ok
                if (ok == false) LinuxRuntimeInstaller.removalError()?.let {
                    android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }, "runtime-operation").start()
    }

    private fun checkCatalog() {
        val release = LinuxRuntimeInstaller.fetchRelease()
        Log.i(TAG, "catalog: " + (release?.version ?: "unreachable"))
        ui.post { if (release != null) available = release }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MEDIA_SETTLE_MS = 1500L
        /** What the picker offers for a driver zip; some file apps label a zip as a plain stream. */
        private val ZIP_EXT = listOf("zip")
        private val WCP_EXT = listOf("wcp")
        /** The storage prompt a driver export puts up before its folder picker. */
        private const val EXPORT_PERMISSION = 3
        /** The same prompt for the desktop's language dump, so the two answers cannot cross. */
        private const val LANG_PERMISSION = 4
        /** And again for the one-click install's log copy. */
        private const val LOG_PERMISSION = 5
    }
}
