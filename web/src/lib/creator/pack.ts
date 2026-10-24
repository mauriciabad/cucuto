// The Create page's pack: its draft, laid out by shared/pack-spec's PackAssembler (the same Kotlin as the app's
// creator, so both write the same layout and pack.json), zipped like the app writes it, then checked with the same
// validator as packer and the importer. Everything stays in memory on the device.
import { zipSync, type Zippable } from 'fflate';
import type { EncodedClip } from './encode.js';
import type { EncodedImage } from './image.js';
import type {
	DraftProblem,
	PackDraftInput,
	PackSpec,
	PackSpecResult
} from '#lib/pack-spec/core.js';

export interface ClipEdit {
	startMs: number;
	endMs: number;
	trimSilence: boolean;
	normalize: boolean;
}

/** What the clip editor hands back. */
export interface SavedClip {
	encoded: EncodedClip;
	/** The processed clip, for previews (mono, 48 kHz). */
	samples: Float32Array;
	durationMs: number;
	edit: ClipEdit;
}

export interface ClipState {
	uid: string;
	label: string;
	/** Relative probability, 1 by default (docs/pack-format.md#clips). */
	weight: number;
	encoded: EncodedClip;
	/** The processed clip, for previews (mono, 48 kHz). */
	samples: Float32Array;
	durationMs: number;
	/** The audio the clip was cut from, so it can be edited again. */
	source: Float32Array;
	edit: ClipEdit;
}

export interface CharacterState {
	uid: string;
	name: string;
	sprite: EncodedImage | null;
	seedColor: string | null;
	clips: ClipState[];
}

export interface PackState {
	/** Random part of the derived pack ID, fixed for the life of the draft. */
	idSuffix: string;
	name: string;
	author: string;
	license: string;
	description: string;
	characters: CharacterState[];
}

export interface BuiltPack {
	fileName: string;
	packId: string;
	manifestJson: string;
	bytes: Uint8Array;
	result: PackSpecResult;
}

export type BuildOutcome =
	{ kind: 'incomplete'; problems: DraftProblem[] } | { kind: 'built'; pack: BuiltPack };

/** 1980-01-02 00:00, inside the zip date range, as the app writes: no clock time leaks into the file. */
const FIXED_TIME = new Date(1980, 0, 2, 0, 0, 0);
/** java.util.zip's default deflate level, which the app uses. */
const LEVEL = 6;

const spriteKey = (character: CharacterState) => `${character.uid}/sprite`;

/** The draft as shared/pack-spec's `PackDraft`, with file keys pointing into this state. */
export function draftInput(pack: PackState): PackDraftInput {
	return {
		id: null,
		idSuffix: pack.idSuffix,
		name: pack.name,
		author: pack.author,
		license: pack.license,
		description: pack.description.trim() === '' ? null : pack.description,
		cover: null,
		characters: pack.characters.map((c) => ({
			id: null,
			name: c.name,
			sprite: c.sprite ? { key: spriteKey(c), extension: c.sprite.extension } : null,
			seedColor: c.seedColor,
			clips: c.clips.map((clip) => ({
				file: { key: clip.uid, extension: clip.encoded.extension },
				label: clip.label.trim() === '' ? null : clip.label,
				weight: clip.weight === 1 ? null : clip.weight
			}))
		}))
	};
}

/** Every file of the draft by key. */
function filesByKey(pack: PackState): Map<string, Uint8Array> {
	const files = new Map<string, Uint8Array>();
	for (const c of pack.characters) {
		if (c.sprite) files.set(spriteKey(c), c.sprite.bytes);
		for (const clip of c.clips) files.set(clip.uid, clip.encoded.bytes);
	}
	return files;
}

/**
 * A zip with `entries` in order, deflated, with fixed timestamps: what the app's PackWriter writes (`pack.json`
 * first, then the files in the assembler's order).
 */
export function zipEntries(entries: [string, Uint8Array][]): Uint8Array {
	const input: Zippable = {};
	for (const [path, bytes] of entries) input[path] = [bytes, { level: LEVEL, mtime: FIXED_TIME }];
	return zipSync(input);
}

/** Lays out, zips and validates the pack. */
export function buildPack(spec: PackSpec, pack: PackState): BuildOutcome {
	const assembly = spec.authoring.assemble(draftInput(pack));
	if (assembly.problems.length > 0 || assembly.manifestJson === null) {
		return { kind: 'incomplete', problems: assembly.problems };
	}
	const files = filesByKey(pack);
	const entries: [string, Uint8Array][] = [
		['pack.json', new TextEncoder().encode(assembly.manifestJson)]
	];
	for (const file of assembly.files) {
		const bytes = files.get(file.key);
		if (!bytes) throw new Error(`Missing file ${file.key}`);
		entries.push([file.path, bytes]);
	}
	const bytes = zipEntries(entries);
	return {
		kind: 'built',
		pack: {
			fileName: assembly.fileName ?? 'pack.soundpack.zip',
			packId: assembly.packId ?? '',
			manifestJson: assembly.manifestJson,
			bytes,
			result: spec.validateArchive(bytes)
		}
	};
}

let counter = 0;
/** A unique key for a character or clip in this session. */
export const uid = (prefix: string) =>
	`${prefix}${Date.now().toString(36)}${(counter++).toString(36)}`;
