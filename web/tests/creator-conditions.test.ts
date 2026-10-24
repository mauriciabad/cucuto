/// <reference types="bun" />
// Clip conditions, special clips and ringtones on the Create page (#322) under Bun: the condition summaries, and drafts
// with conditions and ringtone versions laid out by the shared assembler and checked by the same validator as the app.
// tests/create.e2e.ts drives the dialogs in a real browser.
import { describe, expect, test } from 'bun:test';
import { unzipSync } from 'fflate';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { packSpec, type PackSpecModule } from '../src/lib/pack-spec/core.ts';
import { conditionSummary, problemText } from '../src/lib/creator/conditions.ts';
import { encodeWav } from '../src/lib/creator/encode.ts';
import {
	buildPack,
	draftInput,
	emptyCharacter,
	emptyPack,
	type ClipState,
	type KeptFile,
	type PackState
} from '../src/lib/creator/pack.ts';
import { en } from '../src/lib/i18n/en.ts';
import { es } from '../src/lib/i18n/es.ts';

const fixtures = join(import.meta.dir, '..', '..', 'spec', 'fixtures', 'valid');
const generated = join(
	import.meta.dir,
	'..',
	'src',
	'lib',
	'pack-spec',
	'generated',
	'pack-spec.mjs'
);
if (!existsSync(generated)) {
	throw new Error(
		`${generated} is missing: run ./gradlew syncWebPackSpec in shared/pack-spec first`
	);
}
const spec = packSpec((await import(generated)) as PackSpecModule);
const { authoring } = spec;

const sprite = readFileSync(join(fixtures, 'minimal-pack', 'characters', 'beep', 'sprite.png'));

/** A tone of `seconds` as WAV, 48 kHz mono (synthesized, CC0). */
function tone(seconds: number): Uint8Array {
	const samples = new Float32Array(Math.round(48_000 * seconds));
	for (let i = 0; i < samples.length; i++)
		samples[i] = 0.3 * Math.sin((2 * Math.PI * 440 * i) / 48_000);
	return encodeWav(samples, 48_000);
}

function clip(label: string, extra: Partial<ClipState> = {}): ClipState {
	const bytes = tone(0.5);
	return {
		uid: `clip-${label}`,
		label,
		weight: 1,
		encoded: { bytes, extension: 'wav', type: 'audio/wav' },
		samples: null,
		durationMs: 500,
		source: null,
		edit: { startMs: 0, endMs: 500, trimSilence: false, normalize: false },
		when: null,
		oneIn: null,
		...extra
	};
}

function draft(): PackState {
	const pack = emptyPack('abc123', 'CC0-1.0');
	pack.name = 'Conditions';
	pack.author = 'Tests';
	return pack;
}

const image = { bytes: new Uint8Array(sprite), extension: 'png', type: 'image/png', preview: '' };

describe('condition summaries', () => {
	test('read every kind of condition in plain words', () => {
		const s = en.create.summary;
		expect(conditionSummary(null, null, s, 'en')).toBe('');
		expect(conditionSummary({ time: 'night' }, 25, s, 'en')).toBe('At night · Rare: about 1 in 25');
		expect(
			conditionSummary({ firstOfDay: true, time: { from: '05:00', to: '12:00' } }, null, s, 'en')
		).toBe('First message of the day · 05:00 to 12:00');
		expect(
			conditionSummary(
				{ burst: 3, season: ['autumn', 'winter'], weekday: ['sat', 'sun'] },
				null,
				s,
				'en'
			)
		).toBe('Message 3 in a row · Autumn and Winter · Saturday and Sunday');
		expect(
			conditionSummary({ date: ['12-25', { from: '12-24', to: '01-06' }] }, null, s, 'en')
		).toBe('Dec 25 and Dec 24 to Jan 6');
	});

	test('in Spanish', () => {
		expect(conditionSummary({ time: 'day', season: 'summer' }, 1000, es.create.summary, 'es')).toBe(
			'De día · Verano · Raro: más o menos 1 de cada 1000'
		);
		expect(conditionSummary({ date: '12-25' }, null, es.create.summary, 'es')).toBe('25 dic');
	});

	test('forms from Kotlin carry every optional field as null', () => {
		const form = authoring.conditionForm({ date: '12-25' }, null);
		expect(form.oneIn).toBeNull();
		expect(form.dates).toEqual([{ from: '12-25', to: null }]);
		const ringtone = authoring.ringtoneForm({ file: 'a.ogg' });
		expect(ringtone.loopEndMs).toBeNull();
		expect(ringtone.condition.oneIn).toBeNull();
	});

	test('problems name the date', () => {
		expect(problemText({ kind: 'DATE_INVALID', index: 1 }, en.create.conditions.problems)).toBe(
			'Date 2 is not a day of the year. Write it as month-day, for example 12-25.'
		);
	});
});

describe('drafts with conditions and ringtones', () => {
	test('clip conditions and rarity go into pack.json', () => {
		const pack = draft();
		const frog = emptyCharacter();
		frog.name = 'Frog';
		frog.sprite = image;
		const written = authoring.writeCondition(
			{ ...authoring.conditionForm(null, null), time: 'NIGHT', oneIn: 25 },
			true
		);
		frog.clips = [clip('Ribbit'), clip('Croak', { when: written.when, oneIn: written.oneIn })];
		pack.characters = [frog];
		const outcome = buildPack(spec, pack);
		if (outcome.kind !== 'built') throw new Error(JSON.stringify(outcome.problems));
		expect(outcome.pack.result.valid).toBe(true);
		const manifest = JSON.parse(outcome.pack.manifestJson);
		expect(manifest.characters[0].clips[1]).toEqual({
			file: 'characters/frog/clips/croak.wav',
			label: 'Croak',
			when: { time: 'night' },
			oneIn: 25
		});
	});

	test('a ringtone-only character with two versions of one song builds and validates', () => {
		const pack = draft();
		pack.homepage = 'https://example.com/songs';
		pack.tags = authoring.parseTags('Songs, Calls');
		const song = emptyCharacter();
		song.name = 'Song';
		song.sprite = image;
		song.description = 'A tune for calls.';
		const file: KeptFile = { id: 'file-song', bytes: tone(6), extension: 'wav', durationMs: 6000 };
		const day = authoring.writeRingtone(
			{ ...authoring.ringtoneForm({ file: 'song.wav' }), label: 'Day', loop: 'ONCE' },
			'song.wav',
			6000
		);
		const night = authoring.writeRingtone(
			{
				...authoring.ringtoneForm({ file: 'song.wav' }),
				label: 'Night',
				condition: { ...authoring.conditionForm(null, null), time: 'NIGHT' },
				loop: 'SECTION',
				loopStartMs: 1000,
				loopEndMs: 5000,
				fadeInMs: 500
			},
			'song.wav',
			6000
		);
		expect(day.problems).toEqual([]);
		expect(night.problems).toEqual([]);
		song.ringtones = [
			{ uid: 'r1', file, entry: day.entry },
			{ uid: 'r2', file, entry: night.entry }
		];
		pack.characters = [song];

		// Both versions share one file.
		const input = draftInput(pack);
		expect(input.characters[0].clips).toEqual([]);
		expect(input.characters[0].ringtones?.map((r) => r.file.key)).toEqual([
			`${song.uid}/file-song`,
			`${song.uid}/file-song`
		]);

		const outcome = buildPack(spec, pack);
		if (outcome.kind !== 'built') throw new Error(JSON.stringify(outcome.problems));
		expect(outcome.pack.result.errorCodes).toEqual([]);
		const manifest = JSON.parse(outcome.pack.manifestJson);
		expect(manifest.homepage).toBe('https://example.com/songs');
		expect(manifest.tags).toEqual(['songs', 'calls']);
		expect(manifest.characters[0].description).toBe('A tune for calls.');
		expect(manifest.characters[0].clips).toBeUndefined();
		expect(manifest.characters[0].ringtones).toEqual([
			{ file: 'characters/song/ringtones/day.wav', label: 'Day', loop: false },
			{
				file: 'characters/song/ringtones/day.wav',
				label: 'Night',
				when: { time: 'night' },
				loop: { startMs: 1000, endMs: 5000 },
				fadeInMs: 500
			}
		]);
		const entries = Object.keys(unzipSync(outcome.pack.bytes));
		expect(entries.filter((e) => e.includes('/ringtones/'))).toEqual([
			'characters/song/ringtones/day.wav'
		]);
	});

	test('a character with neither clips nor ringtones still needs one', () => {
		const pack = draft();
		const empty = emptyCharacter();
		empty.name = 'Empty';
		empty.sprite = image;
		pack.characters = [empty];
		const outcome = buildPack(spec, pack);
		expect(outcome.kind).toBe('incomplete');
		if (outcome.kind === 'incomplete') {
			expect(outcome.problems).toEqual([{ kind: 'NO_CLIPS', character: 0 }]);
		}
		expect(en.create.build.problems.NO_CLIPS).toContain('ringtone');
	});
});
