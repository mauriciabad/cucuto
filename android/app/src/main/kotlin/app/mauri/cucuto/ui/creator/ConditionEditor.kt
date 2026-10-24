package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.creator.DraftClip
import app.mauri.cucuto.packspec.DateSpan
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.packspec.authoring.ConditionForm
import app.mauri.cucuto.packspec.authoring.DateForm
import app.mauri.cucuto.packspec.authoring.FormProblem
import app.mauri.cucuto.packspec.authoring.Moment
import app.mauri.cucuto.packspec.authoring.TimeOfDay
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.ringtone.rules.RuleText
import app.mauri.cucuto.ui.ringtone.rules.rememberRuleText
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * "When it plays" for one clip (#322, ADR 0049): which message (any, the first of the day, the N-th in a row), rare
 * ("about 1 in N"), and the time, seasons, weekdays and dates it may play on. The form is the shared [ConditionForm],
 * so the website's creator reads and writes clips alike; Save stays off while it has problems.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipConditionsPage(
    editor: ConditionEditorUi,
    onBack: () -> Unit,
    onChange: (ConditionForm) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rules = rememberRuleText()
    val problems = editor.problems
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.creator_clip_when)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
        bottomBar = { SaveBar(enabled = problems.isEmpty(), onSave = onSave) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (editor.clipLabel.isNotBlank()) {
                Text(editor.clipLabel, style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                clipSummary(editor.form.toWhen(clip = true), editor.form.oneIn, rules)
                    ?: stringResource(R.string.creator_when_summary_always),
                style = MaterialTheme.typography.titleMedium,
            )
            ConditionFields(form = editor.form, clip = true, onChange = onChange, rules = rules)
            ProblemList(problems)
        }
    }
}

/** The editor's fields: the clip-only moment and rarity first (when [clip]), then time, seasons, weekdays and dates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConditionFields(
    form: ConditionForm,
    clip: Boolean,
    onChange: (ConditionForm) -> Unit,
    rules: RuleText,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (clip) {
            EditorSection(stringResource(R.string.creator_when_moment), stringResource(momentHint(form.moment))) {
                ChoiceButtonGroup(
                    options = Moment.entries,
                    selected = form.moment,
                    onSelect = { onChange(form.copy(moment = it)) },
                    label = { stringResource(momentLabel(it)) },
                )
                if (form.moment == Moment.BURST) {
                    NumberField(
                        value = form.burst,
                        label = stringResource(R.string.creator_when_burst_number),
                        onValue = { onChange(form.copy(burst = it)) },
                    )
                }
            }
            EditorSection(stringResource(R.string.creator_when_rare), stringResource(R.string.creator_when_rare_hint)) {
                val rare = form.oneIn != null
                ListItem(
                    headlineContent = { Text(stringResource(R.string.creator_when_rare_switch)) },
                    trailingContent = { Switch(checked = rare, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier =
                        Modifier.toggleable(value = rare, role = Role.Switch) { on ->
                            onChange(form.copy(oneIn = if (on) ConditionForm.DEFAULT_ONE_IN else null))
                        },
                )
                form.oneIn?.let { oneIn ->
                    NumberField(
                        value = oneIn,
                        label = stringResource(R.string.creator_when_one_in),
                        onValue = { onChange(form.copy(oneIn = it)) },
                    )
                }
            }
        }

        EditorSection(stringResource(R.string.creator_when_time), stringResource(R.string.creator_when_time_hint)) {
            ChoiceButtonGroup(
                options = TimeOfDay.entries,
                selected = form.time,
                onSelect = { onChange(form.copy(time = it)) },
                label = { stringResource(timeLabel(it)) },
            )
            if (form.time == TimeOfDay.HOURS) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClockField(
                        value = form.from,
                        label = stringResource(R.string.creator_when_from),
                        onValue = { onChange(form.copy(from = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    ClockField(
                        value = form.to,
                        label = stringResource(R.string.creator_when_to),
                        onValue = { onChange(form.copy(to = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        EditorSection(
            stringResource(R.string.creator_when_seasons),
            stringResource(R.string.creator_when_seasons_hint),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConditionForm.SEASONS.forEach { season ->
                    Chip(
                        selected = season in form.seasons,
                        label = seasonLabel(season),
                        onClick = { onChange(form.copy(seasons = toggle(form.seasons, season))) },
                    )
                }
            }
        }

        EditorSection(
            stringResource(R.string.creator_when_weekdays),
            stringResource(R.string.creator_when_weekdays_hint),
        ) {
            val locale = Locale.getDefault()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConditionForm.WEEKDAYS.forEachIndexed { i, day ->
                    Chip(
                        selected = day in form.weekdays,
                        label = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, locale),
                        onClick = { onChange(form.copy(weekdays = toggle(form.weekdays, day))) },
                    )
                }
            }
        }

        EditorSection(stringResource(R.string.creator_when_dates), stringResource(R.string.creator_when_dates_hint)) {
            form.dates.forEachIndexed { i, date ->
                DateRow(
                    date = date,
                    rules = rules,
                    onChange = { changed ->
                        onChange(form.copy(dates = form.dates.mapIndexed { j, d -> if (j == i) changed else d }))
                    },
                    onRemove = { onChange(form.copy(dates = form.dates.filterIndexed { j, _ -> j != i })) },
                )
            }
            if (form.dates.size < ConditionForm.MAX_DATES) {
                OutlinedButton(
                    onClick = { onChange(form.copy(dates = form.dates + DateForm(""))) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(
                        stringResource(R.string.creator_when_date_add),
                        Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

/** One date: a day, and an optional last day for a range; the day in words once it reads as one. */
@Composable
private fun DateRow(
    date: DateForm,
    rules: RuleText,
    onChange: (DateForm) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = date.from,
                onValueChange = { onChange(date.copy(from = it.take(DATE_LENGTH))) },
                label = { Text(stringResource(R.string.creator_when_date_from)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = date.to.orEmpty(),
                onValueChange = { onChange(date.copy(to = it.take(DATE_LENGTH).ifEmpty { null })) },
                label = { Text(stringResource(R.string.creator_when_date_to)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.creator_when_date_remove))
            }
        }
        dateWords(date, rules)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "on 25 December", "from 24 December to 6 January", or null while the date does not read as one. */
private fun dateWords(
    date: DateForm,
    rules: RuleText,
): String? {
    val single = ConditionForm(dates = listOf(date))
    if (single.problems(clip = false).isNotEmpty() || DateSpan.parseMonthDay(date.from.trim()) == null) return null
    return rules.condition(single.toWhen(clip = false))
}

@Composable
internal fun EditorSection(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun Chip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            if (selected) {
                {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
    )
}

/** A whole number; an emptied field reads as 0 so the form shows its range problem. */
@Composable
internal fun NumberField(
    value: Int,
    label: String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { text -> onValue(text.filter(Char::isDigit).take(MAX_DIGITS).toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClockField(
    value: String,
    label: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(CLOCK_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = ConditionForm.clockMinutes(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** What still needs fixing, in plain words. */
@Composable
internal fun ProblemList(problems: List<FormProblem>) {
    problems.forEach { problem ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                problemText(problem),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The page's Save button, off while the form has problems. */
@Composable
internal fun SaveBar(
    enabled: Boolean,
    onSave: () -> Unit,
) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Button(
            onClick = onSave,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.creator_when_save), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

@Composable
private fun problemText(problem: FormProblem): String =
    when (problem.kind) {
        FormProblem.Kind.TIME_INVALID -> stringResource(R.string.creator_when_problem_time)
        FormProblem.Kind.TIME_RANGE_EMPTY -> stringResource(R.string.creator_when_problem_time_empty)
        FormProblem.Kind.DATE_INVALID -> stringResource(R.string.creator_when_problem_date, (problem.index ?: 0) + 1)
        FormProblem.Kind.TOO_MANY_DATES -> stringResource(R.string.creator_when_problem_dates)
        FormProblem.Kind.BURST_OUT_OF_RANGE -> stringResource(R.string.creator_when_problem_burst)
        FormProblem.Kind.ONE_IN_OUT_OF_RANGE -> stringResource(R.string.creator_when_problem_one_in)
        FormProblem.Kind.RINGTONE_DURATION_OUT_OF_RANGE -> stringResource(R.string.creator_ringtone_problem_duration)
        FormProblem.Kind.LOOP_INVALID -> stringResource(R.string.creator_ringtone_problem_loop)
        FormProblem.Kind.FADE_IN_OUT_OF_RANGE -> stringResource(R.string.creator_ringtone_problem_fade)
        FormProblem.Kind.WEIGHT_OUT_OF_RANGE -> stringResource(R.string.creator_ringtone_problem_weight)
    }

/**
 * A clip's condition in a few words ("First message of the day · by day · Rare: about 1 in 25 messages"), or null for
 * a clip that plays any time, in the rotation.
 */
@Composable
internal fun clipSummary(
    condition: When?,
    oneIn: Int?,
    rules: RuleText,
): String? {
    val parts =
        buildList {
            when {
                condition?.firstOfDay == true -> add(stringResource(R.string.contact_clip_first_of_day))
                condition?.burst != null -> add(stringResource(R.string.contact_clip_burst, condition.burst!!))
            }
            rules.condition(condition?.copy(firstOfDay = null, burst = null)?.takeUnless { it == When() })?.let(::add)
            oneIn?.let { add(stringResource(R.string.contact_clip_rare, it)) }
        }
    return parts.joinToString(SEPARATOR).replaceFirstChar { it.titlecase() }.ifEmpty { null }
}

/** [clipSummary] for a draft clip. */
@Composable
internal fun clipSummary(clip: DraftClip): String? = clipSummary(clip.`when`, clip.oneIn, rememberRuleText())

private fun toggle(
    list: List<String>,
    item: String,
): List<String> = if (item in list) list - item else list + item

@Composable
private fun seasonLabel(season: String): String =
    stringResource(
        when (season) {
            "spring" -> R.string.ringtone_rules_spring
            "summer" -> R.string.ringtone_rules_summer
            "autumn" -> R.string.ringtone_rules_autumn
            else -> R.string.ringtone_rules_winter
        },
    ).replaceFirstChar { it.titlecase() }

private fun momentLabel(moment: Moment): Int =
    when (moment) {
        Moment.ANY -> R.string.creator_when_moment_any
        Moment.FIRST_OF_DAY -> R.string.creator_when_moment_first
        Moment.BURST -> R.string.creator_when_moment_burst
    }

private fun momentHint(moment: Moment): Int =
    when (moment) {
        Moment.ANY -> R.string.creator_when_moment_any_hint
        Moment.FIRST_OF_DAY -> R.string.creator_when_moment_first_hint
        Moment.BURST -> R.string.creator_when_moment_burst_hint
    }

private fun timeLabel(time: TimeOfDay): Int =
    when (time) {
        TimeOfDay.ANY -> R.string.ringtone_rules_choice_any
        TimeOfDay.DAY -> R.string.ringtone_rules_choice_day
        TimeOfDay.NIGHT -> R.string.ringtone_rules_choice_night
        TimeOfDay.HOURS -> R.string.creator_when_time_hours
    }

private const val SEPARATOR = " · "
private const val DATE_LENGTH = 5
private const val CLOCK_LENGTH = 5
private const val MAX_DIGITS = 5
