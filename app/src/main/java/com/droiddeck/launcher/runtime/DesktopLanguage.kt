package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * The desktop's own language: `plasma-localerc`, the one file Plasma 6 reads to decide what to
 * speak.
 *
 * The desktop package ships English on purpose, and KDE's settings dialog writes this file the
 * first time the desktop comes up - so a device with the Chinese pack in can still say everything
 * in English, because nothing has told Plasma to speak Chinese. Picking a language in KDE's own
 * settings writes exactly this file; that is the fix, done by hand and only reachable from inside
 * the desktop.
 *
 * So the one-click install's l10n step does it, right after the pack goes in. The text below is a
 * byte-for-byte copy of what KDE's settings page leaves behind once Traditional Chinese is applied
 * and the desktop restarted - read off a device with the diagnostics export (see the archive,
 * section Q). Setting it here, at the one moment the Chinese pack arrives, is also what keeps the
 * user's own choice afterwards: nothing in the session scripts writes this file again, so a
 * language picked in KDE later - Japanese, or English back again - stands. The Desktop page's own
 * button runs the same code, for a device whose pack went in before this existed or whose language
 * was changed since.
 *
 * [recordedLanguage] is the note left beside it: the value we last wrote, so a diagnostics dump can
 * say whether the file is still ours or the user's own.
 */
object DesktopLanguage {
    private const val TAG = "DesktopLanguage"

    /** What the desktop is put on when the Chinese pack arrives: Traditional Chinese. */
    const val LANGUAGE = "zh_TW"

    /** `plasma-localerc` at the path it has inside the rootfs; where KDE's settings write too. */
    private const val LOCALERC = "root/.config/plasma-localerc"

    /** Our own note beside it: the value we last wrote, so ours can be told from the user's. */
    private const val NOTE = "root/.config/.droiddeck-desktop-language"

    /**
     * What KDE's settings page writes when Traditional Chinese is applied and the desktop
     * restarted, copied off the device byte for byte (241 bytes). `[Formats]` carries the full set
     * of locale variables KDE sets alongside the language, and `[Translations] LANGUAGE` is the key
     * Plasma 6 speaks from - Plasma 5's `[Locale] Language` is not read any more.
     */
    internal const val TRADITIONAL_CHINESE =
        "[Formats]\n" +
            "LANG=zh_TW.UTF-8\n" +
            "LC_ADDRESS=zh_TW.UTF-8\n" +
            "LC_MEASUREMENT=zh_TW.UTF-8\n" +
            "LC_MONETARY=zh_TW.UTF-8\n" +
            "LC_NAME=zh_TW.UTF-8\n" +
            "LC_NUMERIC=zh_TW.UTF-8\n" +
            "LC_PAPER=zh_TW.UTF-8\n" +
            "LC_TELEPHONE=zh_TW.UTF-8\n" +
            "LC_TIME=zh_TW.UTF-8\n" +
            "\n" +
            "[Translations]\n" +
            "LANGUAGE=zh_TW\n"

    /** Puts the desktop on Traditional Chinese. False when the rootfs is not there or it would not write. */
    fun applyTraditionalChinese(context: Context): Boolean = write(LinuxRuntime.rootDir(context))

    /**
     * The write itself, over a rootfs: the config KDE's settings would have written, plus our own
     * note of what we set. Made to be read and run without a device, so the diagnostics dump's
     * answers can be tested - it takes the rootfs, not a Context.
     */
    internal fun write(root: File): Boolean = runCatching {
        if (!root.isDirectory) return false
        File(root, LOCALERC).parentFile?.mkdirs()
        FileUtils.writeString(File(root, LOCALERC), TRADITIONAL_CHINESE)
        FileUtils.writeString(File(root, NOTE), LANGUAGE + "\n")
        true
    }
        .onFailure { Log.w(TAG, "could not set the desktop's language", it) }
        .getOrDefault(false)

    /** What we last set the desktop to, or null when we never have. */
    fun recordedLanguage(context: Context): String? = recordedLanguage(LinuxRuntime.rootDir(context))

    internal fun recordedLanguage(root: File): String? =
        File(root, NOTE).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The language Plasma will come up in, read out of the file the way Plasma reads it: the
     * `LANGUAGE` key under `[Translations]`. Null when the file or the key is not there - which is
     * a device nobody has set a language on, and one that comes up in the system's own.
     */
    internal fun currentLanguage(root: File): String? {
        val body = File(root, LOCALERC).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?: return null
        var translations = false
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                translations = trimmed.equals("[Translations]", ignoreCase = true)
                continue
            }
            if (translations && trimmed.startsWith("LANGUAGE=")) {
                return trimmed.removePrefix("LANGUAGE=").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
