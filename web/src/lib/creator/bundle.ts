// Bundles in the browser (#191): finished packs combined into one archive with `bundle.json` at its root, laid out by
// shared/pack-spec's BundleAssembler exactly like `packer bundle` (each pack in `packs/<pack id>/`, an optional
// `cover.<ext>`), zipped like the creator's packs and checked with the same validator before download.
import { unzipSync } from 'fflate';
import type { EncodedImage } from './image.js';
import { zipEntries } from './pack.js';
import type {
	BundleDraftInput,
	BundleProblem,
	PackSpec,
	PackSpecResult,
	PackSummary
} from '#lib/pack-spec/core.js';

/** A pack file added to the bundle, already checked. */
export interface BundledFile {
	uid: string;
	fileName: string;
	bytes: Uint8Array;
	summary: PackSummary;
}

export type ReadOutcome =
	| { kind: 'pack'; summary: PackSummary }
	| { kind: 'bundle' }
	| { kind: 'invalid'; result: PackSpecResult };

/** Checks a file someone adds: only valid single packs go in a bundle (a bundle cannot hold another bundle). */
export function readPack(spec: PackSpec, bytes: Uint8Array): ReadOutcome {
	const result = spec.validateArchive(bytes);
	if (!result.valid) return { kind: 'invalid', result };
	if (result.kind === 'bundle') return { kind: 'bundle' };
	return { kind: 'pack', summary: result.packs[0] };
}

export interface BundleMeta {
	id: string;
	name: string;
	version: string;
	author: string;
	license: string;
	description: string;
}

export type BundleOutcome =
	| { kind: 'incomplete'; problems: BundleProblem[] }
	| { kind: 'built'; fileName: string; bytes: Uint8Array; result: PackSpecResult };

const blank = (text: string) => (text.trim() === '' ? null : text);

/** Lays out, zips and validates the bundle. */
export function buildBundle(
	spec: PackSpec,
	meta: BundleMeta,
	packs: BundledFile[],
	cover: EncodedImage | null
): BundleOutcome {
	const draft: BundleDraftInput = {
		id: meta.id.trim(),
		name: meta.name,
		version: meta.version,
		author: blank(meta.author),
		license: blank(meta.license),
		description: blank(meta.description),
		cover: cover ? { key: 'cover', extension: cover.extension } : null,
		packIds: packs.map((p) => p.summary.id)
	};
	const assembly = spec.authoring.assembleBundle(draft);
	if (assembly.problems.length > 0 || assembly.manifestJson === null) {
		return { kind: 'incomplete', problems: assembly.problems };
	}
	const rest: [string, Uint8Array][] = [];
	if (cover && assembly.coverPath) rest.push([assembly.coverPath, cover.bytes]);
	packs.forEach((p, i) => {
		const root = assembly.roots[i];
		for (const [path, bytes] of Object.entries(unzipSync(p.bytes))) {
			if (!path.endsWith('/')) rest.push([`${root}/${path}`, bytes]);
		}
	});
	// `packer bundle` order: the manifest first, then every entry sorted by path.
	rest.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
	const bytes = zipEntries([
		['bundle.json', new TextEncoder().encode(assembly.manifestJson)],
		...rest
	]);
	return {
		kind: 'built',
		fileName: assembly.fileName ?? 'bundle.soundpack.zip',
		bytes,
		result: spec.validateArchive(bytes)
	};
}
