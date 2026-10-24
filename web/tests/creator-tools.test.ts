/// <reference types="bun" />
// The Create page's tools under Bun (#191), with the browser build of shared/pack-spec: opening packs for editing
// (the app's pass-through rules), bundles, the repository helper (`packer index` in the browser) and the haptic text
// shared with the app's haptic editor. tests/create-tools.e2e.ts drives the same code in a real browser.
import { describe, expect, test } from 'bun:test';
import { unzipSync, zipSync } from 'fflate';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { packSpec, type PackSpecModule } from '../src/lib/pack-spec/core.ts';
import { buildBundle, readPack } from '../src/lib/creator/bundle.ts';
import {
	addToAppLink,
	buildRepository,
	isHttpsUrl,
	safeFileName,
	sha256Hex
} from '../src/lib/creator/indexer.ts';
import { loudnessRows } from '../src/lib/creator/haptics.ts';
import { zipEntries } from '../src/lib/creator/pack.ts';

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

/** A fixture folder as a zip, the way a pack file arrives. */
function zipFolder(folder: string): Uint8Array {
	const root = join(fixtures, folder);
	const files: Record<string, Uint8Array> = {};
	const walk = (dir: string) => {
		for (const name of readdirSync(dir)) {
			const path = join(dir, name);
			if (statSync(path).isDirectory()) walk(path);
			else files[relative(root, path).split('\\').join('/')] = new Uint8Array(readFileSync(path));
		}
	};
	walk(root);
	return zipSync(files);
}

const json = (bytes: Uint8Array) =>
	JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;

describe('opening a pack for editing', () => {
	test('every field the format allows comes back when the draft is written again', () => {
		const archive = zipFolder('full-pack');
		const opened = authoring.openPack(archive);
		expect(opened.result.valid).toBe(true);
		const draft = opened.draft!;
		expect(draft.id).toBe('app.mauri.fixtures.full');
		expect(draft.version).toBe('2.1.0-beta.1');
		expect(opened.durations['characters/robin/clips/chirp.ogg']).toBeGreaterThan(0);

		// Write it back with the files read by their keys, as the Create page does.
		const files = unzipSync(archive);
		const assembly = authoring.assemble(draft);
		expect(assembly.problems).toEqual([]);
		const entries: [string, Uint8Array][] = [
			['pack.json', new TextEncoder().encode(assembly.manifestJson!)]
		];
		for (const f of assembly.files) entries.push([f.path, files[f.key]]);
		const result = spec.validateArchive(zipEntries(entries));
		expect(result.errorCodes).toEqual([]);

		const original = json(files['pack.json']) as {
			homepage: string;
			tags: string[];
			characters: Record<string, unknown>[];
		};
		const written = JSON.parse(assembly.manifestJson!) as typeof original;
		expect(written.homepage).toBe(original.homepage);
		expect(written.tags).toEqual(original.tags);
		const [robin, song, ringer] = written.characters as Record<string, unknown>[];
		expect(robin.description).toBe('A small bird with two calls.');
		expect(robin.spriteAnimated).toBe('characters/robin/sprite-anim.gif');
		expect(robin.haptic).toEqual(original.characters[0].haptic);
		expect((robin.clips as { when: unknown }[]).map((c) => c.when)).toEqual(
			(original.characters[0].clips as { when: unknown }[]).map((c) => c.when)
		);
		expect(song.haptic).toEqual({ mode: 'none' });
		// Four variants over two files: the entries come back as they were, the shared files written once.
		const ringtones = song.ringtones as Record<string, unknown>[];
		const withoutFile = (r: Record<string, unknown>) =>
			Object.fromEntries(Object.entries(r).filter(([key]) => key !== 'file'));
		expect(ringtones.map(withoutFile)).toEqual(
			(original.characters[1].ringtones as Record<string, unknown>[]).map(withoutFile)
		);
		expect(new Set(ringtones.map((r) => r.file)).size).toBe(2);
		expect(ringer.haptic).toBeUndefined(); // an explicit "auto" is the default, left out
	});

	test('a bundle lists its packs, and one of them opens', () => {
		const archive = zipFolder('bundle');
		const opened = authoring.openPack(archive);
		expect(opened.draft).toBeNull();
		expect(opened.roots).toEqual(['packs/first', 'packs/second']);
		const second = authoring.openPack(archive, 'packs/second');
		expect(second.draft!.characters[0].sprite!.key).toStartWith('packs/second/');
	});

	test('an invalid archive only reports the check', () => {
		const opened = authoring.openPack(new Uint8Array([1, 2, 3]));
		expect(opened.result.valid).toBe(false);
		expect(opened.draft).toBeNull();
	});

	test('an update gets the next version', () => {
		expect(authoring.versionAfter('1.2.3', '1.2.3')).toBe('1.2.4');
		expect(authoring.versionAfter('2.1.0-beta.1', '2.1.0-beta.1')).toBe('2.1.0');
	});
});

describe('bundles', () => {
	const first = new Uint8Array(readFileSync(join(fixtures, 'minimal-pack.soundpack.zip')));
	const second = zipFolder('full-pack');
	const summary = (bytes: Uint8Array) => {
		const read = readPack(spec, bytes);
		if (read.kind !== 'pack') throw new Error(read.kind);
		return read.summary;
	};

	test('packs go to packs/<id>/ next to bundle.json, and the bundle passes every rule', () => {
		const packs = [first, second].map((bytes, i) => ({
			uid: `p${i}`,
			fileName: `p${i}.zip`,
			bytes,
			summary: summary(bytes)
		}));
		const outcome = buildBundle(
			spec,
			{
				id: 'local.me.everything',
				name: 'Everything',
				version: '1.0.0',
				author: 'Me',
				license: '',
				description: ''
			},
			packs,
			null
		);
		if (outcome.kind !== 'built') throw new Error(JSON.stringify(outcome));
		expect(outcome.fileName).toBe('everything.soundpack.zip');
		expect(outcome.result.valid).toBe(true);
		expect(outcome.result.kind).toBe('bundle');
		expect(outcome.result.packs.map((p) => p.root)).toEqual([
			`packs/${packs[0].summary.id}`,
			`packs/${packs[1].summary.id}`
		]);
		const paths = Object.keys(unzipSync(outcome.bytes));
		expect(paths[0]).toBe('bundle.json');
		expect(paths.slice(1)).toEqual([...paths.slice(1)].sort());
	});

	test('a bundle cannot go inside a bundle, and drafts say what they miss', () => {
		expect(readPack(spec, zipFolder('bundle')).kind).toBe('bundle');
		const outcome = buildBundle(
			spec,
			{ id: 'Nope', name: '', version: 'x', author: '', license: '', description: '' },
			[],
			null
		);
		expect(outcome).toEqual({
			kind: 'incomplete',
			problems: ['BUNDLE_ID_INVALID', 'BUNDLE_NAME_MISSING', 'VERSION_INVALID', 'NO_PACKS']
		});
		expect(authoring.isPackId('local.me.all')).toBe(true);
		expect(authoring.isPackId('Local')).toBe(false);
	});
});

describe('repository helper', () => {
	const pack = new Uint8Array(readFileSync(join(fixtures, 'minimal-pack.soundpack.zip')));
	const bundle = zipFolder('bundle');

	async function file(name: string, bytes: Uint8Array, taken: string[] = []) {
		return {
			uid: name,
			fileName: safeFileName(name, taken),
			bytes,
			sha256: await sha256Hex(bytes),
			result: spec.validateArchive(bytes)
		};
	}

	test('writes the folder packer index writes, checked with the repository schema', async () => {
		const files = [await file('minimal.soundpack.zip', pack), await file('My bundle.zip', bundle)];
		const outcome = buildRepository(
			spec,
			{ name: 'My packs', description: '', homepage: 'https://example.com', baseUrl: null },
			files
		);
		if (outcome.kind !== 'built') throw new Error(JSON.stringify(outcome.result.issues));
		expect(outcome.entries).toBe(2);
		expect(spec.validateDocument('repository', new TextEncoder().encode(outcome.json)).valid).toBe(
			true
		);
		const folder = unzipSync(outcome.zip);
		const index = json(folder['repository.json']) as {
			name: string;
			homepage: string;
			packs: {
				id: string;
				type: string;
				cover?: string;
				download: { url: string; size: number; sha256: string };
			}[];
		};
		expect(index.name).toBe('My packs');
		expect(index.packs.map((p) => p.type).sort()).toEqual(['bundle', 'pack']);
		for (const entry of index.packs) {
			const f = folder[entry.download.url];
			expect(f).toBeDefined();
			expect(entry.download.size).toBe(f.length);
			expect(entry.download.sha256).toBe(await sha256Hex(f));
			if (entry.cover) expect(folder[entry.cover]).toBeDefined();
		}
		expect(Object.keys(folder)).toContain('packs/My-bundle.soundpack.zip');
		expect(Object.keys(folder).some((p) => p.startsWith('previews/'))).toBe(true);
	});

	test('characters with a sample carry the pack haptic as x-haptic, as packer writes it', async () => {
		const full = await file('full.soundpack.zip', zipFolder('full-pack'));
		const outcome = buildRepository(
			spec,
			{ name: 'Haptics', description: '', homepage: '', baseUrl: null },
			[full, await file('minimal.soundpack.zip', pack)]
		);
		if (outcome.kind !== 'built') throw new Error(JSON.stringify(outcome.result.issues));
		const index = json(unzipSync(outcome.zip)['repository.json']) as {
			packs: { id: string; characters: { id: string; 'x-haptic'?: { mode: string } }[] }[];
		};
		const modes = Object.fromEntries(
			index.packs.flatMap((p) => p.characters.map((c) => [`${p.id}/${c.id}`, c['x-haptic']?.mode]))
		);
		expect(modes).toEqual({
			'app.mauri.fixtures.full/robin': 'custom',
			'app.mauri.fixtures.full/song': 'none',
			'app.mauri.fixtures.full/ringer': undefined,
			'app.mauri.fixtures.minimal/beep': 'auto'
		});
	});

	test('packs hosted elsewhere are listed by their address and left out of the folder', async () => {
		const outcome = buildRepository(
			spec,
			{
				name: 'Releases',
				description: '',
				homepage: '',
				baseUrl: 'https://github.com/me/packs/releases/download/v1/'
			},
			[await file('minimal.soundpack.zip', pack)]
		);
		if (outcome.kind !== 'built') throw new Error('not built');
		expect(outcome.json).toContain(
			'"url": "https://github.com/me/packs/releases/download/v1/minimal.soundpack.zip"'
		);
		expect(Object.keys(unzipSync(outcome.zip)).some((p) => p.startsWith('packs/'))).toBe(false);
	});

	test('the same pack twice lists the highest version once', async () => {
		const outcome = buildRepository(
			spec,
			{ name: 'Twice', description: '', homepage: '', baseUrl: null },
			[await file('a.zip', pack), await file('a.zip', pack, ['a.soundpack.zip'])]
		);
		if (outcome.kind !== 'built') throw new Error('not built');
		expect(outcome.entries).toBe(1);
		expect(Object.keys(unzipSync(outcome.zip)).filter((p) => p.startsWith('packs/'))).toHaveLength(
			1
		);
	});

	test('file names, addresses and add-to-app links', () => {
		expect(safeFileName('My Pack (1).zip', [])).toBe('My-Pack-1.soundpack.zip');
		expect(safeFileName('birds.soundpack.zip', ['birds.soundpack.zip'])).toBe(
			'birds-2.soundpack.zip'
		);
		expect(safeFileName('ピカ.zip', [])).toBe('pack.soundpack.zip');
		expect(isHttpsUrl('https://me.github.io/packs/')).toBe(true);
		expect(isHttpsUrl('http://me.github.io/')).toBe(false);
		expect(addToAppLink('https://me.github.io/packs/')).toBe(
			'cucuto://repo/add?url=https%3A%2F%2Fme.github.io%2Fpacks%2F'
		);
	});
});

describe('haptic pattern text (shared with the app)', () => {
	const events = [
		{ at: 0, preset: 'CLICK' as const, scale: 0.8 },
		{
			at: 120,
			envelope: [
				{ intensity: 1, sharpness: 0.5, ms: 100 },
				{ intensity: 0, sharpness: 0.2, ms: 150 }
			]
		}
	];

	test('a pattern round-trips through its compact JSON', () => {
		const text = authoring.hapticPatternJson(events);
		expect(text.startsWith('{"mode":"custom","events":[')).toBe(true);
		expect(authoring.parseHapticPattern(`Here you go:\n\`\`\`json\n${text}\n\`\`\``)).toEqual({
			valid: true,
			events
		});
	});

	test('broken patterns name the rule they break', () => {
		const problem = (text: string) => {
			const parsed = authoring.parseHapticPattern(text);
			return parsed.valid ? null : parsed.problem;
		};
		expect(problem('no JSON here')).toEqual({ kind: 'NotJson' });
		expect(problem('[{"at":0,"preset":"CLICK","scale":1.5}]')).toEqual({
			kind: 'OutOfRange',
			event: 1,
			field: 'scale',
			min: 0,
			max: 1,
			value: '1.5'
		});
		expect(
			problem(
				'[{"at":0,"envelope":[{"intensity":0,"sharpness":0.5,"ms":300}]},{"at":200,"preset":"TICK"}]'
			)
		).toEqual({
			kind: 'Overlap',
			event: 2,
			ms: 200,
			until: 300
		});
		expect(problem('[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5}]}]')).toEqual({
			kind: 'MissingField',
			event: 1,
			point: 1,
			field: 'ms'
		});
	});

	test('the prompt carries the clips, the pattern and the format', () => {
		const prompt = authoring.hapticPrompt(
			'Robin',
			[
				{
					path: 'clips/chirp.ogg',
					durationMs: 640,
					loudness: [
						[0, 0, 50],
						[40, 90, 70]
					]
				}
			],
			events
		);
		expect(prompt).toContain('Character: Robin.');
		expect(prompt).toContain('Clip 1, "clips/chirp.ogg": 640 ms long.');
		expect(prompt).toContain('ms,loudness,sharpness\n0,0,50\n40,90,70\n');
		expect(prompt).toContain('{"mode":"custom","events":[');
		expect(authoring.parseHapticPattern(prompt)).toEqual({ valid: true, events });
	});

	test('the browser loudness rows follow the clip', () => {
		const rate = 48_000;
		const samples = new Float32Array(rate);
		for (let i = rate / 4; i < rate / 2; i++)
			samples[i] = 0.5 * Math.sin((2 * Math.PI * 440 * i) / rate);
		const rows = loudnessRows(samples, rate)!;
		expect(rows[0]).toEqual([0, 0, 0]);
		expect(rows.some(([ms, level]) => ms >= 240 && ms < 500 && level >= 95)).toBe(true);
		expect(loudnessRows(new Float32Array(rate), rate)).toBeNull();
	});
});
