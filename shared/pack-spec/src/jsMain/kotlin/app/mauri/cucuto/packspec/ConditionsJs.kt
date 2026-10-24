@file:OptIn(ExperimentalJsExport::class)

package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.ConditionForm
import app.mauri.cucuto.packspec.authoring.FormProblem
import app.mauri.cucuto.packspec.authoring.RingtoneForm

// The browser API of the creators' condition and ringtone editors (the same Kotlin as the app's creator, so both read
// and write `when`, `oneIn` and ringtone entries alike). Forms travel as JSON (`ConditionForm` and `RingtoneForm` in
// web/src/lib/pack-spec/core.ts); problems as `KIND` or `KIND:<date index>`.

/** What [writeCondition] made: the clip's or ringtone's `when` as JSON (null for none), the clip's `oneIn`, problems. */
@JsExport
class ConditionWriteJs internal constructor(
    val whenJson: String?,
    val oneIn: Int?,
    val problems: Array<String>,
)

/** What [writeRingtone] made: the ringtone entry as JSON, and problems. */
@JsExport
class RingtoneWriteJs internal constructor(
    val entryJson: String,
    val problems: Array<String>,
)

/** The form for a clip's or ringtone's `when` ([whenJson], null for none) and a clip's [oneIn], as JSON. */
@JsExport
fun conditionForm(
    whenJson: String?,
    oneIn: Int?,
): String {
    val condition = whenJson?.let { draftJson.decodeFromString(When.serializer(), it) }
    return outJson.encodeToString(ConditionForm.serializer(), ConditionForm.of(condition, oneIn))
}

/** The `when` and `oneIn` a form ([formJson]) stands for; [clip] keeps the clip-only fields. */
@JsExport
fun writeCondition(
    formJson: String,
    clip: Boolean,
): ConditionWriteJs {
    val form = draftJson.decodeFromString(ConditionForm.serializer(), formJson)
    val w = form.toWhen(clip)
    return ConditionWriteJs(
        whenJson = w?.let { SpecJson.encodeToString(When.serializer(), it) },
        oneIn = if (clip) form.oneIn else null,
        problems = codes(form.problems(clip)),
    )
}

/** The form for a pack's ringtone entry ([entryJson]), as JSON. */
@JsExport
fun ringtoneForm(entryJson: String): String {
    val entry = draftJson.decodeFromString(Ringtone.serializer(), entryJson)
    return outJson.encodeToString(RingtoneForm.serializer(), RingtoneForm.of(entry))
}

/** The ringtone entry for [file] that a form ([formJson]) stands for; [durationMs] (NaN when unknown) checks the loop. */
@JsExport
fun writeRingtone(
    formJson: String,
    file: String,
    durationMs: Double,
): RingtoneWriteJs {
    val form = draftJson.decodeFromString(RingtoneForm.serializer(), formJson)
    val duration = durationMs.takeUnless { it.isNaN() }?.toLong()
    return RingtoneWriteJs(
        entryJson = SpecJson.encodeToString(Ringtone.serializer(), form.toEntry(file)),
        problems = codes(form.problems(duration)),
    )
}

/** Tags typed as text, as the format's tags ([Authoring.tags]). */
@JsExport
fun parseTags(text: String): Array<String> = Authoring.tags(text).toTypedArray()

/** Whether [url] is an absolute https URL ([Authoring.isHomepage]). */
@JsExport
fun isHomepage(url: String): Boolean = Authoring.isHomepage(url)

private fun codes(problems: List<FormProblem>): Array<String> =
    problems.map { p -> p.index?.let { "${p.kind.name}:$it" } ?: p.kind.name }.toTypedArray()
