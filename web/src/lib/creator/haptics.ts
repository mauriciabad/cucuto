// Custom haptics in the website's creator (#191). The pattern is the pack format's `custom` timeline
// (docs/pack-format.md#haptics); shared/pack-spec checks it (HapticPatternText, the same rules as the importer) and
// writes the "Copy for AI" prompt the app's haptic editor writes. Browsers cannot play rich haptics, so this file only
// draws the timeline and edits events; the app previews them.
import type { HapticEventJson } from '#lib/pack-spec/core.js';

/** Android recommends 50 ms or more after a preset (preset lengths depend on the device). */
export const PRESET_SLOT_MS = 50;

/** How long an event occupies the timeline: its envelope, or the preset slot. */
export function lengthOf(event: HapticEventJson): number {
	return event.envelope ? event.envelope.reduce((sum, p) => sum + p.ms, 0) : PRESET_SLOT_MS;
}

/** Where the timeline ends: the latest event end. */
export function endOf(events: HapticEventJson[]): number {
	return events.reduce((end, e) => Math.max(end, e.at + lengthOf(e)), 0);
}

/** A tap after the last event. */
export function newTap(events: HapticEventJson[]): HapticEventJson {
	return { at: nextStart(events), preset: 'CLICK', scale: 1 };
}

/** A swell after the last event: up in 80 ms, down in 160 ms. */
export function newSwell(events: HapticEventJson[]): HapticEventJson {
	return {
		at: nextStart(events),
		envelope: [
			{ intensity: 0.8, sharpness: 0.5, ms: 80 },
			{ intensity: 0, sharpness: 0.3, ms: 160 }
		]
	};
}

function nextStart(events: HapticEventJson[]): number {
	if (events.length === 0) return 0;
	return endOf(events) + (events.at(-1)?.envelope ? PRESET_SLOT_MS : 0);
}

/** Events sorted by start, as the format requires. */
export function sorted(events: HapticEventJson[]): HapticEventJson[] {
	return [...events].sort((a, b) => a.at - b.at);
}

/**
 * The timeline as SVG polylines in a `width` × `height` box over `durationMs`: each preset is a spike as high as its
 * scale, each envelope a line through its control points (intensity up).
 */
export function timelinePaths(
	events: HapticEventJson[],
	durationMs: number,
	width: number,
	height: number
): { presets: string[]; envelopes: string[] } {
	// A small inset, so a tap at 0 ms or at full strength is not cut by the edge.
	const pad = 4;
	const x = (ms: number) => pad + (Math.min(ms, durationMs) / durationMs) * (width - 2 * pad);
	const y = (level: number) => height - pad - level * (height - 2 * pad);
	const presets: string[] = [];
	const envelopes: string[] = [];
	for (const e of events) {
		if (e.envelope) {
			let t = e.at;
			const points = [`${x(t)},${y(0)}`];
			for (const p of e.envelope) {
				t += p.ms;
				points.push(`${x(t)},${y(p.intensity)}`);
			}
			envelopes.push(points.join(' '));
		} else {
			const level = e.scale ?? 1;
			presets.push(`${x(e.at)},${y(0)} ${x(e.at)},${y(level)}`);
		}
	}
	return { presets, envelopes };
}

/**
 * A simplified loudness envelope of a clip for the "Copy for AI" prompt, in the spirit of the app's automatic
 * analysis (docs/pack-format.md#haptics, `auto`): RMS loudness in 20 ms windows over the first 2 s, 0 to 100 against
 * the loudest window, with sharpness from the zero-crossing rate (a pitch proxy), keeping only rows where something
 * changes. Rows are `[ms, loudness, sharpness]`; null for silence.
 */
export function loudnessRows(
	samples: Float32Array,
	sampleRate: number
): [number, number, number][] | null {
	const window = Math.round(sampleRate * 0.02);
	const frames = Math.min(Math.floor(samples.length / window), 100);
	if (frames === 0) return null;
	const rms: number[] = [];
	const zcr: number[] = [];
	for (let f = 0; f < frames; f++) {
		let sum = 0;
		let crossings = 0;
		for (let i = f * window; i < (f + 1) * window; i++) {
			sum += samples[i] * samples[i];
			if (i > f * window && samples[i] >= 0 !== samples[i - 1] >= 0) crossings++;
		}
		rms.push(Math.sqrt(sum / window));
		zcr.push(crossings / window);
	}
	const loudest = Math.max(...rms);
	if (loudest < 1e-4) return null;
	const rows: [number, number, number][] = [];
	let last: [number, number, number] | null = null;
	for (let f = 0; f < frames; f++) {
		const level = Math.round((rms[f] / loudest) * 100);
		// 0.5 crossings per sample is the Nyquist limit; map 0..0.25 to 0..100.
		const sharp = Math.round(Math.min(1, zcr[f] / 0.25) * 100);
		const row: [number, number, number] = [f * 20, level, sharp];
		if (!last || Math.abs(level - last[1]) >= 10 || f === frames - 1) {
			rows.push(row);
			last = row;
		}
	}
	return rows.slice(0, 40);
}
