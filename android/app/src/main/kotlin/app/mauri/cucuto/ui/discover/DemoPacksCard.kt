package app.mauri.cucuto.ui.discover

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.plainWords

/** The MIME types the pack file picker offers (pack and backup files are zips; some providers say octet-stream). */
internal val PackPickerTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * The system file picker for a pack zip, opening in Downloads, where the website puts the demo packs. No storage
 * permission: the user picks the file, as on the Packs screen.
 */
internal class OpenPackFromDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent =
        super
            .createIntent(context, input)
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(DOWNLOADS_AUTHORITY, DOWNLOADS_ROOT),
            )

    private companion object {
        const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
        const val DOWNLOADS_ROOT = "downloads"
    }
}

/** "Showing the demo packs only", with a close icon that shows every pack again. */
@Composable
internal fun DemoFocusChip(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(stringResource(R.string.discover_demo_focus)) },
        modifier = modifier,
        trailingIcon = {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.discover_demo_show_all),
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
    )
}

/**
 * The demo packs Home asked for: "Getting the demo packs…" while the index loads, then (only when it goes wrong) why,
 * with Try again and the file picker in Downloads as the fallback.
 */
@Composable
internal fun DemoPacksCard(
    demo: DemoPacksUi,
    onRetry: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = demo as? DemoPacksUi.Failed
    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (failed != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
    ) {
        if (failed == null) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingIndicator(Modifier.size(40.dp))
                Text(stringResource(R.string.discover_demo_finding), style = MaterialTheme.typography.titleMedium)
            }
            return@Card
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.discover_demo_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val reason =
                        when (val problem = failed.problem) {
                            is DemoProblem.Unreachable -> stringResource(plainWords(problem.error))
                            DemoProblem.NotListed -> stringResource(R.string.discover_demo_not_listed)
                        }
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.discover_demo_fallback), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPickFile, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.discover_demo_pick_file))
                }
                Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.discover_retry), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}
