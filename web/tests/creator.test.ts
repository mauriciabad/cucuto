/// <reference types="bun" />
// The Create page's pack building (#48) under Bun, with the browser build of shared/pack-spec: the shared assembler
// and clip pipeline (the same Kotlin as the app's creator), the zip and Ogg writers, and the golden fixtures re-laid
// out by the creator and checked by the same validator. tests/create.e2e.ts drives the page in a real browser.
import { describe, expect, test } from 'bun:test';
import { unzipSync } from 'fflate';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, extname, join } from 'node:path';
import { packSpec, type PackDraftInput, type PackSpecModule } from '../src/lib/pack-spec/core.ts';
import { encodeWav } from '../src/lib/creator/encode.ts';
import { oggCrc, writeOggOpus } from '../src/lib/creator/ogg.ts';
import { zipEntries } from '../src/lib/creator/pack.ts';

const fixtures = join(import.meta.dir, '..', '..', 'spec', 'fixtures');
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

/** Lays out a draft with the shared assembler and zips it like the Create page. */
function build(draft: PackDraftInput, files: Map<string, Uint8Array>) {
	const assembly = authoring.assemble(draft);
	expect(assembly.problems).toEqual([]);
	const entries: [string, Uint8Array][] = [
		['pack.json', new TextEncoder().encode(assembly.manifestJson!)]
	];
	for (const f of assembly.files) entries.push([f.path, files.get(f.key)!]);
	const zip = zipEntries(entries);
	return { assembly, zip, result: spec.validateArchive(zip) };
}

interface FixtureCharacter {
	id: string;
	name: string;
	sprite: string;
	seedColor?: string;
	clips?: { file: string; label?: string; weight?: number }[];
}

/**
 * Every valid single-pack fixture, rebuilt as a creator draft from its own media (characters with clips, their
 * sprites, labels and weights): the creator must turn it into a pack the validator accepts with no issue.
 */
describe('golden fixtures re-laid out by the creator', () => {
	const valid = join(fixtures, 'valid');
	const expected = new Map(
		(
			JSON.parse(readFileSync(join(fixtures, 'fixtures.json'), 'utf8')) as {
				fixtures: { path: string; warnings: string[] }[];
			}
		).fixtures.map((f) => [f.path, f.warnings])
	);
	// Packs whose characters have clips: the creators make notification clips (ringtones come later).
	const folders = readdirSync(valid).filter((name) => {
		const manifest = join(valid, name, 'pack.json');
		if (!statSync(join(valid, name)).isDirectory() || !existsSync(manifest)) return false;
		const pack = JSON.parse(readFileSync(manifest, 'utf8')) as { characters: FixtureCharacter[] };
		return pack.characters.some((c) => (c.clips?.length ?? 0) > 0);
	});
	for (const folder of folders) {
		test(folder, () => {
			const root = join(valid, folder);
			const manifest = JSON.parse(readFileSync(join(root, 'pack.json'), 'utf8')) as {
				name: string;
				author: string;
				license: string;
				characters: FixtureCharacter[];
			};
			const files = new Map<string, Uint8Array>();
			const ref = (path: string) => {
				files.set(path, new Uint8Array(readFileSync(join(root, path))));
				return { key: path, extension: extname(path).slice(1) };
			};
			const characters = manifest.characters.filter((c) => (c.clips?.length ?? 0) > 0);
			const { assembly, result } = build(
				{
					id: null,
					idSuffix: 'abc123',
					name: manifest.name,
					author: manifest.author,
					license: manifest.license,
					characters: characters.map((c) => ({
						id: c.id,
						name: c.name,
						sprite: ref(c.sprite),
						seedColor: c.seedColor ?? null,
						clips: (c.clips ?? []).map((clip) => ({
							file: ref(clip.file),
							label: clip.label ?? basename(clip.file, extname(clip.file)),
							weight: clip.weight ?? null
						}))
					}))
				},
				files
			);
			const details = result.issues
				.map((i) => `${i.code} ${i.file ?? ''}: ${i.message}`)
				.join('\n');
			// Only the license warning can carry over: the creator writes no stray or ignored files.
			const warnings = (expected.get(`valid/${folder}`) ?? []).filter(
				(w) => w === 'LICENSE_NOT_SPDX'
			);
			expect({ errors: result.errorCodes, warnings: result.warningCodes }, details).toEqual({
				errors: [],
				warnings
			});
			expect(result.packs[0].characters).toBe(characters.length);
			expect(assembly.packId).toMatch(/^local\.cucuto-fixtures\.[a-z0-9-]+-abc123$/);
		});
	}
});

describe('shared assembler', () => {
	const png = new Uint8Array(
		readFileSync(join(fixtures, 'valid/minimal-pack/characters/beep/sprite.png'))
	);
	const ogg = new Uint8Array(
		readFileSync(join(fixtures, 'valid/minimal-pack/characters/beep/clips/beep.ogg'))
	);

	test('writes the canonical layout, pack.json first, with the app creator conventions', () => {
		const files = new Map([
			['s', png],
			['c1', ogg],
			['c2', ogg]
		]);
		const { assembly, zip, result } = build(
			{
				id: null,
				idSuffix: 'k3x9q2',
				name: 'Garden birds',
				author: 'Ana',
				license: 'CC-BY-4.0',
				description: '  ',
				characters: [
					{
						id: null,
						name: 'Pájaro azul',
						sprite: { key: 's', extension: 'png' },
						seedColor: '#3D8BFF',
						clips: [
							{ file: { key: 'c1', extension: 'ogg' }, label: 'Tweet!', weight: 2 },
							{ file: { key: 'c2', extension: 'ogg' }, label: 'Tweet', weight: null }
						]
					}
				]
			},
			files
		);
		expect(result.valid).toBe(true);
		expect(assembly.packId).toBe('local.ana.garden-birds-k3x9q2');
		expect(assembly.fileName).toBe('garden-birds.soundpack.zip');
		expect(Object.keys(unzipSync(zip))).toEqual([
			'pack.json',
			'characters/pajaro-azul/sprite.png',
			'characters/pajaro-azul/clips/tweet.ogg',
			'characters/pajaro-azul/clips/tweet-2.ogg'
		]);
		const manifest = JSON.parse(assembly.manifestJson!);
		expect(manifest).toEqual({
			formatVersion: 1,
			id: 'local.ana.garden-birds-k3x9q2',
			name: 'Garden birds',
			version: '1.0.0',
			author: 'Ana',
			license: 'CC-BY-4.0',
			characters: [
				{
					id: 'pajaro-azul',
					name: 'Pájaro azul',
					sprite: 'characters/pajaro-azul/sprite.png',
					seedColor: '#3D8BFF',
					clips: [
						{ file: 'characters/pajaro-azul/clips/tweet.ogg', label: 'Tweet!', weight: 2 },
						{ file: 'characters/pajaro-azul/clips/tweet-2.ogg', label: 'Tweet' }
					]
				}
			]
		});
		expect(assembly.manifestJson!.endsWith('}\n')).toBe(true);
	});

	test('reports what a draft still needs', () => {
		const assembly = authoring.assemble({
			id: null,
			idSuffix: 'aaaaaa',
			name: ' ',
			author: '',
			license: 'CC0-1.0',
			characters: [{ id: null, name: '', sprite: null, seedColor: null, clips: [] }]
		});
		expect(assembly.manifestJson).toBeNull();
		expect(assembly.problems).toEqual([
			{ kind: 'PACK_NAME_MISSING', character: null },
			{ kind: 'AUTHOR_MISSING', character: null },
			{ kind: 'CHARACTER_NAME_MISSING', character: 0 },
			{ kind: 'SPRITE_MISSING', character: 0 },
			{ kind: 'NO_CLIPS', character: 0 }
		]);
	});

	test('offers the license presets and targets of both creators', () => {
		expect(authoring.licensePresets).toEqual(['CC0-1.0', 'CC-BY-4.0']);
		expect(authoring.personalUseLicense).toBe('Personal use only');
		expect(authoring.targets).toMatchObject({
			sampleRate: 48_000,
			channels: 1,
			clipBitrate: 96_000,
			spriteSize: 512,
			clipMinMs: 200,
			clipMaxMs: 30_000,
			clipHintMs: 5_000
		});
		expect(authoring.randomIdSuffix()).toMatch(/^[a-z0-9]{6}$/);
	});
});

describe('shared clip pipeline', () => {
	const rate = 48_000;
	/** 0.5 s of silence, 1 s of a 440 Hz tone at `amplitude`, 0.5 s of silence. */
	function tone(amplitude: number) {
		const samples = new Float32Array(rate * 2);
		for (let i = rate / 2; i < rate * 1.5; i++)
			samples[i] = amplitude * Math.sin((2 * Math.PI * 440 * i) / rate);
		return samples;
	}

	test('trims silence and normalizes to -16 LUFS', () => {
		const source = tone(0.05);
		const clip = authoring.processClip(source, 0, 2000, { trimSilence: true, normalize: true });
		expect(clip.sampleRate).toBe(48_000);
		expect(clip.durationMs).toBeGreaterThanOrEqual(1000);
		expect(clip.durationMs).toBeLessThanOrEqual(1050);
		expect(clip.loudnessLufs!).toBeCloseTo(-16, 0);
		expect(clip.gainDb).toBeGreaterThan(0);
		expect(source[rate]).not.toBe(clip.samples[rate / 2]); // the source is untouched
	});

	test('keeps the cut as is without trimming or normalizing', () => {
		const clip = authoring.processClip(tone(0.05), 250, 1750, {
			trimSilence: false,
			normalize: false
		});
		expect(clip.durationMs).toBe(1500);
		expect(clip.gainDb).toBe(0);
	});

	test('reports silence', () => {
		const clip = authoring.processClip(new Float32Array(rate), 0, 1000, {
			trimSilence: true,
			normalize: true
		});
		expect(clip.silent).toBe(true);
		expect(clip.loudnessLufs).toBeNull();
	});

	test('finds the hook of a song with the shared heuristic', () => {
		const song = new Float32Array(rate * 10);
		for (let i = rate * 6; i < rate * 8; i++) song[i] = Math.sin(i / 10);
		const [start, end] = authoring.findHook(song, rate, 2000);
		expect(end - start).toBe(2000);
		expect(start).toBeGreaterThanOrEqual(5750);
		expect(start).toBeLessThanOrEqual(6000);
	});
});

describe('clip files', () => {
	const png = new Uint8Array(
		readFileSync(join(fixtures, 'valid/minimal-pack/characters/beep/sprite.png'))
	);

	function packWith(extension: string, clip: Uint8Array) {
		return build(
			{
				id: null,
				idSuffix: 'aaaaaa',
				name: 'Clips',
				author: 'Test',
				license: 'CC0-1.0',
				characters: [
					{
						id: null,
						name: 'Beep',
						sprite: { key: 's', extension: 'png' },
						seedColor: null,
						clips: [{ file: { key: 'c', extension }, label: 'Beep' }]
					}
				]
			},
			new Map([
				['s', png],
				['c', clip]
			])
		).result;
	}

	test('WAV fallback clips are valid pack audio', () => {
		expect(packWith('wav', encodeWav(new Float32Array(48_000), 48_000)).valid).toBe(true);
		const tooShort = packWith('wav', encodeWav(new Float32Array(4_800), 48_000));
		expect(tooShort.errorCodes).toEqual(['CLIP_DURATION_OUT_OF_RANGE']);
	});

	test('Ogg Opus files measure exactly the clip length', () => {
		// 20 ms packets of digital silence (TOC 0xF8: CELT fullband 20 ms, mono, one frame).
		const ogg = (sampleCount: number) => {
			const packets = Array.from({ length: Math.ceil((sampleCount + 312) / 960) + 1 }, () =>
				Uint8Array.of(0xf8, 0xff, 0xfe)
			);
			return writeOggOpus({
				packets,
				packetSamples: packets.map(() => 960),
				sampleCount,
				preSkip: 312,
				channels: 1,
				inputSampleRate: 48_000,
				serial: 1234,
				vendor: 'test'
			});
		};
		expect(packWith('ogg', ogg(48_000)).valid).toBe(true);
		expect(packWith('ogg', ogg(30 * 48_000)).valid).toBe(true);
		expect(packWith('ogg', ogg(31 * 48_000)).errorCodes).toEqual(['CLIP_DURATION_OUT_OF_RANGE']);
		expect(packWith('ogg', ogg(4_800)).errorCodes).toEqual(['CLIP_DURATION_OUT_OF_RANGE']);
	});

	test('Ogg pages carry the CRC other encoders write', () => {
		const file = new Uint8Array(
			readFileSync(join(fixtures, 'valid/minimal-pack/characters/beep/clips/beep.ogg'))
		);
		const segments = file[26];
		const length = 27 + segments + file.subarray(27, 27 + segments).reduce((a, b) => a + b, 0);
		const page = file.slice(0, length);
		const stored = new DataView(page.buffer).getUint32(22, true);
		page.fill(0, 22, 26);
		expect(oggCrc(page)).toBe(stored);
	});
});
