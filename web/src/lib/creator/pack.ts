// The Create page's pack: its draft, laid out by shared/pack-spec's PackAssembler (the same Kotlin as the app's
// creator, so both write the same layout and pack.json), zipped like the app writes it, then checked with the same
// validator as packer and the importer. Everything stays on the device (drafts in IndexedDB, see drafts.ts).
import { zipSync, type Zippable } from 'fflate';
import type { EncodedClip } from './encode.js';
import type { EncodedImage } from './image.js';
import type {
	DraftProblem,
	HapticEventJson,
	HapticJson,
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
	/** The processed clip, for previews (mono, 48 kHz); null when this browser cannot decode the file. */
	samples: Float32Array | null;
	durationMs: number;
	/** The audio the clip was cut from, so it can be edited again; null when not kept (see drafts.ts). */
	source: Float32Array | null;
	edit: ClipEdit;
	/** The clip's condition as in pack.json (day and night clips, moments), from the condition editor. */
	when: Record<string, unknown> | null;
	/** A rare clip's chance (`oneIn`), from the condition editor. */
	oneIn?: number | null;
}

/** A file written to the pack as it is: a ringtone (kept in its own format) or an animated sprite. */
export interface KeptFile {
	/** Stable within the draft: ringtone variants that share a file share its ID. */
	id: string;
	bytes: Uint8Array;
	/** File extension, without the dot. */
	extension: string;
	/** An audio file's length, when known. */
	durationMs?: number | null;
	/** The file's name when it was added, for the editors. */
	name?: string | null;
}

/**
 * A ringtone version: `entry` is its pack.json entry (from shared/pack-spec's RingtoneForm, or as an opened pack had
 * it), written with only its `file` replaced. Versions that share `file` share one file in the pack.
 */
export interface RingtoneState {
	/** Missing in drafts saved before ringtones were editable. */
	uid?: string;
	file: KeptFile;
	entry: Record<string, unknown>;
}

/** A character's vibration: the format's `auto` (follows the voice), `none`, or a custom timeline. */
export interface HapticState {
	mode: 'auto' | 'none' | 'custom';
	events: HapticEventJson[];
}

export interface CharacterState {
	uid: string;
	/** The character's ID when it came from an opened pack (assignments refer to it); null to derive one. */
	id: string | null;
	name: string;
	sprite: EncodedImage | null;
	seedColor: string | null;
	clips: ClipState[];
	haptic: HapticState;
	description: string | null;
	/** Pass-through data from an opened pack (the rules of the app's "Duplicate and edit"). */
	tags: string[];
	spriteAnimated: KeptFile | null;
	ringtones: RingtoneState[];
}

/** Where a draft came from when it was opened from a pack file. */
export interface PackOrigin {
	id: string;
	name: string;
	version: string;
}

export interface PackState {
	/** Random part of the derived pack ID, fixed for the life of the draft. */
	idSuffix: string;
	/** The pack's ID when it keeps the one of an opened pack (an update); null to derive one. */
	id: string | null;
	version: string;
	name: string;
	author: string;
	license: string;
	description: string;
	cover: EncodedImage | null;
	characters: CharacterState[];
	homepage: string | null;
	tags: string[];
	/** The pack this draft was opened from, if any. */
	origin: PackOrigin | null;
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

/** The version of a new pack (`Authoring.FIRST_VERSION`). */
export const FIRST_VERSION = '1.0.0';

const spriteKey = (character: CharacterState) => `${character.uid}/sprite`;
const animationKey = (character: CharacterState) => `${character.uid}/sprite-anim`;
const ringtoneKey = (character: CharacterState, file: KeptFile) => `${character.uid}/${file.id}`;
const COVER_KEY = 'cover';

/** A new, empty pack. */
export function emptyPack(idSuffix: string, license: string): PackState {
	return {
		idSuffix,
		id: null,
		version: FIRST_VERSION,
		name: '',
		author: '',
		license,
		description: '',
		cover: null,
		characters: [],
		homepage: null,
		tags: [],
		origin: null
	};
}

/** A new, empty character. */
export function emptyCharacter(): CharacterState {
	return {
		uid: uid('char'),
		id: null,
		name: '',
		sprite: null,
		seedColor: null,
		clips: [],
		haptic: { mode: 'auto', events: [] },
		description: null,
		tags: [],
		spriteAnimated: null,
		ringtones: []
	};
}

/** The character's `haptic` for pack.json: null for `auto` (the default) and for a custom mode without events. */
export function hapticOf(character: CharacterState): HapticJson | null {
	const { mode, events } = character.haptic;
	if (mode === 'none') return { mode: 'none' };
	if (mode === 'custom' && events.length > 0) return { mode: 'custom', events: events.map(clean) };
	return null;
}

/** Numbers as the format types them (an emptied number field is null): whole milliseconds, fractions as typed. */
function clean(event: HapticEventJson): HapticEventJson {
	const whole = (value: unknown) => Math.round(Number(value) || 0);
	const fraction = (value: unknown) => Number(value) || 0;
	const out: HapticEventJson = { at: whole(event.at) };
	if (event.envelope) {
		if (event.initialSharpness !== undefined)
			out.initialSharpness = fraction(event.initialSharpness);
		out.envelope = event.envelope.map((p) => ({
			intensity: fraction(p.intensity),
			sharpness: fraction(p.sharpness),
			ms: whole(p.ms)
		}));
	} else {
		out.preset = event.preset;
		if (event.scale !== undefined && event.scale !== 1) out.scale = fraction(event.scale);
	}
	return out;
}

const blankToNull = (text: string | null) => (text === null || text.trim() === '' ? null : text);

/** The draft as shared/pack-spec's `PackDraft`, with file keys pointing into this state. */
export function draftInput(pack: PackState): PackDraftInput {
	return {
		id: pack.id,
		idSuffix: pack.idSuffix,
		name: pack.name,
		version: pack.version,
		author: pack.author,
		license: pack.license,
		description: blankToNull(pack.description),
		cover: pack.cover ? { key: COVER_KEY, extension: pack.cover.extension } : null,
		homepage: pack.homepage,
		tags: pack.tags,
		characters: pack.characters.map((c) => ({
			id: c.id,
			name: c.name,
			sprite: c.sprite ? { key: spriteKey(c), extension: c.sprite.extension } : null,
			seedColor: c.seedColor,
			clips: c.clips.map((clip) => ({
				file: { key: clip.uid, extension: clip.encoded.extension },
				label: blankToNull(clip.label),
				weight: clip.weight === 1 ? null : clip.weight,
				when: clip.when,
				oneIn: clip.oneIn ?? null
			})),
			haptic: hapticOf(c),
			description: c.description,
			tags: c.tags,
			spriteAnimated: c.spriteAnimated
				? { key: animationKey(c), extension: c.spriteAnimated.extension }
				: null,
			ringtones: ringtoneRefs(c)
		}))
	};
}

/** Ringtone variants: variants that shared a file in the opened pack still share one key, so one file. */
function ringtoneRefs(c: CharacterState) {
	return c.ringtones.map((r) => ({
		file: { key: ringtoneKey(c, r.file), extension: r.file.extension },
		entry: r.entry
	}));
}

/** Every file of the draft by key. */
function filesByKey(pack: PackState): Map<string, Uint8Array> {
	const files = new Map<string, Uint8Array>();
	if (pack.cover) files.set(COVER_KEY, pack.cover.bytes);
	for (const c of pack.characters) {
		if (c.sprite) files.set(spriteKey(c), c.sprite.bytes);
		if (c.spriteAnimated) files.set(animationKey(c), c.spriteAnimated.bytes);
		for (const clip of c.clips) files.set(clip.uid, clip.encoded.bytes);
		for (const r of c.ringtones) files.set(ringtoneKey(c, r.file), r.file.bytes);
	}
	return files;
}

/**
 * A zip with `entries` in order, deflated, with fixed timestamps: what the app's PackWriter writes (the manifest
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

/** Offers bytes as a download (the site's CSP allows blob: downloads through an anchor). */
export function downloadFile(file: File) {
	const url = URL.createObjectURL(file);
	const a = document.createElement('a');
	a.href = url;
	a.download = file.name;
	document.body.append(a);
	a.click();
	a.remove();
	setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

let counter = 0;
/** A unique key for a character or clip in this session. */
export const uid = (prefix: string) =>
	`${prefix}${Date.now().toString(36)}${(counter++).toString(36)}`;
