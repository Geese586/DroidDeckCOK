package com.droiddeck.launcher.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * The app's own file picker, the way Bannerlator's File Manager offers its pick mode: one intent
 * builder per kind of pick, and the picked path read back from the result. Every import on the
 * main screen goes through here rather than Android's document picker, which cannot show an SD
 * card as a card, remembers nothing, and hands back a content URI the runtime cannot bind.
 */
object InAppFilePicker {
    /** Pick one file. [extensions] are lowercase, without the dot; empty = any file. */
    fun buildIntent(
        context: Context,
        extensions: List<String> = emptyList(),
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_EXTENSIONS, extensions.toTypedArray())
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    /** Pick a folder: only folders are listed, and "Use this folder" returns the one open. */
    fun buildDirIntent(
        context: Context,
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_PICK_DIRECTORY, true)
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    fun pickedPath(data: Intent?): String? = data?.getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE)

    fun pickedFile(data: Intent?): File? = pickedPath(data)?.let { File(it) }

    /** The picked file as the `file:` URI the import code already accepts. */
    fun pickedUri(data: Intent?): Uri? = pickedFile(data)?.let { Uri.fromFile(it) }
}
