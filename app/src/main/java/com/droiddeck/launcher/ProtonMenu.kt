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
 * The optional ARM64 Protons (GE-Proton, proton-cachyos): the rows the page lists and an install or
 * removal in progress. The launcher screen keeps one and shows it.
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
     * all. The release publishing a file of the same name is looked for first, so the archive is
     * checked as the download of it would be; [askUnverified] takes over the ones that no release
     * vouches for - a custom build, or a release list that cannot be reached at all.
     */
    fun installProtonFromFile(id: String, archive: java.io.File) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        // The file name is also what says which build it is: the tree it unpacks to is named after
        // the release, so a cachyos archive dropped on the GE row would land in the wrong place.
        if (!tool.assetPattern.containsMatchIn(archive.name)) {
            android.widget.Toast.makeText(activity, activity.getString(R.string.proton_local_wrong_build, tool.name), android.widget.Toast.LENGTH_LONG).show()
            return
        }
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

    fun removeProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = activity.getString(R.string.main_removing_named, tool.name)
        protonPercent = -1
        Thread({
            ProtonExtras.remove(activity, tool)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
    }

    fun refreshProtons() {
        protonRows = ProtonExtras.tools.map { ProtonRow(it.id, it.name, ProtonExtras.installed(activity, it), ProtonExtras.queued(activity, it)) }
    }
}
