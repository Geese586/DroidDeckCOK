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
 * The Updates page for this checkout.
 *
 * Upstream's page weighs the running build against the published catalog and installs what it finds.
 * That cannot work here: this APK is signed with a key of its own rather than DroidDeck's release
 * key, and Android refuses to install a build of one signer over another. Rather than keep a page
 * whose one button could only ever fail, this one says why and hands over the two addresses newer
 * builds are looked for at - DroidDeck's releases, and this fork's own repository - each one tap
 * away, and both printed for a device that has no browser to take them.
 *
 * Upstream's page, its state and the catalog behind them are all left untouched, so they come back
 * whole with an upstream merge; only the rail's one route points here instead (FrontEndContent).
 */
@Composable
internal fun UpdatesForkPage(s: FrontEndState, modifier: Modifier = Modifier) {
    val me = remember { AppUpdates.installed() }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val context = LocalContext.current
    // Set once a browser has been offered the address and none would take it: the line that is
    // normally just a fallback becomes the way there.
    var noBrowser by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.upd_title)) {
            // The build that is running, beside the title where it is always in view.
            Text(
                stringResource(R.string.upd_build, me.version, s.buildLabel), fontSize = 12.5.sp,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
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
                    PrimaryButton(
                        stringResource(R.string.upd_fork_open), main = true, icon = Icons.Outlined.OpenInNew,
                    ) {
                        val page = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        noBrowser = runCatching { context.startActivity(page) }.isFailure
                    }
                    // This fork's own repository: the one place where a build that installs over this
                    // copy (same signer) is published. Same fallback as the button above.
                    SecondaryButton(stringResource(R.string.upd_fork_project_open)) {
                        val page = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        noBrowser = runCatching { context.startActivity(page) }.isFailure
                    }
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
