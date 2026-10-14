package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.session.FirstLocalInstall
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The desktop's language, packed up to be carried off the device: what Plasma is told to speak and
 * the files that decide it, zipped into a folder the user picks.
 *
 * The desktop's Chinese catalogue lives outside the app - it lands in the rootfs from the package
 * folder's `desktop-l10n.tar.zst` - and whether Plasma picks it up depends on config files the
 * session writes at launch. When the desktop comes up in English and there is no shell on the
 * device to poke at, the only honest answer is to read what is actually there: the config under
 * `$HOME/.config`, the system's own locale files, the scripts that write them, and what the app
 * can read off the installed runtime. This is that, in one zip.
 *
 * The report is built from the rootfs and a small [Facts] the app fills in, rather than straight
 * from a Context, so what it says can be read - and the whole pack run - without a device.
 *
 * Nothing is sent from here: the zip is written straight into the folder the caller names, so it
 * lands where the user can see it in any file manager.
 */
object DesktopLangExport {
    private const val TAG = "DesktopLangExport"

    /** The l10n payload's marker; the same name the install step writes (`.droiddeck-pkg-$id`). */
    private const val L10N_ID = "desktop-l10n"

    private val main = Handler(Looper.getMainLooper())

    /** How far the pack has got, 0..1, or null when none is being made. */
    var progress by mutableStateOf<Float?>(null)
        private set

    /**
     * Packs the dump on a worker thread with [progress] following along, then hands the zip (null
     * if the runtime is not there, the folder cannot be written, or the pack failed) to [done] on
     * the main thread. The zip is written straight into [folder], where the user picked it - no
     * copy is left in the app's own storage. Call on the main thread; a second call while one is
     * running is ignored.
     */
    fun prepare(context: Context, folder: File, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching {
                val root = LinuxRuntime.rootDir(context)
                if (!root.isDirectory) return@runCatching null
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(folder, "DroidDeck-KDE-lang-$stamp.zip").also { zip ->
                    write(root, facts(context, root), zip) { p -> main.post { if (progress != null) progress = p } }
                }
            }
                .onFailure { Log.w(TAG, "could not pack the desktop language dump", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "desktop-lang-export").start()
    }

    /**
     * What only the app can know: the Context-bound halves of the report. Gathered by [facts] and
     * handed to [summary] so the report can be built, and read, without a device.
     */
    internal class Facts(
        val packageLine: String,
        val deviceLine: String,
        val desktopTag: String,
        val desktopInstalled: Boolean,
        val l10nInstalled: Boolean,
        /** One per session script: what is on the device against what this apk carries. */
        val scripts: List<Script>,
        /** The offline folder the installer reads, and what it makes of it; null when none. */
        val folder: String?,
        val folderEntries: List<String>,
        val folderSteps: List<String>,
    ) {
        internal class Script(
            val name: String, val onDevice: String, val inApk: String, val same: Boolean,
        )
    }

    private fun facts(context: Context, root: File): Facts {
        val scripts = listOf("droiddeck-desktop", "droiddeck-session").map { name ->
            val staged = File(root, "usr/local/bin/$name")
            val apk = assetBytes(context, "linuxfs/usr/local/bin/$name")
            Facts.Script(
                name,
                if (staged.isFile) staged.length().toString() + " B, sha256 " + sha256(staged) else "(absent)",
                // In bytes, not String.length: the two are the same only for pure ASCII, and the
                // session script carries a few characters that are not - so the count has to come
                // off the bytes or it reads a shade short of the file on disk.
                apk?.let { it.size.toString() + " B" } ?: "(absent)",
                apk != null && staged.isFile && runCatching { staged.readBytes().contentEquals(apk) }.getOrDefault(false),
            )
        }
        val remembered = FirstLocalInstall.rememberedFolder(context)
        val folder = remembered?.let { runCatching { FirstLocalInstall.folder(it) }.getOrNull() }
        return Facts(
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + "), COK " + BuildConfig.FORK_VERSION,
            Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE,
            BuildConfig.DESKTOP_KDE_TAG,
            DesktopCatalog.desktopInstalled(context),
            FirstLocalInstall.l10nInstalled(context),
            scripts,
            folder?.absolutePath,
            folder?.listFiles()?.sortedBy { it.name }
                ?.map { (if (it.isDirectory) "dir  " else "file ") + it.name + (if (it.isFile) "  " + it.length() + " B" else "") }
                .orEmpty(),
            folder?.let { f -> runCatching { FirstLocalInstall.found(f) }.getOrNull()?.map { it.part.toString() + "  " + it.source.name } }
                .orEmpty(),
        )
    }

    /**
     * What goes in beside the [Facts] above, at the paths it has inside the rootfs. The config
     * Plasma reads first, then the system's locale files, then the scripts this app stages at every
     * session - so it is clear which build of the session shim is actually on the device.
     */
    private val PATHS = listOf(
        // What Plasma 6 reads: [Translations] LANGUAGE, and where KDE's own settings dialog writes.
        "root/.config/plasma-localerc",
        // Our note beside it: the value the install wrote, so ours can be told from the user's own.
        "root/.config/.droiddeck-desktop-language",
        "root/.config/kdeglobals",
        "root/.config/kwinrc",
        "root/.config/plasma-workspace",
        "root/.bashrc",
        "root/.profile",
        "etc/locale.gen",
        "etc/locale.conf",
        "etc/environment",
        "etc/profile",
        "etc/profile.d",
        "usr/local/bin/droiddeck-desktop",
        "usr/local/bin/droiddeck-session",
    )

    /** The catalogue directories whose .mo count says whether the l10n payload ever went in. */
    private val LOCALES = listOf("zh_TW", "zh_CN")

    /** Names in the catalogue that only the l10n payload could have brought. */
    private val KDE_CATALOGUES = listOf("kwin", "plasma_workspace", "plasma-desktop", "systemsettings")

    /** Builds the zip (blocking); the caller has made sure [root] is there. */
    internal fun write(root: File, facts: Facts, zip: File, onProgress: (Float) -> Unit) {
        onProgress(0.05f)
        val summary = summary(root, facts)
        onProgress(0.2f)
        val manifest = localeManifest(root)
        onProgress(0.3f)
        val files = PATHS.flatMap { collect(File(root, it)) }
        onProgress(0.4f)
        zip.parentFile?.mkdirs()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            entry(z, "README.txt", README)
            entry(z, "summary.txt", summary)
            entry(z, "locale-manifest.txt", manifest)
            var i = 0
            for (f in files) {
                // invariantSeparatorsPath, not path: path spells the separators of whatever machine
                // runs this, and a zip entry has to be "/" - the same trap runtime_inputs.py hit.
                addEntry(z, "files/" + f.relativeTo(root).invariantSeparatorsPath, f)
                i++
                onProgress(0.4f + 0.6f * i / files.size.coerceAtLeast(1))
            }
        }
        onProgress(1f)
    }

    /** The file itself, or every file under it when it is a directory; nothing when it is absent. */
    private fun collect(f: File): List<File> = when {
        f.isFile -> listOf(f)
        f.isDirectory -> f.listFiles().orEmpty().sortedBy { it.name }.flatMap { collect(it) }
        else -> emptyList()
    }

    private fun entry(z: ZipOutputStream, name: String, body: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray())
        z.closeEntry()
    }

    private fun addEntry(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(z) }
        z.closeEntry()
    }

    /**
     * Everything the app can read off the device in one place, so the answer is usually in this
     * file alone: the package markers, whether each half of the Chinese support is in, and the full
     * text of every small config that decides what language Plasma speaks.
     */
    internal fun summary(root: File, facts: Facts): String = buildString {
        appendLine("DroidDeck - the desktop's language")
        appendLine("==================================")
        appendLine("Taken:    " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine("Package:  " + facts.packageLine)
        appendLine("Device:   " + facts.deviceLine)
        appendLine()

        appendLine("1. Package markers, and the language the desktop is on")
        appendLine("------------------------------------------------------")
        for (id in listOf(DesktopCatalog.DESKTOP_ID, L10N_ID)) {
            val m = File(root, ".droiddeck-pkg-$id")
            val value = if (m.isFile) m.readText().trim().ifEmpty { "(empty)" } else "(absent)"
            appendLine(".droiddeck-pkg-$id = $value")
        }
        appendLine("desktop pinned tag  = " + facts.desktopTag)
        appendLine("desktopInstalled()  = " + facts.desktopInstalled)
        appendLine("l10nInstalled()     = " + facts.l10nInstalled)
        appendLine("kwin_wayland        = " + File(root, "usr/bin/kwin_wayland").isFile)
        appendLine()
        // The one line that answers the question the dump is usually made for: what language the
        // desktop will actually come up in, and whether it is still the one this app set.
        val current = DesktopLanguage.currentLanguage(root)
        val ours = DesktopLanguage.recordedLanguage(root)
        appendLine("plasma-localerc LANGUAGE = " + (current ?: "(not set)"))
        appendLine("this app last set it to  = " + (ours ?: "(never)"))
        appendLine("so the language is       = " + whoseLanguage(current, ours))
        appendLine()

        appendLine("2. The Chinese catalogues that are in the rootfs")
        appendLine("------------------------------------------------")
        appendLine("The runtime ships glibc's own zh_CN/zh_TW catalogues (glib20, tar, fontconfig...),")
        appendLine("so a bare directory proves nothing; the KDE names are what the l10n payload brings.")
        for (locale in LOCALES) {
            val dir = File(root, "usr/share/locale/$locale/LC_MESSAGES")
            val mo = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mo") }.orEmpty()
            val kde = mo.filter { f -> KDE_CATALOGUES.any { f.name.contains(it) } }
            appendLine("usr/share/locale/$locale/LC_MESSAGES: " + mo.size + " .mo, of them KDE: " + kde.size)
            if (kde.isNotEmpty()) appendLine("    " + kde.map { it.name }.sorted().joinToString(", "))
        }
        appendLine()

        appendLine("3. The session scripts this app stages (they are re-staged at every launch)")
        appendLine("---------------------------------------------------------------------------")
        for (script in facts.scripts) {
            appendLine("usr/local/bin/" + script.name)
            appendLine("    on the device: " + script.onDevice)
            appendLine("    in this apk:   " + script.inApk)
            appendLine("    same as the apk: " + script.same)
        }
        // "LANGUAGE=zh_TW" rather than the file's name: the script talks about plasma-localerc in
        // its comments - explaining why it does NOT write it - so the name alone reads true on a
        // script that has stood down. Only a line that would set a language counts.
        val desktopScript = runCatching { File(root, "usr/local/bin/droiddeck-desktop").readText() }.getOrNull()
        val writesLanguage = desktopScript != null &&
            listOf("LANGUAGE=zh_TW", "LANGUAGE=zh_CN").any { desktopScript.contains(it) }
        appendLine("desktop script writes the language: " + writesLanguage +
            "  (false is right: the language is set as the pack goes in, not at every launch)")
        appendLine()

        appendLine("4. The offline folder the installer reads")
        appendLine("-----------------------------------------")
        appendLine("read from: " + (facts.folder ?: "(none)"))
        facts.folderEntries.forEach { appendLine("    " + it) }
        appendLine("the installer sees:")
        if (facts.folderSteps.isEmpty()) appendLine("    (nothing this can install)") else facts.folderSteps.forEach { appendLine("    " + it) }
        appendLine()

        appendLine("5. The config files themselves")
        appendLine("------------------------------")
        for (relative in PATHS) {
            val f = File(root, relative)
            if (!f.exists()) { appendLine("### $relative - ABSENT"); appendLine(); continue }
            if (f.isDirectory) {
                appendLine("### $relative/ - directory, " + collect(f).size + " files (in the zip)")
                appendLine()
                continue
            }
            val body = runCatching { f.readText() }.getOrNull()
            appendLine("### $relative - " + f.length() + " B")
            appendLine(if (body == null) "(not text; carried in the zip as it is)" else body.trimEnd())
            appendLine()
        }
    }

    /**
     * Whose language the desktop is on, said in a line: ours, the user's, or nobody's. The dump is
     * usually made to answer exactly this, and both values it is read from are in the same report.
     */
    internal fun whoseLanguage(current: String?, ours: String?): String = when {
        current == null && ours == null -> "nobody's - the desktop comes up in the system's own"
        current == null -> "ours was $ours, but the file no longer names a language"
        ours == null -> "the device's own - this app has never set it"
        current == ours -> "ours - set when the Chinese pack went in, unchanged since"
        else -> "the user's own - picked in KDE's settings after this app set $ours"
    }

    /** Every catalogue under the two locales, and the locale directories the rootfs carries. */
    internal fun localeManifest(root: File): String = buildString {
        appendLine("usr/share/locale, as it is on the device")
        appendLine("========================================")
        val localeRoot = File(root, "usr/share/locale")
        val dirs = localeRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        appendLine("directories (" + dirs.size + "): " + dirs.joinToString(" "))
        appendLine()
        for (locale in LOCALES) {
            val dir = File(localeRoot, "$locale/LC_MESSAGES")
            val mo = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mo") }?.sortedBy { it.name }.orEmpty()
            appendLine("--- $locale/LC_MESSAGES: " + mo.size + " .mo, " + mo.sumOf { it.length() } + " B in all")
            mo.forEach { appendLine("    " + it.name + "  " + it.length()) }
            appendLine()
        }
        appendLine("Every .mo in the whole tree, by locale:")
        localeRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { loc ->
            val n = File(loc, "LC_MESSAGES").listFiles()?.count { it.isFile && it.name.endsWith(".mo") } ?: 0
            if (n > 0) appendLine("    " + loc.name + "  " + n)
        }
    }

    /** The staged copy of an asset, at the bytes: a size in bytes has to match what is on disk. */
    private fun assetBytes(context: Context, path: String): ByteArray? =
        runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()

    private fun sha256(f: File): String =
        runCatching { Hashes.sha256(f) }.getOrDefault("(unreadable)")

    private val README = """
        DroidDeck - the desktop's language, packed up
        =============================================
        Made with the "KDE language files" button on the Desktop page, which asks for a folder and
        drops this zip in it. Nothing left the device: it went where you pointed it.

          summary.txt          everything the app could read off the installed runtime in one
                               place: the package markers, whether each half of the Chinese
                               support is in, how many catalogues the two locales carry, and the
                               full text of every small config that decides what language
                               Plasma speaks.
          locale-manifest.txt  what is under usr/share/locale: every catalogue for zh_TW and
                               zh_CN with its size, and the locale directories.
          files/               the files themselves, at the paths they have inside the rootfs
                               (root/.config/..., etc/..., usr/local/bin/...).

        What is being looked at: the desktop package ships English only on purpose, our l10n
        payload adds the Chinese catalogues, and the language is set as that pack goes in - the
        install writes the same plasma-localerc KDE's own settings page writes, and the Desktop
        page has a button that runs it again. If the desktop still comes up in English, one of
        those is not on the device, or KDE has been given another language since - summary.txt says
        which, and whose the setting is.
    """.trimIndent()
}
