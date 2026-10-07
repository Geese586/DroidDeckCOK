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

/** Where a newer build is looked for, now that this copy cannot install one by itself. */
private const val RELEASES_URL = "https://github.com/Droid-Deck/DroidDeck/releases"

/**
 * The Updates page for this checkout.
 *
 * Upstream's page weighs the running build against the published catalog and installs what it finds.
 * That cannot work here: this APK is signed with a key of its own rather than DroidDeck's release
 * key, and Android refuses to install a build of one signer over another. Rather than keep a page
 * whose one button could only ever fail, this one says why and hands over the address newer builds
 * are looked for at - and installed from - by hand.
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
                Box(Modifier.padding(top = 16.dp)) {
                    PrimaryButton(
                        stringResource(R.string.upd_fork_open), main = true, icon = Icons.Outlined.OpenInNew,
                    ) {
                        val page = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        noBrowser = runCatching { context.startActivity(page) }.isFailure
                    }
                }
                Text(
                    if (noBrowser) stringResource(R.string.upd_fork_no_browser) else stringResource(R.string.upd_fork_manual),
                    fontSize = 12.5.sp, color = if (noBrowser) pal.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                // Printed whether or not the button worked: on a device with no browser, this line
                // is the address, not a footnote to it.
                Text(RELEASES_URL, fontSize = 12.5.sp, color = pal.signal, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
