// Short summaries of clip and ringtone conditions for the Create page's lists ("At night · Rare: about 1 in 25"),
// read straight from the `when` JSON of pack.json (docs/pack-format.md#ringtones-and-variants, #special-clips). The
// editors themselves read and write conditions with shared/pack-spec's ConditionForm (the app's creator does the same).
import { fill } from '#lib/i18n/index.js';
import type { FormProblem } from '#lib/pack-spec/core.js';

/** The strings a summary is made of (`create.summary` in the site's messages). */
export interface SummaryMessages {
	day: string;
	night: string;
	hours: string;
	firstOfDay: string;
	burst: string;
	rare: string;
	dateRange: string;
	always: string;
	seasons: Record<string, string>;
}

const SEASONS = ['spring', 'summer', 'autumn', 'winter'];
const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
/** A leap year whose 1 January is a Monday, so weekday and day names (29 February too) can be formatted. */
const YEAR = 2024;

const names = (value: unknown): string[] =>
	typeof value === 'string'
		? [value]
		: Array.isArray(value)
			? value.filter((v): v is string => typeof v === 'string')
			: [];

/** `MM-DD` as "25 Dec" in `locale`; the text itself when it is not a day. */
function day(text: string, locale: string): string {
	const match = /^(\d{2})-(\d{2})$/.exec(text);
	if (!match) return text;
	const date = new Date(YEAR, Number(match[1]) - 1, Number(match[2]));
	return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(date);
}

/**
 * The summary of a clip's or ringtone's condition, `when` as in pack.json (null for none) and a clip's `oneIn`:
 * "First message of the day · 05:00 to 12:00", "At night · Saturday and Sunday · Rare: about 1 in 25". Empty when
 * there is no condition.
 */
export function conditionSummary(
	when: Record<string, unknown> | null | undefined,
	oneIn: number | null | undefined,
	t: SummaryMessages,
	locale: string
): string {
	const list = new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' });
	const parts: string[] = [];
	if (when) {
		if (when.firstOfDay === true) parts.push(t.firstOfDay);
		if (typeof when.burst === 'number') parts.push(fill(t.burst, { n: when.burst }));
		const time = when.time;
		if (time === 'day') parts.push(t.day);
		else if (time === 'night') parts.push(t.night);
		else if (time && typeof time === 'object') {
			const { from, to } = time as { from?: unknown; to?: unknown };
			parts.push(fill(t.hours, { from: String(from), to: String(to) }));
		}
		const seasons = names(when.season).filter((s) => SEASONS.includes(s));
		if (seasons.length > 0) parts.push(list.format(seasons.map((s) => t.seasons[s] ?? s)));
		const weekdays = names(when.weekday).filter((d) => WEEKDAYS.includes(d));
		if (weekdays.length > 0) {
			const format = new Intl.DateTimeFormat(locale, { weekday: 'long' });
			parts.push(
				list.format(weekdays.map((d) => format.format(new Date(YEAR, 0, 1 + WEEKDAYS.indexOf(d)))))
			);
		}
		const spans = Array.isArray(when.date) ? when.date : when.date ? [when.date] : [];
		const dates = spans.map((span) => {
			if (typeof span === 'string') return day(span, locale);
			const { from, to } = span as { from?: unknown; to?: unknown };
			return fill(t.dateRange, { from: day(String(from), locale), to: day(String(to), locale) });
		});
		if (dates.length > 0) parts.push(list.format(dates));
	}
	if (typeof oneIn === 'number') parts.push(fill(t.rare, { n: oneIn.toLocaleString(locale) }));
	return parts.join(' · ');
}

/** A form problem in plain words, from `create.conditions.problems`; `{n}` is the date's number. */
export function problemText(problem: FormProblem, text: Record<string, string>): string {
	return fill(text[problem.kind] ?? problem.kind, { n: (problem.index ?? 0) + 1 });
}
