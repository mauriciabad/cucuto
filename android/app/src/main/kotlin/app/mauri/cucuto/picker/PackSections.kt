package app.mauri.cucuto.picker

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.settings.RememberedFolds
import app.mauri.cucuto.home.searchable
import kotlin.random.Random

/**
 * Which pack sections of a picker are unfolded (ADR 0072, #289). The first rule that applies wins:
 * 1. what the user did on this screen ([session]);
 * 2. the pack holding the contact's current choice is unfolded;
 * 3. what the user did last time in this picker ([remembered]);
 * 4. small packs ([SMALL_PACK] characters or fewer) are unfolded, large ones folded.
 */
@Immutable
data class PackFolds(
    val remembered: RememberedFolds = RememberedFolds(),
    val session: Map<String, Boolean> = emptyMap(),
) {
    fun isExpanded(
        packId: String,
        size: Int,
        currentPack: String?,
    ): Boolean =
        session[packId] ?: when (packId) {
            currentPack -> true
            in remembered.expanded -> true
            in remembered.folded -> false
            else -> size <= SMALL_PACK
        }

    /** [packId] unfolded or folded on this screen. */
    fun with(
        packId: String,
        expanded: Boolean,
    ): PackFolds = copy(session = session + (packId to expanded))

    companion object {
        /** Packs this small (two rows on a phone) start unfolded: folding them saves nothing. */
        const val SMALL_PACK = 12
    }
}

/**
 * One pack's section as a picker shows it.
 *
 * @property characters The characters the section shows when [expanded]: all of them, or the search matches.
 * @property total How many eligible characters the pack has (the header's count).
 * @property foldable Whether the header folds: not while searching, nor for a pack shown alone.
 */
@Immutable
data class PackSection<C>(
    val packId: String,
    val name: String,
    val characters: List<C>,
    val total: Int,
    val expanded: Boolean,
    val foldable: Boolean,
) {
    /** What the grid shows under the header. */
    val shown: List<C> get() = if (expanded) characters else emptyList()
}

/**
 * The sections a picker shows (ADR 0072): the packs the chip leaves ([chipPack], null for all), each folded or not by
 * [folds]. A non-blank [query] (case and accents ignored) shows every pack with a match, unfolded, with only the
 * characters whose name matches, or all of them when the pack's name does; packs without a match are left out.
 */
fun <P, C> packSections(
    packs: List<P>,
    id: (P) -> String,
    name: (P) -> String,
    characters: (P) -> List<C>,
    characterName: (C) -> String,
    chipPack: String?,
    query: String,
    folds: PackFolds,
    currentPack: String?,
): List<PackSection<C>> {
    val shown = chipPack?.let { chip -> packs.filter { id(it) == chip } } ?: packs
    val needle = query.searchable()
    if (needle.isNotEmpty()) {
        return shown.mapNotNull { pack ->
            val all = characters(pack)
            val matches =
                if (name(pack).searchable().contains(needle)) {
                    all
                } else {
                    all.filter { characterName(it).searchable().contains(needle) }
                }
            matches.takeIf { it.isNotEmpty() }?.let {
                PackSection(id(pack), name(pack), it, all.size, expanded = true, foldable = false)
            }
        }
    }
    val alone = shown.size == 1
    return shown.map { pack ->
        val all = characters(pack)
        PackSection(
            packId = id(pack),
            name = name(pack),
            characters = all,
            total = all.size,
            expanded = alone || folds.isExpanded(id(pack), all.size, currentPack),
            foldable = !alone,
        )
    }
}

/**
 * The grid index of the first character matching [match] among [sections], after [leading] full-width items (header,
 * search, chips). Each section is its header plus its [PackSection.shown] characters. Null when it is not shown.
 */
fun <C> List<PackSection<C>>.gridIndexOf(
    leading: Int,
    match: (C) -> Boolean,
): Int? {
    var index = leading
    for (section in this) {
        index += 1
        val at = section.shown.indexOfFirst(match)
        if (at >= 0) return index + at
        index += section.shown.size
    }
    return null
}

/**
 * A "Surprise me" roll (ADR 0072, #290): [pick] came from [packId]'s eligible characters, or from every pack for null.
 * [count] tells one roll from the next, so the screen scrolls to each new pick.
 */
@Immutable
data class SurpriseRoll<K>(
    val packId: String?,
    val pick: K,
    val count: Int,
)

/** How "Surprise me" picks (ADR 0072, #290); one place, so the owner's corrections on #290 change only this. */
object SurprisePick {
    /**
     * A random character from [pool] (the eligible ones: with clips for messages, with ringtones for calls), never
     * [previous] (the character selected now) unless it is the only one. Null for an empty pool.
     */
    fun <T> pick(
        pool: List<T>,
        previous: T?,
        random: Random = Random.Default,
    ): T? = pool.filter { it != previous }.ifEmpty { pool }.randomOrNull(random)
}
