package com.droiddeck.launcher.gpu

import android.content.Context
import java.io.File

/**
 * What a driver zip in an offline folder is, and whether this device already holds it.
 *
 * "Export drivers" writes those zips itself and names each one after the driver it carries -
 * `android-<id>.zip` and `linux-<id>.zip` for a single driver's half, `bundle-<label>.zip` for a
 * matched pair. That name is the whole contract. The `<id>` in it is the directory the driver lives
 * in once installed: the export took it from the installed driver, and it is exactly the name the
 * importer works out again from the same zip's own metadata, so a driver that is already in can be
 * recognised by looking the id up rather than by importing it a second time beside the first.
 *
 * The bundled two are the exception the id lookup cannot serve - their directories are named by the
 * apk, not by any metadata inside them - and they need no serving either: the app unpacks one out of
 * its own assets the moment the GPU calls for it. A zip of either therefore counts as held without
 * anything being installed, and is never installed here.
 *
 * A zip whose name is not one of those three is not read at all: a folder of drivers the export
 * wrote holds these and nothing else, and guessing at a name this did not write would mean judging
 * "already in" against an id nothing can be sure of - which is how a first-time install comes to
 * offer itself forever.
 */
internal object DriverZip {
    private const val BUNDLE = "bundle-"
    private const val DISPLAY = "android-"
    private const val RUNTIME = "linux-"
    private const val ZIP = ".zip"

    /** Which list a zip belongs in: both halves at once, the display driver, or the runtime one. */
    enum class Kind { BUNDLE, DISPLAY, RUNTIME }

    /** A zip's kind and the id it installs as - for a bundle, its label the way the name carries it. */
    class Entry(val kind: Kind, val id: String)

    /** What [zip] is by its name, or null when it is not a zip "Export drivers" wrote. */
    fun read(zip: File): Entry? {
        val name = zip.name
        if (!name.endsWith(ZIP, ignoreCase = true)) return null
        val stem = name.substring(0, name.length - ZIP.length)
        val entry = when {
            stem.startsWith(BUNDLE) -> Entry(Kind.BUNDLE, stem.substring(BUNDLE.length))
            stem.startsWith(DISPLAY) -> Entry(Kind.DISPLAY, stem.substring(DISPLAY.length))
            stem.startsWith(RUNTIME) -> Entry(Kind.RUNTIME, stem.substring(RUNTIME.length))
            else -> null
        }
        return entry?.takeIf { it.id.isNotEmpty() }
    }

    /**
     * True when this device already holds what [zip] carries.
     *
     * A bundle is matched by its label, which is what its file name was made of, so a bundle
     * installed under a suffixed id still counts as the same one. A bundled display driver counts
     * because the apk has it - whether it has been unpacked yet or not.
     */
    fun installed(context: Context, entry: Entry): Boolean = when (entry.kind) {
        Kind.BUNDLE -> DriverBundle.all(context)
            .any { LinuxVulkanDriverManager.sanitizeId(it.label) == entry.id }
        Kind.DISPLAY -> entry.id in TurnipDriver.BUNDLED || TurnipDriver(context).isInstalled(entry.id)
        Kind.RUNTIME -> LinuxVulkanDriverManager(context).isInstalled(entry.id)
    }

    /** True when a bundled display driver, which is never worth installing: the apk carries it. */
    fun isBundled(entry: Entry): Boolean =
        entry.kind == Kind.DISPLAY && entry.id in TurnipDriver.BUNDLED
}
