package com.droiddeck.launcher.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File

// Reading and setting a file's read-only and hidden attributes (the DOS attribute xattr).

// ── DOS file attributes (Read-only / Hidden) ──
// The in-container file manager (wfm.exe) exposes these in its Properties dialog; this mirrors the
// same two toggles for files browsed from the app side. No root - we only touch permission bits the
// app owns (container files) and the Wine DOS-attribute xattr.

// FILE_ATTRIBUTE_HIDDEN, as Wine encodes it in the user.DOSATTRIB extended attribute.
private const val FILE_ATTRIBUTE_HIDDEN = 0x2

private const val DOSATTRIB_XATTR = "user.DOSATTRIB"

// Snapshot of a file's two toggleable attributes. hiddenSupported is false when the underlying
// filesystem can't store the DOSATTRIB xattr (the FUSE /storage volumes) - the Hidden toggle is then
// disabled while Read-only keeps working.
internal data class FileAttrState(
    val readOnly: Boolean,
    val hidden: Boolean,
    val hiddenSupported: Boolean,
)

// Wine (and Samba) store user.DOSATTRIB as an ASCII hex string ("0x22"), sometimes with trailing
// Samba fields after a separator. We only need the leading DOS-attribute hex, so read from the "0x"
// prefix and stop at the first non-hex character.
private fun parseDosAttrib(raw: ByteArray): Int {
    val s = String(raw, Charsets.US_ASCII).trim()
    val hex = if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2) else s
    val digits = hex.takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return digits.toIntOrNull(16) ?: 0
}

// Read the current read-only + Wine-hidden state. Never throws: an xattr-unsupported filesystem
// comes back hiddenSupported=false (Hidden toggle disabled), Read-only always resolves.
internal fun readFileAttrs(file: File): FileAttrState {
    val path = file.absolutePath
    // Read-only reflects the OWNER write bit - that's what Wine maps to FILE_ATTRIBUTE_READONLY.
    val readOnly = runCatching {
        (android.system.Os.stat(path).st_mode and android.system.OsConstants.S_IWUSR) == 0
    }.getOrDefault(!file.canWrite())

    var hidden = false
    var supported = true
    try {
        hidden = (parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR)) and FILE_ATTRIBUTE_HIDDEN) != 0
    } catch (e: android.system.ErrnoException) {
        // ENODATA: xattr namespace works, the attribute just isn't set yet -> still toggleable.
        // ENOTSUP/EOPNOTSUPP/anything else: the fs can't store it -> disable the Hidden toggle.
        supported = e.errno == android.system.OsConstants.ENODATA
    } catch (e: Exception) {
        supported = false
    }
    return FileAttrState(readOnly, hidden, supported)
}

// Toggle read-only by flipping the write bits, preserving every other permission bit. Setting
// read-only clears owner/group/other write (so Wine sees FILE_ATTRIBUTE_READONLY regardless of which
// write bit it checks); clearing it restores owner write. Falls back to File.setWritable if chmod is
// somehow refused. Returns true on success.
internal fun setReadOnly(file: File, readOnly: Boolean): Boolean = runCatching {
    val path = file.absolutePath
    val mode = android.system.Os.stat(path).st_mode
    val writeBits = android.system.OsConstants.S_IWUSR or
        android.system.OsConstants.S_IWGRP or android.system.OsConstants.S_IWOTH
    val newMode = if (readOnly) mode and writeBits.inv()
        else mode or android.system.OsConstants.S_IWUSR
    android.system.Os.chmod(path, newMode)
    true
}.getOrElse { file.setWritable(!readOnly, true) }

// Flip Wine's DOS hidden bit in user.DOSATTRIB, preserving the other DOS-attribute bits
// (archive/system/read-only) already encoded there, and synthesizing a minimal value when absent.
// Writes Wine's own "0x%x" format, which Wine reads back natively. Returns false when the fs can't
// store the xattr, so the caller can disable just the Hidden toggle.
internal fun setHidden(file: File, hidden: Boolean): Boolean = try {
    val path = file.absolutePath
    var attr = try {
        parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR))
    } catch (e: android.system.ErrnoException) {
        if (e.errno == android.system.OsConstants.ENODATA) 0 else throw e
    }
    attr = if (hidden) attr or FILE_ATTRIBUTE_HIDDEN else attr and FILE_ATTRIBUTE_HIDDEN.inv()
    val value = "0x%x".format(attr).toByteArray(Charsets.US_ASCII)
    android.system.Os.setxattr(path, DOSATTRIB_XATTR, value, 0)
    true
} catch (e: Exception) {
    false
}
