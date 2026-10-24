package app.mauri.cucuto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * One pack filter chip of a picker (ADR 0063): a pack that has characters the picker can offer, and how many.
 *
 * @property count Characters of this pack the picker lists (with clips for messages, with ringtones for calls).
 */
@Immutable
data class PackChip(
    val packId: String,
    val name: String,
    val count: Int,
)

/**
 * The chips worth showing: one per pack with at least one eligible character, in [packs] order. Empty when fewer than
 * two packs qualify (one pack needs no filter).
 */
fun <P> packChips(
    packs: List<P>,
    id: (P) -> String,
    name: (P) -> String,
    count: (P) -> Int,
): List<PackChip> {
    val chips = packs.map { PackChip(id(it), name(it), count(it)) }.filter { it.count > 0 }
    return if (chips.size < 2) emptyList() else chips
}

/** [selected] if one of [chips] still has it (the pack may be gone), else null ("All"). */
fun selectedChip(
    chips: List<PackChip>,
    selected: String?,
): String? = selected?.takeIf { id -> chips.any { it.packId == id } }

/**
 * "All" and one chip per pack ([packChips]) with its count, in a row that scrolls sideways. [onSelect] gets the pack
 * ID, or null for "All".
 */
@Composable
fun PackFilterChips(
    chips: List<PackChip>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = chips.sumOf { it.count }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        item(key = "all") {
            val label = stringResource(R.string.pack_filter_count, stringResource(R.string.pack_filter_all), total)
            val spoken =
                pluralStringResource(R.plurals.pack_filter_a11y, total, stringResource(R.string.pack_filter_all), total)
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
        items(chips, key = { it.packId }) { chip ->
            val spoken = pluralStringResource(R.plurals.pack_filter_a11y, chip.count, chip.name, chip.count)
            FilterChip(
                selected = selected == chip.packId,
                onClick = { onSelect(if (selected == chip.packId) null else chip.packId) },
                label = { Text(stringResource(R.string.pack_filter_count, chip.name, chip.count)) },
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}
