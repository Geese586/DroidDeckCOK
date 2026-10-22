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
import com.droiddeck.launcher.session.SteamLineSwitch
import com.droiddeck.launcher.session.SteamRepair
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.MirrorTest
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

    private var installed by mutableStateOf<String?>(null)
    private var flood by mutableStateOf<PendingFlood?>(null)
    private var floodProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
    private class PendingFlood(val from: androidx.compose.ui.geometry.Rect, val intent: Intent)
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
    private var pendingSwitch by mutableStateOf<Intent?>(null)
    private var switchingTo: Intent? = null
    private var switchPoll: Runnable? = null
    private var switchDeadline = 0L
    private var showResetSettings by mutableStateOf(false)
    private var lowSpace by mutableStateOf<Long?>(null)
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
    private var showComponents by mutableStateOf(false)
    private var focusComponentsContent by mutableStateOf(true)
    private var showMapping by mutableStateOf(false)
    private var saveBusy: String? = null
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

    /** The wrong-folder notice of the one-click install, kept on screen instead of a tiny Toast. */
    private var firstLocalHint by mutableStateOf<String?>(null)
    private var guestHostname by mutableStateOf(SessionPrefs.DEFAULT_GUEST_HOSTNAME)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var phantomProcessStatus by mutableStateOf(PhantomProcessStatus.NOT_APPLICABLE)
    private var showPhantomGate by mutableStateOf(false)
    private var directAudio by mutableStateOf(false)
    private var picturePosition by mutableStateOf("center")
    private var stretch16x9 by mutableStateOf(false)
    private var orientation by mutableStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    private var launcherFullscreen by mutableStateOf(true)
    private var downloadSource by mutableStateOf(DownloadSource.MODE_AUTO)
    private var downloadMirror by mutableStateOf("")
    private var downloadPrefix by mutableStateOf("")
    private var animationsEnabled by mutableStateOf(true)
    /** Which of Valve's two client-update edges Steam client installs pin (DroidDeckCOK). */
    private var steamCdn by mutableStateOf(com.droiddeck.launcher.session.SteamCdn.AUTO)
    private var steamLineTestBusy by mutableStateOf(false)
    private var steamLineTestEdges by mutableStateOf<List<com.droiddeck.launcher.session.SteamCdnProbe.Edge>?>(null)
    /** One mirror measurement at a time; a second tap while one runs is ignored rather than queued. */
    @Volatile private var mirrorTestBusy = false
    private var gameStoresEnabled by mutableStateOf(true)
    private var gameStoresSpeedTier by mutableStateOf("fast")
    private var storesOpenTab by mutableStateOf(SessionPrefs.STORES_OPEN_LIBRARY)
    private var navRequest by mutableStateOf<String?>(null)
    private var storesShowMature by mutableStateOf(false)
    private var storesSort by mutableStateOf(SessionPrefs.STORES_SORTS.first())
    private var storesView by mutableStateOf(SessionPrefs.STORES_VIEWS.first())
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
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        com.droiddeck.launcher.handtracking.HandTracking.refreshPermission(this)
    }
    private var placementCheck by mutableStateOf<Boolean?>(null)
    private val wifiPermissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SessionPrefs.setWifiDiscoveryEnabled(this, WifiDiscovery.permissionGranted(this))
        refreshWifiDiscovery()
    }

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
    private val pickBootMovie = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { importBootMovie(it) }
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

    private fun syncLossless() {
        Thread({
            Lossless.sync(this)
            val state = Lossless.state(this)
            ui.post { lossless = state }
        }, "lossless-sync").start()
    }

    private fun importBootMovie(file: File) {
        bootMovieImporting = true
        bootMovieProgress = -1
        Thread({
            val result = com.droiddeck.launcher.session.BootMovie.import(
                this, file,
                onStart = { ui.post { android.widget.Toast.makeText(this, getString(R.string.boot_movie_converting_started, file.name), android.widget.Toast.LENGTH_LONG).show() } },
                onProgress = { percent -> ui.post { bootMovieProgress = percent } },
            )
            val message = when (result) {
                is com.droiddeck.launcher.session.BootMovie.Import.Done -> getString(
                    if (result.audioDropped) R.string.boot_movie_imported_silent else R.string.boot_movie_imported, result.name)
                com.droiddeck.launcher.session.BootMovie.Import.TooLong ->
                    getString(R.string.boot_movie_too_long, com.droiddeck.launcher.session.BootMovie.MAX_CONVERT_SECONDS)
                is com.droiddeck.launcher.session.BootMovie.Import.Unsupported -> getString(R.string.boot_movie_unsupported)
                com.droiddeck.launcher.session.BootMovie.Import.Failed -> getString(R.string.boot_movie_import_failed)
            }
            ui.post {
                bootMovieImporting = false
                bootMovieProgress = -1
                loadBootMovie()
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "boot-movie-import").start()
    }

    private fun loadBootMovie() {
        bootMovie = com.droiddeck.launcher.session.BootMovie.choice(this)
        bootMovieSound = SessionPrefs.bootMovieSound(this)
        bootMovieCustomName = com.droiddeck.launcher.session.BootMovie.customName(this)
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
    private var sgdbUserKey by mutableStateOf(false)
    private val pickRomsDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setRomsDir(this, path)
            romsDir = path
        }
    }
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
    private var steamClientArch by mutableStateOf("arm64")
    private var runSteamAtStartup by mutableStateOf(false)
    private var bootMovie by mutableStateOf(com.droiddeck.launcher.session.BootMovie.DROIDDECK_30)
    private var bootMovieSound by mutableStateOf(true)
    private var bootMovieCustomName by mutableStateOf<String?>(null)
    private var bootMovieImporting by mutableStateOf(false)
    private var bootMovieProgress by mutableStateOf(-1)
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
    private var zramCompression by mutableStateOf(false)
    private var zramSupported by mutableStateOf<Boolean?>(null)
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
    private var steamGamesX64 by mutableStateOf(false)
    @Volatile private var componentSweepDone = false
    @Volatile private var componentSweepSession: String? = null
    private var emulatorList by mutableStateOf<List<Library.Emulator>>(emptyList())
    private var runningLabel by mutableStateOf<String?>(null)
    private var logsEnabled by mutableStateOf(true)
    private var vacDiagnostics by mutableStateOf(false)
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
        if (com.droiddeck.launcher.session.NoSteamLaunch.enabled(this, game)) return launchWithoutSteam(game, returnHome)
        if (SessionState.running) {
            if (SessionState.mode != SessionService.MODE_STEAM || SessionState.stopRequested) {
                android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                return true
            }
            val steamVr = game.gameIdString == com.droiddeck.launcher.gpu.SteamVr.APP_ID
            if (!steamVr && !SessionService.launchGame(this, game.gameIdString)) {
                android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                .putExtra(SessionActivity.EXTRA_STEAMVR, steamVr)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        return startSession(Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .apply {
                    if (game.gameIdString == com.droiddeck.launcher.gpu.SteamVr.APP_ID) {
                        putExtra(SessionActivity.EXTRA_STEAMVR, true)
                    } else {
                        putExtra(SessionService.EXTRA_STEAM_URL,
                            com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(game.gameIdString))
                    }
                }, steamSession = true)
    }

    private fun launchWithoutSteam(game: Library.SteamGame, returnHome: Boolean): Boolean {
        val app = applicationContext
        Thread({
            val added = if (game.library == Library.ADDED) runCatching { com.droiddeck.launcher.session.NoSteamLaunch.addedGame(app, game) }.getOrNull() else null
            val command = com.droiddeck.launcher.session.NoSteamLaunch.command(game, added)
            ui.post {
                if (isDestroyed) return@post
                if (command == null) {
                    android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                    return@post
                }
                com.droiddeck.launcher.session.NoSteamLaunch.remember(command)
                val next = Intent(this, SessionActivity::class.java)
                    .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                    .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                    .putExtra(SessionService.EXTRA_PROGRAM, command.program)
                    .putExtra(SessionService.EXTRA_PROGRAM_ARGS, command.args.toTypedArray())
                when {
                    !SessionState.running -> startSessionOrWait(next)
                    SessionState.stopRequested -> android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                    SessionState.mode == SessionService.MODE_RUN && SessionState.program == command.program && SessionState.programArgs == command.args ->
                        startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                            .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    else -> pendingSwitch = next
                }
            }
        }, "no-steam-launch").start()
        return true
    }

    private fun opensEpicLauncher(next: Intent): Boolean =
        next.getStringExtra(SessionService.EXTRA_PROGRAM) == com.droiddeck.launcher.session.NoSteamLaunch.EPIC ||
            next.getStringExtra(SessionService.EXTRA_STEAM_URL) == com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(
                com.droiddeck.launcher.stores.epic.EpicClient.gameId(com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId))

    private fun uninstallEpicLauncher() {
        if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling) return
        com.droiddeck.launcher.stores.epic.EpicClient.uninstalling = true
        pendingSwitch?.takeIf(::opensEpicLauncher)?.let { pendingSwitch = null }
        switchingTo?.takeIf(::opensEpicLauncher)?.let {
            switchPoll?.let { poll -> ui.removeCallbacks(poll) }
            switchPoll = null
            switchingTo = null
        }
        if (SessionState.running) SessionService.stop(this)
        val app = applicationContext
        val deadline = android.os.SystemClock.uptimeMillis() + SESSION_SWITCH_WAIT_MS
        Thread({
            while ((SessionState.running || SessionState.phase !in SESSION_ENDED) && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(SESSION_SWITCH_POLL_MS)
            }
            val ended = !SessionState.running && SessionState.phase in SESSION_ENDED
            val done = try {
                ended && com.droiddeck.launcher.stores.epic.EpicClient.uninstall(app)
            } finally {
                com.droiddeck.launcher.stores.epic.EpicClient.uninstalling = false
            }
            ui.post {
                if (!isDestroyed) refresh()
                android.widget.Toast.makeText(app, if (done) R.string.epic_client_uninstalled else R.string.epic_client_uninstall_failed,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }, "epic-uninstall").start()
    }

    private fun openEpicLauncher(): Boolean {
        if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling) {
            android.widget.Toast.makeText(this, R.string.epic_client_uninstalling, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null) {
            android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (!com.droiddeck.launcher.stores.epic.EpicClient.launcherRunning(this) && !com.droiddeck.launcher.stores.epic.EpicClient.fexReady(this)) {
            com.droiddeck.launcher.stores.epic.EpicClient.requestFex(this)
            android.widget.Toast.makeText(this, R.string.epic_client_needs_fex, android.widget.Toast.LENGTH_LONG).show()
            if (!SessionState.running) startSteamSession()
            else if (SessionState.mode == SessionService.MODE_STEAM) startActivity(Intent(this, SessionActivity::class.java)
                .setAction(SessionService.ACTION_RESUME).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        if (com.droiddeck.launcher.session.NoSteamLaunch.enabled(this, com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId)) {
            com.droiddeck.launcher.stores.epic.EpicClient.launcherEntry(this, Library.EPIC_LAUNCHER_NAME)?.let { return launchWithoutSteam(it, false) }
        }
        val launcher = Intent(this, SessionActivity::class.java)
            .putExtra(SessionActivity.EXTRA_RETURN_HOME, false)
            .putExtra(SessionService.EXTRA_STEAM_URL, com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(
                com.droiddeck.launcher.stores.epic.EpicClient.gameId(com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId)))
        if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM && !SessionState.stopRequested) {
            val app = applicationContext
            val shortcut = com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId
            val listed = com.droiddeck.launcher.frontend.AddedGames.inSteam(app, shortcut)
            if (listed && !SessionService.launchGame(this, com.droiddeck.launcher.stores.epic.EpicClient.gameId(shortcut))) {
                android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, false)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (!listed) Thread({
                com.droiddeck.launcher.frontend.AddedGamesPublisher.republish(app)
                val id = com.droiddeck.launcher.stores.epic.EpicClient.steamLauncherId(app) ?: shortcut
                ui.post { SessionService.launchGame(app, com.droiddeck.launcher.stores.epic.EpicClient.gameId(id)) }
            }, "epic-launcher-open").start()
            return true
        }
        if (SessionState.running) {
            if (SessionState.stopRequested) android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
            else pendingSwitch = launcher
            return true
        }
        return startSessionOrWait(launcher)
    }

    private fun startSessionOrWait(next: Intent): Boolean {
        val started = startSession(next, steamSession = next.hasExtra(SessionService.EXTRA_STEAM_URL))
        if (!started && (busy || LinuxRuntimeInstaller.isBusy())) android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
        return started
    }

    private fun startAfterSession(next: Intent) {
        SessionService.stop(this)
        awaitSessionEnd(next, android.os.SystemClock.uptimeMillis() + SESSION_SWITCH_WAIT_MS)
    }

    private fun awaitSessionEnd(next: Intent, deadline: Long) {
        switchingTo = next
        switchDeadline = deadline
        switchPoll?.let { ui.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (SessionState.running || SessionState.phase !in SESSION_ENDED) {
                    if (android.os.SystemClock.uptimeMillis() < deadline) ui.postDelayed(this, SESSION_SWITCH_POLL_MS)
                    else {
                        switchPoll = null
                        switchingTo = null
                        android.widget.Toast.makeText(this@MainActivity, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                    }
                    return
                }
                switchPoll = null
                switchingTo = null
                if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling && opensEpicLauncher(next)) return
                startSessionOrWait(next)
            }
        }
        switchPoll = poll
        ui.postDelayed(poll, SESSION_SWITCH_POLL_MS)
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
        val (cached, cachedX64) = com.droiddeck.launcher.frontend.LibraryCache.snapshot(this)
        steamGames = if (shortcutPicker) emptyList() else cached
        steamGamesX64 = cachedX64
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        orientation = SessionPrefs.orientation(this)
        requestedOrientation = orientation
        launcherFullscreen = SessionPrefs.launcherFullscreen(this)
        downloadSource = DownloadSource.mode(this)
        downloadMirror = DownloadSource.mirror(this)
        downloadPrefix = DownloadSource.custom(this)
        DownloadSource.probe()
        animationsEnabled = SessionPrefs.animationsEnabled(this)
        gameStoresEnabled = SessionPrefs.gameStoresEnabled(this)
        gameStoresSpeedTier = SessionPrefs.gameStoresSpeedTier(this)
        storesOpenTab = SessionPrefs.storesOpenTab(this)
        storesShowMature = SessionPrefs.storesShowMature(this)
        storesSort = SessionPrefs.storesSort(this)
        storesView = SessionPrefs.storesView(this)
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
                        frameGenLabel = frameGenLabel, romsDir = romsDir, logsEnabled = logsEnabled,
                        vacDiagnostics = vacDiagnostics, agentCommands = agentCommands,
                        steamGames = steamGames, steamGamesX64 = steamGamesX64, emulators = emulatorList, running = runningLabel,
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
                        downloadSource = downloadSource,
                        downloadMirror = downloadMirror,
                        downloadPrefix = downloadPrefix,
                        animationsEnabled = animationsEnabled,
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
                        storesSort = storesSort,
                        storesView = storesView,
                        storeDownloadsActive = com.droiddeck.launcher.stores.StoresState.activeDownloads,
                    ),
                    FrontEndActions(
                        // Upstream 0.5.0's Play: it asks about free space first.
                        onPlay = { playSteam() },
                        // The same session, with the edge menu on its loading screen: the button for
                        // a user whose line is the thing in doubt, who wants to see and change the
                        // edge instead of trusting whatever the script picked. It used to time the
                        // two edges first; on a device where neither edge answers that cost seconds
                        // and ended in a Toast saying the client could only come from a folder, so
                        // the timing is gone and the menu is the whole of what this button adds.
                        onPlayMeasured = { startSteamSession(lineMenu = true) },
                        // The client's desktop UI, with gamescope's Deck integration disabled.
                        onPlaySteamVr = { playSteamVr() },
                        onSteamUrl = { url ->
                            if (!SessionState.running) startSession(
                                Intent(this, SessionActivity::class.java).putExtra(SessionService.EXTRA_STEAM_URL, url), steamSession = true,
                            )
                        },
                        onPlayDesktopUi = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"), steamSession = true)
                        },
                        onSteamGame = { g ->
                            if (shortcutPicker) chooseGameShortcut(g)
                            else {
                                com.droiddeck.launcher.frontend.AutoComponents.beforeLaunch(this, g)
                                com.droiddeck.launcher.stores.StoreLaunch.prepare(this, g) { launchGame(g) }
                            }
                        },
                        onEpicLauncher = { openEpicLauncher() },
                        onUninstallEpicLauncher = { uninstallEpicLauncher() },
                        onGameShortcut = { g -> com.droiddeck.launcher.frontend.GameShortcuts.pin(this, g) },
                        onExportGameFile = { g -> pickGameExport(g) },
                        onSyncGameFiles = { pickGameExport(null) },
                        onAddGameExe = { path -> addGameExe(path) },
                        onAddedGamesChanged = { folders ->
                            val known = steamGames.mapNotNull { it.gameFiles?.path }.toSet()
                            updateAddedGames(folders, then = { if (folders.any { it !in known }) fetchAddedGameArt() })
                        },
                        onSteamGameArtChanged = { appId -> refreshSteamGameArt(appId) },
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
                        onUserApp = { app ->
                            app.args.firstOrNull()?.let { Library.flatpakNames[it] = app.name }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, app.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, app.args.toTypedArray()))
                        },
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
                        onVacDiagnostics = {
                            SessionPrefs.setVacDiagnostics(this, !SessionPrefs.vacDiagnostics(this))
                            vacDiagnostics = SessionPrefs.vacDiagnostics(this)
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
                        onResetSettings = {
                            if (SessionState.running) android.widget.Toast.makeText(this, R.string.setup_reset_settings_session_running, android.widget.Toast.LENGTH_LONG).show()
                            else showResetSettings = true
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
                        onSteamCdn = { id ->
                            com.droiddeck.launcher.session.SteamCdn.set(this, id)
                            // A deliberate pick outranks whatever the last line test measured:
                            // the measurement is the reason for one install, not a veto over the
                            // user - and it is dropped here so no later install can be pinned by
                            // an edge this change has just superseded.
                            SteamCdn.setOverride(SteamCdn.AUTO)
                            steamCdn = id
                        },
                        onSteamLineTest = { runSteamLineTest() },
                        onSteamLineTestDismiss = { steamLineTestEdges = null },
                        onGameStoresSpeedTier = { tier -> SessionPrefs.setGameStoresSpeedTier(this, tier); gameStoresSpeedTier = tier },
                        onStoresOpenTab = { tab -> SessionPrefs.setStoresOpenTab(this, tab); storesOpenTab = tab },
                        onNavigated = { navRequest = null },
                        onStoresShowMature = { show -> SessionPrefs.setStoresShowMature(this, show); storesShowMature = show },
                        onStoresSort = { sort -> SessionPrefs.setStoresSort(this, sort); storesSort = sort },
                        onStoresView = { view -> SessionPrefs.setStoresView(this, view); storesView = view },
                        onLibraryChanged = { refreshAddedGames(); refresh() },
                        onAnimationsEnabled = { on ->
                            SessionPrefs.setAnimationsEnabled(this, on)
                            animationsEnabled = on
                            com.droiddeck.launcher.ui.Motion.refresh(this)
                        },
                        onDownloadSource = { mode ->
                            DownloadSource.setMode(this, mode)
                            downloadSource = mode
                            // Picking the accelerator mode is the user asking to be routed through
                            // one of them; measuring here is what makes the mirror the download
                            // then picks something they were shown the reason for (DroidDeckCOK).
                            if (mode == DownloadSource.MODE_MIRROR) runMirrorTest()
                        },
                        onDownloadMirror = { host ->
                            DownloadSource.setMirror(this, host)
                            downloadMirror = host
                        },
                        onDownloadPrefix = { prefix ->
                            DownloadSource.setCustom(this, prefix)
                            downloadPrefix = DownloadSource.custom(this)
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
                            onJoyConVr = { on -> ControllerPrefs.setJoyConVr(this, on); refreshController() },
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
                pendingSwitch?.let { next ->
                    ConfirmDialog(
                        title = androidx.compose.ui.res.stringResource(R.string.drawer_stop_title),
                        text = runningLabel ?: "",
                        confirm = androidx.compose.ui.res.stringResource(R.string.drawer_stop),
                        onConfirm = { pendingSwitch = null; startAfterSession(next) },
                        onDismiss = { pendingSwitch = null },
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
                // A pick that found nothing: the download addresses have to stay on screen long
                // enough to be copied, so a dialog, not the Toast this used to be.
                firstLocalHint?.let { hint ->
                    com.droiddeck.launcher.ui.FirstLocalHintDialog(
                        text = hint,
                        onDismiss = { firstLocalHint = null },
                    )
                }
                lowSpace?.let { free ->
                    com.droiddeck.launcher.ui.LowSpaceDialog(
                        free,
                        onFreeUp = { lowSpace = null; com.droiddeck.launcher.storage.StorageState.requestOpen() },
                        onPlay = { lowSpace = null; com.droiddeck.launcher.storage.StorageState.lowSpaceDismissed = true; startSteamSession() },
                        onDismiss = { lowSpace = null },
                    )
                }
                com.droiddeck.launcher.ui.HandCameraHost(
                    check = placementCheck,
                    onAskPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    onPlay = { placementCheck = null; startSteamSession(steamVr = true) },
                    onClose = { placementCheck = null },
                )
                if (showResetSettings) com.droiddeck.launcher.ui.ResetSettingsDialog(
                    onConfirm = { gameProfiles -> resetSettings(gameProfiles) },
                    onDismiss = { showResetSettings = false },
                )
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
        else {
            pendingSwitch = savedIntent(savedInstanceState, KEY_PENDING_SWITCH)
            savedIntent(savedInstanceState, KEY_SWITCHING_TO)?.let { awaitSessionEnd(it, savedInstanceState.getLong(KEY_SWITCH_DEADLINE)) }
        }

        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (SessionPrefs.micEnabled(this) && !SessionPrefs.micAsked(this)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
            SessionPrefs.setMicAsked(this)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        Thread({ com.droiddeck.launcher.stores.StoreAccounts.encryptAll(this) }, "store-credentials").start()
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

    /**
     * @param lineMenu puts the edge menu on the loading screen, so this run's download can be moved
     *   to the other edge by hand. Off for the plain Play, which keeps its old shape: no menu, no
     *   wait, nothing new on screen.
     * @param steamVr starts the client inside SteamVR on the glasses (upstream 0.5.0).
     */
    private fun startSteamSession(steamVr: Boolean = false, lineMenu: Boolean = false) {
        startSession(
            Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_STEAMVR, steamVr),
            steamSession = true, lineMenu = lineMenu,
        )
    }

    private fun playSteam() {
        val storage = com.droiddeck.launcher.storage.StorageState
        if (!SessionState.running && !storage.lowSpaceDismissed && storage.lowOnSpace(this)) {
            lowSpace = storage.freeBytes(this)
            storage.refresh(this)
        } else startSteamSession()
    }

    private fun playSteamVr() {
        val tracking = com.droiddeck.launcher.glasses.Glasses.settings.value
        if (!com.droiddeck.launcher.glasses.Glasses.state.value.usesSpatialControllers(tracking) &&
            com.droiddeck.launcher.handtracking.HandTracking.wanted(this)) placementCheck = true
        else startSteamSession(steamVr = true)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) com.droiddeck.launcher.ui.HeldKeys.confirm = event.action == KeyEvent.ACTION_DOWN
        if (showComponents && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                components.compComp = com.droiddeck.launcher.ui.layerStep(components.compComp, if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
            }
            return true
        }
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
        com.droiddeck.launcher.stores.StoresState.refresh(this)
        com.droiddeck.launcher.stores.StoresState.libraryListener = { refreshAddedGames(); refresh() }
        com.droiddeck.launcher.ui.Motion.refresh(this)
        com.droiddeck.launcher.ui.QuitFlood.take()?.let { c ->
            returning = ReturningFlood(c, com.droiddeck.launcher.ui.LaunchOrigin.takeReturn())
        }
        refreshPhantomStatus()
        syncLossless()
        updates.onResume()
        drivers.checkOnOpen()
        protons.checkUpdates()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSwitch?.let { outState.putParcelable(KEY_PENDING_SWITCH, it) }
        switchingTo?.let {
            outState.putParcelable(KEY_SWITCHING_TO, it)
            outState.putLong(KEY_SWITCH_DEADLINE, switchDeadline)
        }
    }

    private fun savedIntent(state: Bundle, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) state.getParcelable(key, Intent::class.java)
        else @Suppress("DEPRECATION") state.getParcelable(key)

    override fun onDestroy() {
        updates.unregister()
        super.onDestroy()
    }

    override fun onStop() {
        unregisterReceiver(wifiLocationReceiver)
        unregisterReceiver(mediaReceiver)
        ui.removeCallbacks(mediaRefresh)
        com.droiddeck.launcher.stores.StoresState.libraryListener = null
        displayManager.unregisterDisplayListener(secondScreenDisplayListener)
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
        // The launcher can itself be shown on a non-default display (DeX, an external monitor); that one is not a "second screen".
        val ownDisplayId = if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId ?: android.view.Display.DEFAULT_DISPLAY else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        secondScreenDisplays = SecondScreenDisplays.available(displayManager, ownDisplayId)
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
            onBack = { if (!com.droiddeck.launcher.ui.Hops.back { showComponents = false }) showComponents = false },
            games = steamGames,
            generalFexPreset = fexPreset,
            onGeneralFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
            gpu = drivers.state(),
            gpuActions = com.droiddeck.launcher.ui.GpuDriversActions(
                onAuto = { on -> drivers.setMode(on) },
                onPair = { key -> drivers.selectPair(key) },
                onRefresh = { if (drivers.mode == SessionPrefs.GPU_DRIVERS_AUTO) drivers.ensureAuto(force = true) else drivers.checkLatestTurnip() },
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
                onMode = { m -> drivers.chooseMode(m) },
                onRestart = { drivers.restartApp() },
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
        androidx.compose.runtime.LaunchedEffect(components.compDownloadRevision) { components.refreshComponents() }
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
                zramCompression = if (mode == SessionService.MODE_STEAM) zramCompression else null,
                zramSupported = zramSupported,
                pipSupported = com.droiddeck.launcher.session.SessionPipController.supported(this),
                pipAutoEnter = pipAutoEnter,
                unfoldedControls = if (mode == SessionService.MODE_STEAM &&
                    com.droiddeck.launcher.session.SessionDisplay.foldable(this)) unfoldedControls else null,
                oscMode = if (mode == SessionService.MODE_STEAM) oscMode else null,
                backActionsInverted = backActionsInverted,
                directAudio = if (mode == SessionService.MODE_STEAM) directAudio else null,
                picturePosition = if (mode == SessionService.MODE_STEAM) picturePosition else null,
                stretch16x9 = if (mode == SessionService.MODE_STEAM) stretch16x9 else null,
                mic = if (mode == SessionService.MODE_STEAM) mic else null,
                gameStorage = if (mode == SessionService.MODE_STEAM) gameStorage else null,
                storageOptions = storageOptions,
                storageDiagnostics = mode == SessionService.MODE_STEAM && storageDiagnostics,
                fexPreset = if (mode == SessionService.MODE_STEAM) fexPreset else null,
                forceSsbs = forceSsbs,
                syncBackend = if (mode == SessionService.MODE_STEAM) SessionPrefs.syncBackendOf(fastSync, fsyncFirst, syncFallback) else null,
                buildStamp = buildStamp,
                steamClientArch = if (mode == SessionService.MODE_STEAM) steamClientArch else null,
                steamRepairQueued = steamRepairQueued,
                mangoapp = mangoapp,
                steamController = if (mode == SessionService.MODE_STEAM) steamController else null,
                runSteamAtStartup = mode == SessionService.MODE_STEAM && runSteamAtStartup,
                bootMovie = if (mode == SessionService.MODE_STEAM) bootMovie else null,
                bootMovieSound = bootMovieSound,
                bootMovieCustomName = bootMovieCustomName,
                bootMovieImporting = bootMovieImporting,
                bootMovieProgress = bootMovieProgress,
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
                onZramCompression = { on -> SessionPrefs.setZramCompression(this, on); zramCompression = on },
                onPipAutoEnter = { on -> SessionPrefs.setPipAutoEnter(this, on); pipAutoEnter = on },
                onUnfoldedControls = { on -> SessionPrefs.setUnfoldedControls(this, on); unfoldedControls = on },
                onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                onBackActionsInverted = { inverted ->
                    SessionPrefs.setBackActionsInverted(this, inverted)
                    backActionsInverted = inverted
                },
                onDirectAudio = { on -> SessionPrefs.setDirectAudio(this, on); directAudio = on },
                onPicturePosition = { value -> SessionPrefs.setPicturePosition(this, value); picturePosition = value },
                onStretch16x9 = { on -> SessionPrefs.setStretch16x9(this, on); stretch16x9 = on },
                onMic = { on ->
                    SessionPrefs.setMicEnabled(this, on)
                    mic = on
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
                onSteamClientArch = { id -> SessionPrefs.setSteamClientArch(this, id); steamClientArch = id; refresh() },
                onMangoapp = { on -> SessionPrefs.setMangoapp(this, on); mangoapp = on },
                onSteamController = { id -> SessionPrefs.setSteamController(this, id); steamController = id },
                onRunSteamAtStartup = { on ->
                    SessionPrefs.setRunSteamAtStartup(this, on)
                    runSteamAtStartup = on
                },
                onBootMovie = { id -> SessionPrefs.setBootMovie(this, id); loadBootMovie() },
                onBootMovieSound = { on -> SessionPrefs.setBootMovieSound(this, on); bootMovieSound = on },
                onImportBootMovie = {
                    pickBootMovie.launch(InAppFilePicker.buildIntent(this, com.droiddeck.launcher.session.BootMovie.PICKER_EXTENSIONS, getString(R.string.boot_movie_pick)))
                },
                onRemoveBootMovie = { com.droiddeck.launcher.session.BootMovie.removeCustom(this); loadBootMovie() },
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

    private fun updateAddedGames(folders: List<String>, then: () -> Unit = {}) {
        if (folders.isEmpty()) return
        Thread({
            val fresh = folders.mapNotNull { f ->
                com.droiddeck.launcher.frontend.AddedGames.single(this, f)?.let { com.droiddeck.launcher.frontend.Library.addedGame(this, it) }
            }
            fresh.forEach { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(this, it) }
            ui.post {
                val paths = folders.toSet()
                steamGames = (steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path in paths } + fresh).distinctBy { it.gameId }
                then()
            }
        }, "added-games-update").start()
    }

    private val steamArtRefreshes = HashMap<Int, Int>()

    private fun refreshSteamGameArt(appId: Int) {
        val version = (steamArtRefreshes[appId] ?: 0) + 1
        steamArtRefreshes[appId] = version
        Thread({
            val fresh = com.droiddeck.launcher.frontend.Library.steamGames(this).firstOrNull { it.appId == appId } ?: return@Thread
            ui.post {
                if (steamArtRefreshes[appId] != version) return@post
                steamGames = steamGames.map { if (it.appId == appId && it.library != Library.ADDED) fresh else it }
            }
        }, "steam-art-update").start()
    }

    private fun removeAddedGame(folder: String, appId: Long?, name: String) {
        steamGames = steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path == folder }
        Thread({ com.droiddeck.launcher.frontend.AddedGames.remove(this, java.io.File(folder), appId, name) }, "added-game-remove").start()
    }

    private fun refreshAddedGames() {
        sgdbKeyKnown()
        fetchAddedGameArt()
    }

    private fun sgdbKeyKnown() {
        Thread({
            val set = com.droiddeck.launcher.frontend.SteamGridDb.userKey(this).isNotEmpty()
            ui.post { sgdbUserKey = set }
        }, "sgdb-key-state").start()
    }

    private fun fetchAddedGameArt() {
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
        steamClientArch = SessionPrefs.steamClientArch(this)
        steamRepairQueued = SteamRepair.queued(this)
        mangoapp = SessionPrefs.mangoapp(this)
        steamController = SessionPrefs.steamController(this)
        runSteamAtStartup = SessionPrefs.runSteamAtStartup(this)
        loadBootMovie()
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
        picturePosition = SessionPrefs.picturePosition(this)
        stretch16x9 = SessionPrefs.stretch16x9(this)
        mic = SessionPrefs.micEnabled(this)
        refreshWifiDiscovery()
        gameStorage = SessionPrefs.gameStorage(this)
        storageDiagnostics = SessionPrefs.storageDiagnosticsEnabled(this)
        settingsMode = mode
        Thread({
            val zram = com.droiddeck.launcher.core.ZramSupport.supported()
            val zramOn = SessionPrefs.zramCompression(this)
            ui.post { zramSupported = zram; zramCompression = zramOn }
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
        // Until the scan finishes, the other client's games would launch in this one.
        val listX64 = SessionPrefs.activeSteamClientArch(this) == "x86_64"
        if (listX64 != steamGamesX64) {
            steamGamesX64 = listX64
            steamGames = steamGames.filterNot(Library::fromSteamClient)
        }
        if (!busy && LinuxRuntimeInstaller.isBusy()) followRuntimeOperation { LinuxRuntimeInstaller.attach(it) }
        if (showComponents) drivers.refreshDrivers()
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
        downloadSource = DownloadSource.mode(this)
        steamCdn = com.droiddeck.launcher.session.SteamCdn.current(this)
        vacDiagnostics = SessionPrefs.vacDiagnostics(this)
        agentCommands = com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this)
        runningLabel = if (SessionState.running) when (SessionState.mode) {
            SessionService.MODE_DESKTOP -> getString(R.string.rail_desktop)
            SessionService.MODE_RUN -> Library.nameForProgram(SessionState.program)
                ?: SessionState.program?.substringAfterLast('/')?.substringBefore('.') ?: getString(R.string.main_running_program)
            else -> "Steam"
        } else null
        val scanGeneration = ++libraryScanGeneration
        val artRefreshesAtStart = HashMap(steamArtRefreshes)
        if (shortcutPicker) shortcutLibraryScanning = true
        if (SessionPrefs.steamClientArch(this) == "x86_64" && !OfflineMode.enabled(this)) {
            Thread({ com.droiddeck.launcher.session.FexCatalog.refresh(this) }, "fex-catalog").start()
        }
        Thread({
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { if (scanGeneration == libraryScanGeneration) emulatorList = emus }
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
            val scannedArch = SessionPrefs.activeSteamClientArch(this)
            val scannedX64 = scannedArch == "x86_64"
            val games = if (ready) Library.launchableGames(this, arch = scannedArch) else emptyList()
            val all = games.distinctBy { it.gameId }
            // Whether the play page's first-time install button belongs: the things a session
            // cannot do without, then the remembered folder's own contents. Off the main thread,
            // as reading that folder is a listing.
            val firstLocal = FirstLocalInstall.needed(this)
            if (ready) com.droiddeck.launcher.frontend.LibraryCache.save(this, all)
            // Once per app start: the Steam games' Windows components from Steam's lists.
            // Queued only, after the launcher has been up a little; the worker takes them one at a time.
            // And again after each session (a game installed in it, files changed).
            if (ready && scanGeneration == libraryScanGeneration) com.droiddeck.launcher.frontend.LibraryCache.save(this, all, scannedX64)
            val endedSession = SessionState.sessionId.takeIf { !SessionState.running && it != null && it != componentSweepSession }
            if (ready && (!componentSweepDone || endedSession != null)) {
                componentSweepDone = true
                endedSession?.let { componentSweepSession = it }
                ui.postDelayed({ Thread({ com.droiddeck.launcher.frontend.AutoComponents.sweep(this, all) }, "components-sweep").start() }, 10_000)
            }
            ui.post {
                firstLocalNeeded = firstLocal
                if (scanGeneration == libraryScanGeneration) {
                    val current = steamGames.filter { it.library != Library.ADDED }.associateBy { it.appId }
                    steamGamesX64 = scannedX64
                    steamGames = all.map { g ->
                        val newer = current[g.appId]
                        if (newer != null && g.library != Library.ADDED && steamArtRefreshes[g.appId] != artRefreshesAtStart[g.appId]) g.withArt(newer.art, newer.hero, newer.icon) else g
                    }
                    shortcutLibraryScanning = false
                    handleGameLink(all)
                }
            }
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
            showRemove = true
            return
        }
        if (release == null) {
            Thread({ checkCatalog() }, "catalog").start()
            return
        }
        if (installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()) { showNonAdreno = release; return }
        install(release)
    }

    /**
     * Starts a session; with no runtime on a non-Adreno, the same warning Setup gives comes first, before any download.
     *
     * @param lineMenu only the separate "advanced play" button turns this on (see [startSteamSession]):
     *   it asks the loading screen for the edge menu, so a line that turns out to crawl can be left
     *   for the other one without leaving the session. Nothing is timed before the session starts -
     *   a measurement that answers "neither edge" on a whole class of devices delayed every one of
     *   those starts and told the user nothing they could act on.
     */
    private fun startSession(intent: Intent, steamSession: Boolean = false, lineMenu: Boolean = false): Boolean {
        if (busy || LinuxRuntimeInstaller.isBusy()) return false
        if (com.droiddeck.launcher.storage.StorageState.cleaning) {
            android.widget.Toast.makeText(this, R.string.storage_cleanup_busy, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        refreshPhantomStatus()
        if (steamSession && PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            showPhantomGate = true
            return false
        }
        val warn = installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()
        if (warn && available != null) { showNonAdreno = available; return false }
        // A Steam session with no runtime means the next thing on screen is a 790 MB download, and
        // this is the one moment where the mirror it goes through is worth minutes rather than
        // seconds. Measured before the session starts, so the number is up before the bytes move;
        // the download waits on that measurement instead of racing it (DownloadSource.ranked), so
        // the mirror the Toast names is the mirror the download uses. With the runtime already in
        // place there is nothing big to fetch and a report would explain a choice nobody is making.
        if (steamSession && installed == null && DownloadSource.mode(this) != DownloadSource.MODE_DIRECT) runMirrorTest()
        // The last run's tap is spent - a line chosen for a download that is over must not decide
        // this one. Cleared here, where the run begins, rather than when the session service gets
        // going: that is after the loading screen is on screen, so a tap made on that screen would
        // have been erased by its own start (see SteamLineSwitch).
        if (lineMenu) intent.putExtra(SessionService.EXTRA_LINE_MENU, true)
        // Nothing outranks the setting for this run: whatever the line test left in memory was a
        // reason for the run it was made in, not for this one.
        SteamCdn.setOverride(SteamCdn.AUTO)
        SteamLineSwitch.clear(this)
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

    private fun resetSettings(gameProfiles: Boolean) {
        if (SessionState.running) return
        val scopes = steamGames.map { it.profileKey }
        Thread({
            val ok = com.droiddeck.launcher.session.SettingsReset.resetAll(this, gameProfiles, scopes)
            ui.post {
                if (isDestroyed) return@post
                com.droiddeck.launcher.core.AppLanguage.setChosen(this, com.droiddeck.launcher.core.AppLanguage.SYSTEM)
                if (!ok) android.widget.Toast.makeText(this, R.string.setup_reset_settings_partial, android.widget.Toast.LENGTH_LONG).show()
                com.droiddeck.launcher.ui.Motion.refresh(this)
                recreate()
            }
        }, "reset-settings").start()
    }

    private fun removeRuntime() {
        if (runtimeChangesBlocked()) return
        val removal = LinuxRuntimeInstaller.beginUninstall(this) ?: return
        ready = false
        com.droiddeck.launcher.runtime.RuntimeInstallService.keepRemovalAlive(this)
        followRuntimeOperation { removal.run(it) }
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
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
     * A measured report, drawn by us instead of by the platform. The platform's toast lays a
     * message out in a short box and ellipsises what does not fit - two lines on the ROM this app
     * runs on - so a report whose whole point is the ranked rows loses exactly the rows it exists
     * for: the first one survives, the rest never arrive. So the same [android.widget.Toast] is
     * given a view of our own, a [android.widget.TextView] the platform sizes to its content, and
     * every line shows.
     *
     * Still a real Toast, and that is the reason for not using a view in this activity's window:
     * the Steam line report is shown the moment before the session activity comes up, and anything
     * belonging to this window is covered by it. A toast floats over it. Built here rather than
     * inflated from a layout because all it needs is text and its own rounded card, in colours of
     * its own so it reads the same over the launcher, the loading screen and a game.
     *
     * Custom toast views still draw one reason further: this app targets API 28, and the
     * restrictions that make them unreliable from API 30 on are keyed off a target of R or newer.
     */
    @Suppress("DEPRECATION")
    private fun showReportToast(text: CharSequence) {
        // Gravity deliberately left alone: a toast's default is bottom-centre, and asking for it
        // again would be answered with "setGravity() shouldn't be called on text toasts" on the
        // builds that classify a TextView-carrying toast as one.
        showToast(reportToast(text), text)
    }

    /** The card [showReportToast] and [showLongToast] both draw, and nothing else. */
    @Suppress("DEPRECATION")
    private fun reportToast(text: CharSequence): android.widget.Toast {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val view = android.widget.TextView(this).apply {
            setText(text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(pad, pad, pad, pad)
            // Wide enough for a row like "1. Fastly - 3.2 MB/s" without stretching across a
            // handheld's screen, and tall enough for as many rows as a report carries.
            maxWidth = (360 * density).toInt()
            setLineSpacing(4 * density, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xE6212121.toInt())
            }
        }
        return android.widget.Toast(this).apply {
            duration = android.widget.Toast.LENGTH_LONG
            this.view = view
        }
    }

    /** Shows [toast], or the platform's own text toast when this ROM refuses the view. */
    private fun showToast(toast: android.widget.Toast, text: CharSequence) {
        try {
            toast.show()
        } catch (error: RuntimeException) {
            // A ROM that refuses the custom view still has to say it.
            Log.w(TAG, "report toast refused: ${error.message}")
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * A toast the user has to have time to read: the report's card, held for [holdMs] instead of the
     * platform's three and a half seconds.
     *
     * Two ways, in order. The platform's own duration field is set directly, which is honoured for
     * the same reason the card is ours at all - this app targets API 28, and the restrictions on
     * both are keyed off a target of R or newer. A ROM that has closed that door is answered the
     * other way round: a fresh card every few seconds until the hold is up, so the message is on
     * screen for as long as it was asked for either way.
     *
     * Put up *before* the work it warns about, by a caller that waits for this to return: the work
     * that follows is minutes of proot, and a card enqueued once that has the machine may not arrive
     * at all. A ROM that clamps the duration anyway still leaves it up for the platform's own long
     * duration, which is late rather than never - and never before the work, which is the part that
     * matters.
     */
    private fun showLongToast(text: CharSequence, holdMs: Long) {
        val first = reportToast(text)
        // Read back before it is shown: the re-showing path below is for a field that would not
        // take, and a ROM with no such field is answered without asking it anything else.
        val held = holdFor(first, holdMs)
        showToast(first, text)
        if (held) return
        val again = object : Runnable {
            private var left = holdMs - TOAST_REPEAT_MS
            override fun run() {
                if (left <= 0 || isFinishing || isDestroyed) return
                left -= TOAST_REPEAT_MS
                showToast(reportToast(text), text)
                ui.postDelayed(this, TOAST_REPEAT_MS)
            }
        }
        ui.postDelayed(again, TOAST_REPEAT_MS)
    }

    /**
     * Sets a toast's own duration field and says whether the ROM took it. Read back rather than
     * trusted, so the caller can fall back instead of believing a number that never landed.
     */
    private fun holdFor(toast: android.widget.Toast, holdMs: Long): Boolean = runCatching {
        val field = android.widget.Toast::class.java.getDeclaredField("mDuration")
        field.isAccessible = true
        field.setInt(toast, holdMs.toInt())
        field.getInt(toast).toLong() == holdMs
    }.getOrDefault(false)

    /**
     * Times both of Valve's client-update edges from this network, fastest first, and hands the one
     * the numbers justify pinning to [onDone] ([SteamCdn.AUTO] when they justify nothing).
     *
     * Two callers, one measurement. Setup › Diagnostics wants the numbers in a dialog, because the
     * user is about to pin an edge from them. A Steam session about to fetch a client that is not
     * there yet wants them as a Toast, because that is the moment the choice costs minutes: the
     * numbers on screen are the reason for the edge the install then uses, and the ranking is
     * applied rather than reported - see [SteamCdn.setOverride]. The probe touches nothing but the
     * network, and a second call while one is running answers with no measurement rather than
     * queueing behind it.
     */
    private fun runSteamLineTest(showDialog: Boolean = true, onDone: ((String) -> Unit)? = null) {
        if (steamLineTestBusy) {
            // Someone else's measurement is in flight: this run is not measured, so it starts from
            // the setting, and neither the other run's ranking nor its pin is inherited.
            SteamCdn.setOverride(SteamCdn.AUTO)
            onDone?.invoke(SteamCdn.AUTO)
            return
        }
        steamLineTestBusy = true
        steamLineTestEdges = null
        // Said before the first byte, because the session start waits for this: without it the
        // press would look ignored for as long as the edges take to answer.
        if (!showDialog) android.widget.Toast.makeText(
            this, R.string.steam_line_test_running, android.widget.Toast.LENGTH_SHORT,
        ).show()
        Thread({
            val edges = SteamCdnProbe.measureAll(SteamCdn.hosts(SteamCdn.AUTO), LINE_TEST_BUDGET_MS)
            val winner = SteamCdnProbe.recommendation(edges) ?: SteamCdn.AUTO
            android.util.Log.i("SteamCdn", "line test: " + edges.joinToString { "${it.host} ok=${it.reachable} ${SteamCdnProbe.rateToString(it.bytesPerSecond)}" } + " -> $winner")
            ui.post {
                steamLineTestBusy = false
                if (showDialog) {
                    steamLineTestEdges = edges
                } else {
                    if (!isFinishing && !isDestroyed) showReportToast(steamLineReport(edges, winner))
                    // The numbers go to the run as well as to the screen: the Toast is gone in
                    // three seconds and the download it was taken for takes minutes, and the
                    // loading screen keeps saying what was measured for the whole of it.
                    // The order as well as the pin: every edge the test timed, fastest first, so
                    // a lead short of the pin's 2x still decides which edge is tried first while
                    // the other stays as the fallback (see SessionService, BL_STEAM_CDN_ORDER).
                    SteamCdn.setMeasured(
                        winner, steamLineRanking(edges), SteamCdnProbe.ranked(edges).map { it.host },
                    )
                }
                onDone?.invoke(winner)
            }
        }, "steam-line-test").start()
    }

    /**
     * What the Toast carries: every edge that moved bytes, fastest first, then the pin the numbers
     * bought - or, when they bought none, why the script's own order stands. An edge that timed out
     * is left out rather than shown as unreachable: this test has a budget of seconds and a slow
     * edge is a verdict about the budget, not about the network.
     */
    private fun steamLineReport(edges: List<SteamCdnProbe.Edge>, winner: String): String {
        val rows = rankedRows(edges)
        if (rows.isEmpty()) return getString(R.string.steam_line_test_none)
        val order = SteamCdnProbe.ranked(edges).map { it.host }
        val tail = when {
            winner != SteamCdn.AUTO ->
                getString(R.string.steam_line_test_pinned, SteamCdnProbe.label(SteamCdn.hosts(winner).first()))
            SteamCdn.orderValue(order).isNotEmpty() ->
                getString(R.string.steam_line_test_order, SteamCdnProbe.label(order.first()))
            else -> getString(R.string.steam_line_test_auto)
        }
        return getString(R.string.steam_line_test_title) + "\n" + rows.joinToString("\n") + "\n" + tail
    }

    /**
     * The same measurement as a single line, for the loading screen's own footer: "1. Fastly -
     * 3.2 MB/s · 2. Akamai - 1.1 MB/s". Deliberately the rows and nothing else - it sits under the
     * percentage of a running download - because which edge that download actually uses is a fact
     * the install script reports for itself (see LoadingState.source).
     */
    private fun steamLineRanking(edges: List<SteamCdnProbe.Edge>): String = rankedRows(edges).joinToString(" · ")

    /**
     * The edges that moved bytes, fastest first, as the numbered rows both reports use. An edge
     * whose measurement ran out of budget is left out rather than shown as unreachable: the test
     * has seconds to spend and that is a verdict about the budget, not about the network.
     */
    private fun rankedRows(edges: List<SteamCdnProbe.Edge>): List<String> =
        edges.filter { it.reachable && it.bytesPerSecond > 0 }.take(3).mapIndexed { index, edge ->
            getString(
                R.string.steam_line_test_line, index + 1,
                SteamCdnProbe.label(edge.host), SteamCdnProbe.rateToString(edge.bytesPerSecond),
            )
        }

    /**
     * Times every mirror the app can fetch GitHub through and says which three came out fastest.
     * Called from the two moments the choice matters: the user picking the mainland mirror, and a
     * Steam session starting with no runtime installed. The numbers are the reason for the mirror
     * the download then uses, because this measurement is what orders it - a user on a 5 MB/s
     * GitHub line has watched a download slow to 1 MB/s through an accelerator that was the right
     * answer on somebody else's connection, and this is what stops that being invisible.
     */
    private fun runMirrorTest() {
        if (mirrorTestBusy) return
        mirrorTestBusy = true
        android.widget.Toast.makeText(this, R.string.mirror_test_running, android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            // The runtime's own asset when the catalog can be read - the very file this test exists
            // to protect - and a pinned release asset when it cannot, which is the network the test
            // is for. Both are real GitHub URLs of the kind the accelerators carry.
            val target = runCatching { LinuxRuntimeInstaller.fetchRelease()?.url }.getOrNull()
            val lines = MirrorTest.measure(target ?: MirrorTest.FALLBACK_URL)
            android.util.Log.i("MirrorTest", lines.joinToString { "${it.label}=${MirrorTest.rate(it.bytesPerSecond)}" })
            ui.post {
                mirrorTestBusy = false
                // The same reason as the Steam line report: the platform's own layout would show
                // the first mirror and ellipsise the other two away.
                if (!isFinishing && !isDestroyed) showReportToast(mirrorReport(lines))
            }
        }, "mirror-test").start()
    }

    /** The report: the measured fastest mirrors, in the order the download is about to take them. */
    private fun mirrorReport(lines: List<MirrorTest.Line>): String {
        if (!MirrorTest.anyReachable(lines)) return getString(R.string.mirror_test_none)
        val body = lines.filter { it.reachable }.take(3).mapIndexed { index, line ->
            getString(R.string.mirror_test_line, index + 1, line.label, MirrorTest.rate(line.bytesPerSecond))
        }.joinToString("\n")
        return getString(R.string.mirror_test_title) + "\n" + body
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
            // the folder does not. The edge is this run's line measurement if one was made for it,
            // and the user's Setup choice otherwise (see SteamCdn.forSession).
            val problem = SteamClient.installFromDir(this, dir,
                cdn = SteamCdn.guestValue(SteamCdn.forSession(this)),
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
                when {
                    // A mis-tap keeps the button: nothing went in, so the judgement that put it up
                    // stands. Recomputing could take it away, and the dialog is the whole answer here.
                    held.isEmpty() -> firstLocalHint = getString(R.string.first_local_nothing_found)
                    missing.isEmpty() -> {
                        firstLocalNeeded = stillNeeded
                        android.widget.Toast.makeText(this, R.string.first_local_all_installed, android.widget.Toast.LENGTH_LONG).show()
                    }
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
                }, { number, total, label ->
                    // The card goes up before the step does, and the step waits for it: what follows
                    // is minutes of proot with the device busy, and a message posted into that is a
                    // message that may never arrive. The wait is bounded, so the courtesy cannot
                    // hold the install up.
                    val up = java.util.concurrent.CountDownLatch(1)
                    ui.post {
                        showLongToast(getString(R.string.first_local_long_step, label), LONG_STEP_MS)
                        up.countDown()
                    }
                    up.await(TOAST_UP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        private const val SESSION_SWITCH_WAIT_MS = 30_000L
        private const val SESSION_SWITCH_POLL_MS = 250L
        private const val KEY_PENDING_SWITCH = "pending_switch"
        private const val KEY_SWITCHING_TO = "switching_to"
        private const val KEY_SWITCH_DEADLINE = "switch_deadline"
        private val SESSION_ENDED = setOf(SessionPhase.IDLE, SessionPhase.FAILED)
        private val ZIP_EXT = listOf("zip")
        private val WCP_EXT = listOf("wcp")
        /** The storage prompt a driver export puts up before its folder picker. */
        private const val EXPORT_PERMISSION = 3
        /** The same prompt for the desktop's language dump, so the two answers cannot cross. */
        private const val LANG_PERMISSION = 4
        /** And again for the one-click install's log copy. */
        private const val LOG_PERMISSION = 5
        /**
         * How long a session start waits for the Steam line test. Two edges on a working network
         * answer in a second or two; an edge that is merely unreachable would take its own socket
         * timeouts, and the user is waiting to play, so past this the run is reported as timed out
         * and the session starts without a pin - the script's own order and its slow-edge switch
         * are what the pin was going to improve on, not replace.
         */
        private const val LINE_TEST_BUDGET_MS = 8_000L
        /**
         * How long the card that warns about a long step stays up once it is there. A minute, which
         * is long enough that a user who glances at the phone part-way through the wait sees it.
         */
        private const val LONG_STEP_MS = 60_000L
        /**
         * How long the install waits for that card to have reached the platform before it starts the
         * step. Bounded, because the card is a courtesy and the install is the point: a wedged main
         * thread must not be able to hold a run up with it.
         */
        private const val TOAST_UP_MS = 5_000L
        /** How often that card is put up again on a ROM that will not hold one for [LONG_STEP_MS]. */
        private const val TOAST_REPEAT_MS = 3_000L
    }
}
