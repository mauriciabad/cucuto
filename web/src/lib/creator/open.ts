// "Edit an existing pack" (#191): a .soundpack.zip becomes a draft. shared/pack-spec validates the archive and turns
// its manifest into a draft with the app's "Duplicate and edit" pass-through rules (PackEditing), so nothing the
// format allows is lost: ringtones, animated sprites, clip conditions, tags and descriptions come back unchanged even
// though this page does not edit them yet. The browser only reads the files and decodes the clips for previews.
import { unzipSync } from 'fflate';
import { decodeToMono } from './audio.js';
import type { EncodedClip } from './encode.js';
import type { EncodedImage } from './image.js';
import {
	FIRST_VERSION,
	uid,
	type CharacterState,
	type ClipState,
	type HapticState,
	type KeptFile,
	type PackState
} from './pack.js';
import type { PackSpec, PackSpecResult, PackSummary } from '#lib/pack-spec/core.js';

/** MIME types of the pack media formats (docs/pack-format.md#media), by extension. */
const MIME: Record<string, string> = {
	png: 'image/png',
	jpg: 'image/jpeg',
	jpeg: 'image/jpeg',
	webp: 'image/webp',
	gif: 'image/gif',
	avif: 'image/avif',
	jxl: 'image/jxl',
	svg: 'image/svg+xml',
	ogg: 'audio/ogg',
	oga: 'audio/ogg',
	opus: 'audio/ogg',
	mp3: 'audio/mpeg',
	m4a: 'audio/mp4',
	aac: 'audio/aac',
	flac: 'audio/flac',
	wav: 'audio/wav'
};

export const mimeOf = (extension: string) =>
	MIME[extension.toLowerCase()] ?? 'application/octet-stream';

const extensionOf = (path: string) => path.slice(path.lastIndexOf('.') + 1).toLowerCase();

/** A data: URL (the site's CSP allows data: images and media, not blob: ones). */
export function dataUrl(bytes: Uint8Array, type: string): string {
	let binary = '';
	const chunk = 0x8000;
	for (let i = 0; i < bytes.length; i += chunk) {
		binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
	}
	return `data:${type};base64,${btoa(binary)}`;
}

/** An image file as the creator holds pictures. */
export function imageFile(bytes: Uint8Array, extension: string): EncodedImage {
	const type = mimeOf(extension);
	return { bytes, extension: extension.toLowerCase(), type, preview: dataUrl(bytes, type) };
}

export type OpenOutcome =
	| { kind: 'invalid'; result: PackSpecResult }
	/** A valid bundle: the person picks one of its packs. */
	| { kind: 'bundle'; packs: PackSummary[] }
	| {
			kind: 'opened';
			pack: PackState;
			/** Clips this browser cannot decode: they stay in the pack, without preview or editing. */
			undecodable: number;
			keptRingtones: number;
			keptAnimations: number;
	  };

/** The draft of a pack that keeps the original's ID and saves as an update (one patch above its version). */
export function asUpdate(spec: PackSpec, pack: PackState): PackState {
	if (!pack.origin) return pack;
	return {
		...pack,
		id: pack.origin.id,
		version: spec.authoring.versionAfter(pack.origin.version, pack.origin.version)
	};
}

/** The draft of a pack saved as a copy: a new derived ID (installs next to the original) and version 1.0.0. */
export function asCopy(pack: PackState): PackState {
	return { ...pack, id: null, version: FIRST_VERSION };
}

/**
 * Opens a pack archive. A bundle without `root` answers with its packs; with `root`, the pack in that folder opens.
 * By default the draft saves as an update of the original ([asUpdate]).
 */
export async function openPackArchive(
	spec: PackSpec,
	bytes: Uint8Array,
	root?: string
): Promise<OpenOutcome> {
	const opened = spec.authoring.openPack(bytes, root ?? null);
	if (!opened.result.valid) return { kind: 'invalid', result: opened.result };
	const draft = opened.draft;
	if (!draft) return { kind: 'bundle', packs: opened.result.packs };

	const files = unzipSync(bytes);
	const read = (path: string) => {
		const file = files[path];
		if (!file) throw new Error(`Missing ${path}`);
		return file;
	};
	const kept = new Map<string, KeptFile>();
	const keep = (path: string): KeptFile => {
		let file = kept.get(path);
		if (!file) {
			file = { id: uid('file'), bytes: read(path), extension: extensionOf(path) };
			kept.set(path, file);
		}
		return file;
	};

	let undecodable = 0;
	const characters: CharacterState[] = [];
	for (const c of draft.characters) {
		const clips: ClipState[] = [];
		for (const clip of c.clips) {
			const clipBytes = read(clip.file.key);
			const encoded: EncodedClip = {
				bytes: clipBytes,
				extension: clip.file.extension,
				type: mimeOf(clip.file.extension)
			};
			let samples: Float32Array | null = null;
			try {
				samples = await decodeToMono(clipBytes.slice().buffer);
			} catch {
				undecodable += 1;
			}
			const durationMs = samples
				? (samples.length * 1000) / spec.authoring.targets.sampleRate
				: (opened.durations[clip.file.key] ?? 0);
			clips.push({
				uid: uid('clip'),
				label: clip.label ?? '',
				weight: clip.weight ?? 1,
				encoded,
				samples,
				durationMs,
				source: samples,
				edit: { startMs: 0, endMs: Math.floor(durationMs), trimSilence: false, normalize: false },
				when: clip.when ?? null,
				oneIn: clip.oneIn ?? null
			});
		}
		const haptic: HapticState =
			c.haptic?.mode === 'custom'
				? { mode: 'custom', events: c.haptic.events ?? [] }
				: { mode: c.haptic?.mode === 'none' ? 'none' : 'auto', events: [] };
		characters.push({
			uid: uid('char'),
			id: c.id,
			name: c.name,
			sprite: c.sprite ? imageFile(read(c.sprite.key), c.sprite.extension) : null,
			seedColor: c.seedColor,
			clips,
			haptic,
			description: c.description ?? null,
			tags: c.tags ?? [],
			spriteAnimated: c.spriteAnimated ? keep(c.spriteAnimated.key) : null,
			ringtones: (c.ringtones ?? []).map((r) => ({ file: keep(r.file.key), entry: r.entry }))
		});
	}

	const pack: PackState = {
		idSuffix: draft.idSuffix,
		id: draft.id,
		version: draft.version ?? FIRST_VERSION,
		name: draft.name,
		author: draft.author,
		license: draft.license,
		description: draft.description ?? '',
		cover: draft.cover ? imageFile(read(draft.cover.key), draft.cover.extension) : null,
		characters,
		homepage: draft.homepage ?? null,
		tags: draft.tags ?? [],
		origin: draft.id
			? { id: draft.id, name: draft.name, version: draft.version ?? FIRST_VERSION }
			: null
	};
	return {
		kind: 'opened',
		pack: asUpdate(spec, pack),
		undecodable,
		keptRingtones: characters.reduce((n, c) => n + c.ringtones.length, 0),
		keptAnimations: characters.filter((c) => c.spriteAnimated).length
	};
}
