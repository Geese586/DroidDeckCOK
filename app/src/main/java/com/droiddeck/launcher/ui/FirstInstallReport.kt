package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R

/**
 * Where the one-click local install stopped, kept on screen instead of shown for four seconds.
 *
 * Only a run that did not finish gets here - a clean one says so in a Toast - so every line is
 * about something that went wrong, and the first thing a reader needs is *which* step: its number,
 * its name and the message it failed with. The run takes minutes, and this is the only thing that
 * says what to do next, so it is a dialog that stays until it is read rather than a Toast that is
 * gone before a user who left the screen comes back.
 *
 * It also carries the way to the log file, which holds what the lines cannot: every step's timing,
 * the stack trace of anything that threw, and where a run that stopped was last heard from. That log
 * is written whatever happens, and Setup's Diagnostics can fetch it when there is no dialog at all -
 * after a run that went fine, or one the system killed outright.
 */
@Composable
fun FirstInstallReportDialog(text: String, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.first_local_report_title)) },
        text = {
            Column(
                // Room for a line per step and enough of them to read without scrolling twice; the
                // column scrolls so eleven steps on a short screen are all still reachable.
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave() }) { Text(stringResource(R.string.first_local_logs_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/**
 * The wrong-folder notice of the one-click install: the folder the user picked holds nothing this
 * can install. It carries the addresses the offline package is downloaded from, so it stays up as
 * a dialog until dismissed - a Toast had neither the room for two links and their codes nor the
 * patience to be copied from. No title on purpose: the text itself says what went wrong.
 */
@Composable
fun FirstLocalHintDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
