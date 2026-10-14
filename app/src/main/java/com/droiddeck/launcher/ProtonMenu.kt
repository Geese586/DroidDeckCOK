package com.droiddeck.launcher

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState

/**
 * The optional ARM64 Protons (GE-Proton, proton-cachyos, DroidDeck-Proton): the rows the page lists,
 * an install or removal in progress, and whether a newer release is out. The launcher screen keeps
 * one and shows it.
 */
internal class ProtonMenu(
    private val activity: android.app.Activity,
    private val ui: android.os.Handler,
    /** Asks the user whether to install an archive no release publishes a checksum for; the answer
     *  comes back through [installProtonArchive]. */
    private val askUnverified: (String, java.io.File) -> Unit,
) {
    var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    var protonBusyId by mutableStateOf<String?>(null)
    var protonStage by mutableStateOf<String?>(null)
    var protonPercent by mutableIntStateOf(-1)
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    fun installProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = ProtonExtras.install(activity, tool) { label, value ->
                ui.post { protonStage = label; protonPercent = value }
            }
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "install-proton-$id").start()
    }

    /**
     * Installs a build from an archive the user picked, for a network that cannot reach GitHub at
     * all, or for a build no repo the app knows about publishes. The release publishing a file of
     * the same name is looked for first, so the archive is checked as the download of it would be;
     * [askUnverified] takes over the rest - a custom build whose name says nothing, or a release
     * list that cannot be reached at all. Nothing is refused outright any more: a name that does not
     * look like the row's own build is a question, not a wall, since the tree unpacks to whatever
     * the tarball calls itself either way.
     */
    fun installProtonFromFile(id: String, archive: java.io.File) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = "Checking the release…"
        protonPercent = -1
        Thread({
            val asset = runCatching { ProtonExtras.publishedAsset(tool, archive.name) }.getOrNull()
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                if (asset == null) askUnverified(id, archive) else installProtonArchive(id, archive, asset)
            }
        }, "proton-file-check-$id").start()
    }

    /** As [installProtonFromFile] above, once the checksum - or the user's answer - is in. */
    fun installProtonArchive(id: String, archive: java.io.File, asset: ProtonExtras.Asset?) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonStage = "Preparing…"
        protonPercent = -1
        Thread({
            val problem = ProtonExtras.installFromFile(activity, tool, archive, asset) { label, value ->
                ui.post { protonStage = label; protonPercent = value }
            }
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "install-proton-file-$id").start()
    }

    /** Removes the build in [dir], or with [older] every build of it but the newest. */
    fun removeProton(id: String, dir: String? = null, older: Boolean = false) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = activity.getString(R.string.main_removing_named, dir ?: tool.name)
        protonPercent = -1
        Thread({
            if (older) ProtonExtras.removeOlder(activity, tool) else ProtonExtras.remove(activity, tool, dir)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
    }

    fun refreshProtons() {
        protonRows = ProtonExtras.tools.map {
            val all = ProtonExtras.installedAll(activity, it)
            ProtonRow(it.id, it.name, all.lastOrNull(), ProtonExtras.queued(activity, it), all, ProtonExtras.update(activity, it))
        }
    }

    /** Looks for newer releases, at most every few hours (see ProtonExtras.checkLatest); quiet offline. */
    fun checkUpdates(force: Boolean = false) {
        if (!checking.compareAndSet(false, true)) return
        Thread({
            val asked = try { ProtonExtras.checkLatest(activity, force) } catch (_: Throwable) { false } finally { checking.set(false) }
            if (asked) ui.post { refreshProtons() }
        }, "proton-updates").start()
    }
}
