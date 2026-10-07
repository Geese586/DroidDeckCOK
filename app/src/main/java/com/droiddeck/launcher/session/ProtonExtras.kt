package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray

/** Immediate download and installation of the optional ARM64 Proton builds. */
object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/%s/releases?per_page=15"
    @Volatile
    var installInProgress = false
        private set

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    /**
     * [sha256] from GitHub's asset digest; [sha512] the URL of a checksum file published beside it.
     * [publishedAsset] hands one out so an archive the user picked can be checked by the same
     * rules a download is.
     */
    internal data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                              val sha256: String? = null)

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")

    /** The installed build's directory name (e.g. GE-Proton11-7), or null. */
    fun installed(context: Context, tool: Tool): String? =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.maxByOrNull { it.name }?.name

    /** A request left by an earlier app version, still consumed by the session shim. */
    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Deletes the installed build; Steam discovers its absence on its next start. */
    fun remove(context: Context, tool: Tool) {
        installed(context, tool)?.let { File(toolsDir(context), it).deleteRecursively() }
    }

    /** Downloads, verifies and registers the latest build without waiting for another session. */
    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "A compatibility tool install is already running"
        installInProgress = true
        return try { installNow(context, tool, onProgress) } finally { installInProgress = false }
    }

    /**
     * Installs a build from an archive already on the device, for a network that cannot reach
     * GitHub at all - the case this path exists for. [asset] is the release the archive was
     * published as, found by file name so the same checksum can be checked; null when no release
     * publishes that name, which is when the caller has to ask the user whether to go on.
     */
    @Synchronized
    internal fun installFromFile(context: Context, tool: Tool, archive: File, asset: Asset?,
                                 onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "A compatibility tool install is already running"
        installInProgress = true
        return try { installArchive(context, tool, archive, asset, false, onProgress) } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "Stop the active session before installing compatibility tools"
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"

        val asset = findLatestAsset(tool) ?: return "Could not find an ARM64 release for ${tool.name}"
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return "At least 4 GB free plus the download size is required to install ${tool.name}"
        }

        onProgress("Downloading ${tool.name} ${asset.tag}", 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true) { fraction ->
                onProgress("Downloading ${tool.name} ${asset.tag}", if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress("Retrying download · attempt ${attempt + 2} of 5", -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "Download interrupted"
                }
            }
        }
        if (!downloaded) return "Download failed; the partial file is kept so a retry can resume"
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return "Download exceeded the release size and was deleted"
            }
            return "Download was incomplete; the partial file is kept for a resumable retry"
        }

        return installArchive(context, tool, archive, asset, true, onProgress)
    }

    /**
     * Verifies [archive] and puts it in the client's compatibilitytools.d. [asset] is the release
     * it was published as, or null when no release publishes that name and the user has accepted
     * that nothing can check it. [own] is whether the file was downloaded here: one that was is
     * deleted once it has served, while one the user picked is left where it is.
     *
     * The tree is unpacked by the guest's own droiddeck-proton-extra - the script the session shim
     * would otherwise run - which is told the path and reads the tarball itself.
     */
    private fun installArchive(context: Context, tool: Tool, archive: File, asset: Asset?, own: Boolean,
                               onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "Stop the active session before installing compatibility tools"
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        if (!archive.isFile) return "That file cannot be read"
        if (asset != null && asset.size > 0 && archive.length() != asset.size) {
            return "The file is not the size ${tool.name} ${asset.tag} publishes; nothing was installed"
        }

        // One checksum has to be found and has to match: GitHub's sha256 digest, else the
        // release's own sha512 file. A file neither vouches for is not installed.
        if (asset != null) {
            onProgress("Verifying", -1)
            if (asset.sha256 == null && asset.sha512 == null) {
                if (own) archive.delete()
                return "${tool.name} ${asset.tag} publishes no checksum; nothing was installed"
            }
            val verified = if (asset.sha256 != null) {
                asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            } else {
                val expected = Downloader.downloadString(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return "Could not read the release checksum; try again"
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            if (!verified) {
                if (own) archive.delete()
                return if (own) "Checksum mismatch; the download was deleted"
                else "Checksum mismatch; nothing was installed and the file was left alone"
            }
        }

        if (SessionState.running) return "A session started meanwhile; stop it before installing compatibility tools"
        onProgress("Installing ${tool.name}", -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/droiddeck-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            // The guest opens the tarball itself, so where it sits has to be visible inside. The
            // app's own directories are bound into every command; a file anywhere else - where a
            // picked one lives - is not, so its directory is bound at the same path. A download
            // already sits in one of ours and the bind is merely redundant.
            val extraBinds = archive.parentFile?.let { listOf(it.path) } ?: emptyList()
            val command = LinuxRuntime.command(context, null, runtimeDir, null, extraBinds, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress("Installing ${tool.name}", -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) "${tool.name} installation failed (exit $status)"
            else {
                if (own) archive.delete()
                unqueue(context, tool)
                if (EsyncPacks.enabled(context)) {
                    onProgress("Fetching droiddeck-esync pack", -1)
                    try {
                        EsyncPacks.fetchWanted(context, root, onProgress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync pack for ${tool.name}", t)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: "Could not install ${tool.name}"
        }
    }

    private fun findLatestAsset(tool: Tool): Asset? = findAsset(tool) { tool.assetPattern.containsMatchIn(it) }

    /**
     * The release record for an asset named [name], so an archive the user picked can be checked
     * the way the download of it would be; null when no release of [tool] publishes that name.
     */
    internal fun publishedAsset(tool: Tool, name: String): Asset? = findAsset(tool) { it == name }

    /** The first release carrying an asset whose name [matches], the name sorting the build out the
     *  way the download path sorts it when a release carries more than one ARM64 build. */
    private fun findAsset(tool: Tool, matches: (String) -> Boolean): Asset? {
        val releases = Downloader.downloadString(RELEASES.format(tool.repo)) ?: return null
        return try {
            val array = JSONArray(releases)
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
                val archive = entries.sortedBy { it.optString("name") }.firstOrNull { matches(it.optString("name")) } ?: continue
                val name = archive.optString("name")
                val stem = name.substringBefore(".tar")
                val checksum = entries.firstOrNull {
                    it.optString("name").startsWith(stem) && it.optString("name").contains("sha512", ignoreCase = true)
                }?.optString("browser_download_url")?.takeIf { it.startsWith("http") }
                return Asset(
                    release.optString("tag_name"), name,
                    archive.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue, checksum,
                    archive.optLong("size", 0L),
                    Hashes.githubSha256(archive.optString("digest")),
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "release metadata for ${tool.id}", e)
            null
        }
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
