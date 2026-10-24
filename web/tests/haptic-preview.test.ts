/// <reference types="bun" />
// Drawn haptic previews on the Packs page (#301, ADR 0069): what each `x-haptic` from the repository index becomes.
import { describe, expect, test } from 'bun:test';
import { PREVIEW_MIN_MS, hapticPreview } from '../src/lib/creator/haptics.ts';

describe('hapticPreview', () => {
	test('no x-haptic, or an empty pattern, draws nothing', () => {
		expect(hapticPreview(undefined, 96, 24)).toBeNull();
		expect(hapticPreview({ mode: 'custom', events: [] }, 96, 24)).toBeNull();
	});

	test('auto and none are named, not drawn', () => {
		expect(hapticPreview({ mode: 'auto' }, 96, 24)).toEqual({ mode: 'auto' });
		expect(hapticPreview({ mode: 'none' }, 96, 24)).toEqual({ mode: 'none' });
	});

	test('a custom pattern fills the width over its own length', () => {
		const preview = hapticPreview(
			{
				mode: 'custom',
				events: [
					{ at: 0, preset: 'CLICK', scale: 0.5 },
					{
						at: 100,
						envelope: [
							{ intensity: 1, sharpness: 0.5, ms: 200 },
							{ intensity: 0, sharpness: 0.5, ms: 300 }
						]
					}
				]
			},
			104,
			20
		);
		if (preview?.mode !== 'custom') throw new Error('expected a drawing');
		expect(preview.durationMs).toBe(600);
		// Insets of 4: the tap at 0 ms is a spike from the baseline to half height.
		expect(preview.presets).toEqual(['4,16 4,10']);
		// The envelope ends at the right edge: the pattern sets the window.
		expect(preview.envelopes).toEqual(['20,16 52,4 100,16']);
	});

	test('a single short tap does not stretch across the drawing', () => {
		const preview = hapticPreview({ mode: 'custom', events: [{ at: 0, preset: 'TICK' }] }, 100, 20);
		if (preview?.mode !== 'custom') throw new Error('expected a drawing');
		expect(preview.durationMs).toBeLessThan(PREVIEW_MIN_MS);
		expect(preview.presets).toEqual(['4,16 4,4']);
	});
});
