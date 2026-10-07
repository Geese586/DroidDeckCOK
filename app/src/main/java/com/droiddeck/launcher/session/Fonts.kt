package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * The fonts the runtime does not ship: Chinese, Japanese and Korean text, which the DejaVu family
 * in the rootfs has no glyphs for. Without one, every CJK string on the desktop - the LXQt menus,
 * a page in Firefox, a window title - comes out as boxes.
 *
 * The desktop's own way in is copying the files into /usr/share/fonts by hand, which is what this
 * does, into the TTF directory the rootfs already keeps its fonts in. That location is scanned
 * either way: fontconfig's fonts.conf lists `/usr/share/fonts` and walks it recursively, so a file
 * dropped there is picked up by the next program that starts - the refresh below only saves that
 * program the first scan.
 *
 * Nothing here is checked against a checksum: these are the user's own files, and a font is read
 * by fontconfig rather than run.
 */
object Fonts {
    private const val TAG = "Fonts"

    /** The rootfs directory the desktop package puts its own fonts in (DejaVu, Adwaita). */
    private const val SYSTEM_DIR = "usr/share/fonts/TTF"

    /** The extensions fontconfig reads; anything else in the folder is left alone. */
    private val EXTENSIONS = setOf("ttf", "ttc", "otf", "otc")

    /** Every font in [dir], in a stable order, so the same folder installs the same way twice. */
    fun fontFiles(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    private fun systemDir(context: Context) = File(LinuxRuntime.rootDir(context), SYSTEM_DIR)

    /** `/usr/share/fonts` itself: fontconfig scans it too, and the desktop's hand-copies land here. */
    private fun fontsRoot(context: Context) = systemDir(context).parentFile

    /**
     * Where [source] already is, if it is: the TTF directory this installs into, or /usr/share/fonts
     * itself - which is where a font copied in by hand, the desktop's own way in, ends up. Names and
     * lengths rather than digests: this only has to recognise the same file, and a font copied twice
     * costs nothing but disk.
     */
    private fun located(context: Context, source: File): File? {
        val candidates = listOf(File(systemDir(context), source.name), File(fontsRoot(context), source.name))
        return candidates.firstOrNull { it.isFile && it.length() == source.length() }
    }

    /**
     * True when everything [dir] holds is already installed, in either location. Decides whether the
     * step is offered at all.
     */
    fun installed(context: Context, dir: File): Boolean {
        val files = fontFiles(dir)
        return files.isNotEmpty() && files.all { located(context, it) != null }
    }

    /**
     * Copies [dir]'s fonts into the rootfs and refreshes fontconfig's cache. Returns null when they
     * are in, or a message for the user. Fonts already there - in either location - are left alone,
     * so a folder installed twice, or one the user has already copied in by hand, is no work.
     *
     * A failed refresh is not a failed install: the files are in place, and fontconfig scans
     * /usr/share/fonts itself when it has no cache for it. What is left behind is logged instead.
     */
    fun install(context: Context, dir: File, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        val files = fontFiles(dir)
        if (files.isEmpty()) return "That folder holds no font files"
        val destination = systemDir(context)
        val missing = files.filter { located(context, it) == null }
        if (missing.isNotEmpty() && !destination.isDirectory && !destination.mkdirs()) {
            return "The runtime's font directory cannot be written"
        }
        try {
            missing.forEachIndexed { index, source ->
                onProgress("Copying ${source.name}", (index + 1) * 100 / missing.size)
                source.copyTo(File(destination, source.name), overwrite = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "copy from $dir", e)
            return e.message ?: "The fonts could not be copied"
        }
        if (missing.isEmpty()) {
            onProgress("Fonts already installed", 100)
            return null
        }
        onProgress("Refreshing the font cache", -1)
        refreshCache(context)
        return null
    }

    /**
     * fc-cache inside the runtime, best effort. fontconfig and fc-cache are part of the rootfs - the
     * desktop does not bring either - so this normally runs; when it cannot, the install is still
     * complete.
     */
    private fun refreshCache(context: Context) {
        try {
            // The runtime is the working directory, as for a session: fc-cache writes its cache
            // under /var/cache/fontconfig, which only exists inside the rootfs.
            val runtimeDir = File(context.filesDir, ".font-cache-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "/usr/bin/fc-cache", "-f",
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(LinuxRuntime.rootDir(context))
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            child.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.i(TAG, it) } }
            val status = child.waitFor()
            if (status != 0) Log.w(TAG, "fc-cache exited $status; the fonts are in place anyway")
        } catch (e: Exception) {
            Log.w(TAG, "fc-cache", e)
        }
    }
}
