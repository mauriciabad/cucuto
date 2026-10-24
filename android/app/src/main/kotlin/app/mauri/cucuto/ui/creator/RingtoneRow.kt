package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.creator.DraftRingtone
import app.mauri.cucuto.packspec.authoring.LoopMode
import app.mauri.cucuto.packspec.authoring.RingtoneForm
import app.mauri.cucuto.ui.ringtone.rules.rememberRuleText

/** One ringtone version on the character page: play, its name, when it rings and how it loops; tap to edit. */
@Composable
internal fun RingtoneRow(
    ringtone: DraftRingtone,
    playing: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onAddVersion: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val form = RingtoneForm.of(ringtone.entry)
    val rules = rememberRuleText()
    val name = form.label.ifBlank { stringResource(R.string.creator_ringtone_unnamed) }
    Row(
        modifier.fillMaxWidth().clickable(
            onClickLabel = stringResource(R.string.creator_ringtone_edit),
            onClick = onEdit,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            if (playing) {
                Icon(
                    painterResource(R.drawable.ic_creator_stop),
                    contentDescription = stringResource(R.string.creator_ringtone_stop),
                )
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.creator_ringtone_play))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val condition =
                rules.condition(form.condition.toWhen(clip = false))?.let {
                    stringResource(R.string.ringtone_rules_rings, it)
                } ?: stringResource(R.string.ringtone_rules_always_rings)
            val loop =
                when (form.loop) {
                    LoopMode.WHOLE -> stringResource(R.string.creator_ringtone_loop_whole)
                    LoopMode.ONCE -> stringResource(R.string.creator_ringtone_loop_once)
                    LoopMode.SECTION -> stringResource(R.string.creator_ringtone_loop_section)
                }
            Text(
                "$condition · $loop",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.creator_ringtone_more, name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.creator_ringtone_edit)) },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.creator_ringtone_add_version)) },
                    onClick = {
                        menu = false
                        onAddVersion()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.creator_ringtone_remove)) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}
