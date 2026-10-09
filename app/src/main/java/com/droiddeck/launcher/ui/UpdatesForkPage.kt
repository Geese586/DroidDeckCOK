package com.droiddeck.launcher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.update.AppUpdates

/** Where the official build's newer releases are published. */
private const val RELEASES_URL = "https://github.com/Droid-Deck/DroidDeck/releases"

/**
 * This build's own repository. The page hands over both addresses because the two answer different
 * questions: DroidDeck's releases are where the official builds are, and this one is where a build
 * that actually installs over this copy - same signer - is published.
 */
private const val PROJECT_URL = "https://github.com/Geese586/DroidDeckCOK"

/**
 * The official build's newest stable release, when it is ahead of the version this copy was made
 * from - what this fork means by "there is an update".
 *
 * Upstream's [AppUpdates.hasUpdate] cannot answer that, and is not wrong: it counts only a release
 * the running build could install over itself, and an APK signed with this checkout's own key can
 * never take an official one (see [UpdatesForkPage]). What somebody running this fork wants to
 * know is whether DroidDeck has moved on past the version the fork was built on, and that is a
 * plain version comparison against the same catalog upstream's page reads.
 *
 * Nothing when the catalog is missing or the official build is not ahead, so the rail and the page
 * both read "no notice" from a null.
 */
internal fun officialUpdateAvailable(catalog: AppUpdates.Catalog?): AppUpdates.Release? {
    val release = catalog?.stable ?: return null
    val version = release.version ?: release.tag
    return if (AppUpdates.compareVersions(version, AppUpdates.installed().version) > 0) release else null
}

/**
 * The Updates page for this checkout.
 *
 * Upstream's page weighs the running build against the published catalog and installs what it finds.
 * That cannot work here: this APK is signed with a key of its own rather than DroidDeck's release
 * key, and Android refuses to install a build of one signer over another. Rather than keep a page
 * whose one button could only ever fail, this one says why and hands over the two addresses newer
 * builds are looked for at - DroidDeck's releases, and this fork's own repository - each one tap
 * away, and both printed for a device that has no browser to take them.
 *
 * It reads the same catalog upstream's page reads, through the same state, so the official build's
 * version and the moment it was last read are already here: the card at the top says which version
 * DroidDeck is on and, once its sources move past the version this copy was built from, that there
 * is a new one to pull in. The rail's Updates item badges on the same signal.
 *
 * Upstream's page, its state and the catalog behind them are all left untouched, so they come back
 * whole with an upstream merge; only the rail's one route points here instead (FrontEndContent).
 */
@Composable
internal fun UpdatesForkPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier = Modifier) {
    val me = remember { AppUpdates.installed() }
    val u = s.updates
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val context = LocalContext.current
    // Set once a browser has been offered an address and none would take it: the lines that are
    // normally fallbacks become the way there.
    var noBrowser by remember { mutableStateOf(false) }
    val open: (String) -> Unit = { url ->
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        noBrowser = runCatching { context.startActivity(page) }.isFailure
    }
    // The official build's version, and the release when it is ahead of this copy's.
    val official = officialUpdateAvailable(u.catalog)
    val officialNow = u.catalog?.stable?.let { it.version ?: it.tag }
    val behindVersion = official?.let { it.version ?: it.tag }
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.upd_title)) {
            // The build that is running, beside the title where it is always in view.
            Text(
                stringResource(R.string.upd_build, me.version, s.buildLabel), fontSize = 12.5.sp,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            // Same as upstream's page: when the catalog was last read, beside the title, and the
            // button that reads it again - the way to get an official version onto this page on a
            // device that has just come back onto a network.
            val checked = u.catalog?.checkedAt?.takeIf { it > 0 }
            if (!LocalNarrowPane.current) Text(
                if (u.checking) stringResource(R.string.common_checking)
                else if (checked != null) stringResource(R.string.upd_checked, ago(checked).replaceFirstChar { it.lowercase() })
                else stringResource(R.string.upd_not_checked),
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ToolIcon(
                Icons.Outlined.Refresh, stringResource(R.string.store_check_updates),
                busy = u.checking, enabled = !u.checking && u.stage == null, onClick = a.updates.onCheck,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // What DroidDeck's own build is on. It is the one thing on this page that moves while
            // this copy sits still, so it comes first: the notice the rail badges is this card.
            Column(
                modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface)
                    .border(1.dp, pal.line, Shape16).padding(18.dp),
            ) {
                val tint = when {
                    official != null -> AttentionAmber
                    officialNow != null -> pal.good
                    else -> colors.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
                    Text(
                        if (official != null) stringResource(R.string.upd_fork_ver_new) else stringResource(R.string.upd_fork_ver_status),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint,
                    )
                    if (official != null) Text(
                        stringResource(R.string.upd_dot_ago, ago(official.publishedAt)),
                        fontSize = 13.sp, color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    if (officialNow != null) stringResource(R.string.upd_fork_ver_headline, officialNow)
                    else stringResource(R.string.upd_fork_ver_unknown_headline),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    when {
                        behindVersion != null -> stringResource(R.string.upd_fork_ver_behind, me.version, behindVersion)
                        officialNow != null -> stringResource(R.string.upd_fork_ver_current, me.version)
                        else -> stringResource(R.string.upd_fork_ver_unknown)
                    },
                    fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
                // The check's own failure, said where the version above would have come from.
                if (u.error != null) Text(
                    u.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp),
                )
                // The one action this card has, and only while it has something to say: the card
                // below carries the button in every other state.
                if (official != null) Box(Modifier.padding(top = 14.dp)) {
                    PrimaryButton(stringResource(R.string.upd_fork_open), main = true, icon = Icons.Outlined.OpenInNew) {
                        open(RELEASES_URL)
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface)
                    .border(1.dp, pal.line, Shape16).padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(AttentionAmber))
                    Text(
                        stringResource(R.string.upd_fork_status), fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = AttentionAmber,
                    )
                }
                Text(
                    stringResource(R.string.upd_fork_headline), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = colors.onBackground, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.upd_fork_detail), fontSize = 14.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    stringResource(R.string.upd_fork_own_builds), fontSize = 13.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    // Not while the card above offers it: one page, one way to the releases.
                    if (official == null) PrimaryButton(
                        stringResource(R.string.upd_fork_open), main = true, icon = Icons.Outlined.OpenInNew,
                    ) { open(RELEASES_URL) }
                    // This fork's own repository: the one place where a build that installs over this
                    // copy (same signer) is published.
                    SecondaryButton(stringResource(R.string.upd_fork_project_open)) { open(PROJECT_URL) }
                }
                Text(
                    if (noBrowser) stringResource(R.string.upd_fork_no_browser) else stringResource(R.string.upd_fork_manual),
                    fontSize = 12.5.sp, color = if (noBrowser) pal.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                // Printed whether or not the buttons worked: on a device with no browser, these lines
                // are the addresses, not footnotes to them.
                Text(RELEASES_URL, fontSize = 12.5.sp, color = pal.signal, modifier = Modifier.padding(top = 2.dp))
                Text(
                    stringResource(R.string.upd_fork_project), fontSize = 12.5.sp,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                Text(PROJECT_URL, fontSize = 12.5.sp, color = pal.signal, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** As upstream's page writes it: "5 minutes ago", from the moment the catalog was last read. */
@Composable
private fun ago(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.upd_while_ago)
    if (System.currentTimeMillis() - millis in 0 until 60_000) return stringResource(R.string.upd_just_now)
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
