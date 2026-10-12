package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes every installed GPU driver into a folder the user picked, as zips the GPU drivers tab's
 * own "Import .zip" takes back: one per driver, and one per bundle holding both its halves.
 *
 * Both driver directories are app-private - `data/user/0/<package>/files/...`, which no file
 * manager on the device can open - and neither holds a file that means anything on its own. This
 * is the way out, and it needs no root: the target is an ordinary folder on shared storage.
 *
 * Inside the picked folder:
 * ```
 *   android-<id>.zip    meta.json + the library it names - an AdrenoTools zip   (TurnipDriver)
 *   linux-<id>.zip      meta.json + libvulkan_freedreno.so - a "-Linux" zip     (LinuxVulkanDriverManager)
 *   bundle-<name>.zip   manifest.json + android/ + linux/ - both halves of a pair (DriverBundle)
 *   drivers.txt         what each zip is, and which of them is the driver in use
 * ```
 * A Linux driver's own `icd.json` is left out on purpose: it names an absolute path from the device
 * it was imported on, and the importer writes a correct one for whatever device it lands on.
 * Everything else goes out as it lies, so a zip made here imports back byte for byte.
 */
object DriverExport {
    private const val TAG = "DriverExport"
    private const val BUNDLE_MANIFEST = "manifest.json"
    private const val LIST = "drivers.txt"

    private const val ANDROID = "android-"
    private const val LINUX = "linux-"
    private const val BUNDLE = "bundle-"
    /** The column the names in drivers.txt line up in. */
    private const val NAME_COLUMN = 36

    /** One file going into a zip: from disk, or made here (a bundle's manifest). */
    private class Part(val name: String, val file: File?, val text: ByteArray? = null) {
        val length: Long get() = file?.length() ?: (text?.size?.toLong() ?: 0L)
    }

    /**
     * One zip to write. [ids] are the driver ids it holds - one, or a bundle's two - and they are
     * what [list] marks "in use" against.
     */
    private class Job(val file: File, val parts: List<Part>, val ids: List<String>, val line: String) {
        val bytes: Long get() = parts.sumOf { it.length }
    }

    /** What an export wrote: the zip names, how many drivers, and what was left out (with why). */
    class Result(val files: List<String>, val drivers: Int, val notes: List<String>)

    /**
     * Export every installed driver into [folder], creating it when it is not there. [progress] is
     * called with 0..100 as the bytes are written, on the calling thread. Throws [IOException] only
     * when the folder itself cannot be written: one driver that cannot be read is named in
     * [Result.notes] and the rest still go out.
     */
    fun export(context: Context, folder: File, progress: ((Int) -> Unit)? = null): Result {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create " + folder)

        val td = TurnipDriver(context)
        val lm = LinuxVulkanDriverManager(context)
        val bundles = DriverBundle.all(context)
        // A bundle's halves are already in its own zip; listing them again would double the export.
        val inBundle = bundles.flatMap { listOf(it.androidId, it.linuxId) }.toSet()
        val jobs = ArrayList<Job>()
        val notes = ArrayList<String>()

        for (bundle in bundles) {
            val parts = bundleParts(td, lm, bundle)
            if (parts == null) {
                notes.add(bundle.label + ": one half is gone, its drivers are listed on their own")
                continue
            }
            val name = BUNDLE + LinuxVulkanDriverManager.sanitizeId(bundle.label) + ".zip"
            jobs.add(Job(
                File(folder, name), parts, listOf(bundle.androidId, bundle.linuxId),
                line(name, "${bundle.label}  (display ${bundle.androidId}, runtime ${bundle.linuxId})"),
            ))
        }

        val display = ArrayList<String>()
        for (id in td.visibleBundled()) {
            if (td.isInstalled(id)) display.add(id)
            else notes.add("$id: built into the app, not unpacked on this device - nothing to export")
        }
        display.addAll(td.enumerateImported())
        for (id in display.distinct().filterNot { it in inBundle }) {
            val files = driverFiles(File(td.driverPath(id)))
            val name = ANDROID + id + ".zip"
            if (files.isEmpty()) { notes.add("$name: nothing to read"); continue }
            val detail = listOfNotNull(
                if (id in TurnipDriver.BUNDLED) "bundled" else if (TurnipReleases.isDownloaded(context, id)) "downloaded" else "imported",
                td.driverVersion(id).takeIf { it.isNotEmpty() },
            )
            jobs.add(Job(File(folder, name), files.map { Part(it.name, it) }, listOf(id),
                line(name, "${td.displayName(id)}  (${detail.joinToString(", ")})")))
        }

        for (id in lm.enumerateInstalledDrivers().filterNot { it in inBundle }) {
            // icd.json is the one file the import rewrites; the rest is the driver as it came.
            val files = driverFiles(lm.getDriverDir(id)).filter { it.name != LinuxVulkanDriverManager.ICD_NAME }
            val name = LINUX + id + ".zip"
            if (files.isEmpty()) { notes.add("$name: nothing to read"); continue }
            val detail = listOfNotNull(
                lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                if (TurnipReleases.isDownloaded(context, id)) "downloaded" else "imported",
            )
            jobs.add(Job(File(folder, name), files.map { Part(it.name, it) }, listOf(id),
                line(name, "${lm.getDriverName(id)}  (${detail.joinToString(", ")})")))
        }

        val total = jobs.sumOf { it.bytes }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        val written = ArrayList<Job>()
        for (job in jobs) {
            try {
                write(job) { bytes ->
                    done += bytes
                    val percent = (done * 100 / total).toInt()
                    if (percent != shown) { shown = percent; progress?.invoke(percent) }
                }
                written.add(job)
            } catch (e: Exception) {
                Log.w(TAG, "exporting " + job.file.name, e)
                FileUtils.delete(job.file)
                notes.add(job.file.name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }

        try {
            FileUtils.writeString(File(folder, LIST), list(context, td, written, notes))
        } catch (e: Exception) {
            Log.w(TAG, "writing $LIST", e)
            notes.add("$LIST: " + (e.message ?: e.javaClass.simpleName))
        }
        progress?.invoke(100)
        return Result(written.map { it.file.name }, written.size, notes)
    }

    /** Both halves of [bundle] as zip parts (root manifest, `android/`, `linux/`), or null. */
    private fun bundleParts(td: TurnipDriver, lm: LinuxVulkanDriverManager, bundle: DriverBundle.Bundle): List<Part>? {
        val android = driverFiles(File(td.driverPath(bundle.androidId)))
        val linux = driverFiles(lm.getDriverDir(bundle.linuxId)).filter { it.name != LinuxVulkanDriverManager.ICD_NAME }
        if (android.isEmpty() || linux.isEmpty()) return null
        val manifest = JSONObject()
            .put("package", bundle.name)
            .put("version", bundle.version)
            // The id the bundle is registered under came from the release tag; carrying it here
            // gives the same id back on import.
            .put("tag", bundle.id)
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))
        val parts = ArrayList<Part>()
        parts.add(Part(BUNDLE_MANIFEST, null, manifest.toString(2).toByteArray(Charsets.UTF_8)))
        android.forEach { parts.add(Part("android/" + it.name, it)) }
        linux.forEach { parts.add(Part("linux/" + it.name, it)) }
        return parts
    }

    /** Every regular file of a driver directory, by name - what a zip of it carries. */
    private fun driverFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** Writes one zip, reporting the bytes as they go in. */
    private fun write(job: Job, onBytes: (Long) -> Unit) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(job.file), 1 shl 16)).use { zip ->
            for (part in job.parts) {
                val text = part.text
                val source = part.file
                if (text == null && source == null) continue
                zip.putNextEntry(ZipEntry(part.name))
                if (text != null) {
                    zip.write(text)
                    onBytes(text.size.toLong())
                } else if (source != null) {
                    source.inputStream().use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * The read-me beside the zips: what each one is, how to put it back, and what was not exported.
     * Plain text in English, like the session folders' own reports.
     */
    private fun list(context: Context, td: TurnipDriver, written: List<Job>, notes: List<String>): String {
        // What the app would run right now, in both lists: a display driver is always set (Auto
        // picks by GPU), a runtime one only when the user chose an imported driver.
        val inUse = HashSet<String>()
        val display = SessionPrefs.androidDriver(context)
        inUse.add(if (display.isNotEmpty()) display else td.autoId())
        val runtime = SessionPrefs.linuxDriver(context)
        if (runtime.isNotEmpty()) inUse.add(runtime)

        val text = StringBuilder()
        text.append("DroidDeck GPU drivers\n")
        text.append("Exported ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .append(" from ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n\n")
        text.append("Display (Android) drivers - what the app's compositor puts the frame on the screen\n")
        text.append("with. AdrenoTools zips: meta.json plus the library it names.\n")
        text.append("Runtime (Linux) drivers - what the Steam client, the games it launches and the\n")
        text.append("desktop render on. \"-Linux\" zips: glibc, not interchangeable with the ones above.\n")
        text.append("Bundles - one zip carrying both halves of a matched pair, installed and picked\n")
        text.append("together.\n\n")
        for (job in written) {
            text.append(job.line)
            if (job.ids.any { it in inUse }) text.append("  [in use]")
            text.append('\n')
        }
        if (notes.isNotEmpty()) {
            text.append("\nNot exported:\n")
            for (note in notes) text.append("  ").append(note).append('\n')
        }
        text.append("\nPutting one back: GPU drivers tab, \"Import .zip\". A bundle's zip installs both\n")
        text.append("of its halves and sets them together; a single driver's zip needs no renaming - the\n")
        text.append("importer reads what is inside it. A Linux driver's icd.json is left out, it names an\n")
        text.append("absolute path from the device it came from and is written again on import.\n")
        return text.toString()
    }

    /** A drivers.txt line: the file name in a fixed column, then what the driver is. */
    private fun line(name: String, detail: String): String =
        "  " + (if (name.length >= NAME_COLUMN) "$name  " else name.padEnd(NAME_COLUMN)) + detail
}
