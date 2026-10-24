// "Host your own repository" (#191): `packer index` in the browser. shared/pack-spec's RepositoryIndexer (the code
// packer runs) makes each entry and its previews; this file adds what is platform glue: SHA-256 with Web Crypto,
// safe file names and the zip of the finished folder. Entirely client-side: nothing is uploaded.
import { unzipSync } from 'fflate';
import { zipEntries } from './pack.js';
import type { PackSpec, PackSpecResult } from '#lib/pack-spec/core.js';

/** A pack or bundle file added to the repository, already checked. */
export interface IndexedFile {
	uid: string;
	/** The file name the index lists: safe in a URL, ends in `.zip`, unique in the folder. */
	fileName: string;
	bytes: Uint8Array;
	sha256: string;
	result: PackSpecResult;
}

export interface RepositoryMeta {
	name: string;
	description: string;
	homepage: string;
	/** Null when the packs go in the folder's `packs/`. */
	baseUrl: string | null;
}

export type RepositoryOutcome =
	| { kind: 'invalid'; result: PackSpecResult }
	| { kind: 'built'; json: string; entries: number; zip: Uint8Array };

/** Lowercase hex SHA-256 (what `download.sha256` holds). */
export async function sha256Hex(bytes: Uint8Array): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * A file name that works unescaped in a URL: letters, digits, dots, hyphens and underscores, ending in `.zip`
 * (ADR 0007), and not one of `taken`.
 */
export function safeFileName(name: string, taken: Iterable<string>): string {
	const used = new Set(taken);
	let base = name
		.replace(/\.zip$/i, '')
		.replace(/[^A-Za-z0-9._-]+/g, '-')
		.replace(/^[-.]+|[-.]+$/g, '');
	if (base === '') base = 'pack';
	if (!/\.soundpack$/i.test(base)) base += '.soundpack';
	let candidate = `${base}.zip`;
	for (let n = 2; used.has(candidate); n++) {
		candidate = `${base.replace(/\.soundpack$/i, '')}-${n}.soundpack.zip`;
	}
	return candidate;
}

/** Whether `url` is an absolute `https://` address (what the repository format accepts for absolute URLs). */
export function isHttpsUrl(url: string): boolean {
	try {
		const parsed = new URL(url.trim());
		return parsed.protocol === 'https:' && parsed.hostname !== '';
	} catch {
		return false;
	}
}

/** The deep link that asks the app to add a repository (ADR 0029). */
export const addToAppLink = (address: string) =>
	`cucuto://repo/add?url=${encodeURIComponent(address.trim())}`;

const blank = (text: string) => (text.trim() === '' ? null : text.trim());

/**
 * Indexes every file and zips the folder: `repository.json`, `previews/…` and, unless the packs live at
 * `meta.baseUrl`, `packs/<file>`. The index is checked with the repository schema first.
 */
export function buildRepository(
	spec: PackSpec,
	meta: RepositoryMeta,
	files: IndexedFile[]
): RepositoryOutcome {
	const tools = spec.authoring.repository;
	const entries: Record<string, unknown>[] = [];
	const byUrl = new Map<
		string,
		{ file: IndexedFile; previews: { path: string; source: string }[] }
	>();
	for (const file of files) {
		const url = tools.downloadUrl(file.fileName, meta.baseUrl);
		const indexed = tools.entry(file.bytes, url, file.sha256);
		if (!indexed.entry) return { kind: 'invalid', result: indexed.result };
		entries.push(indexed.entry);
		byUrl.set(url, { file, previews: indexed.previews });
	}
	const index = tools.index(
		{ name: meta.name, description: blank(meta.description), homepage: blank(meta.homepage) },
		entries
	);
	if (!index.json) return { kind: 'invalid', result: index.result };
	// Only the files the index lists (the highest version of each ID) and their previews go in the folder.
	const listed = (JSON.parse(index.json) as { packs: { download: { url: string } }[] }).packs;
	const folder = new Map<string, Uint8Array>();
	for (const entry of listed) {
		const item = byUrl.get(entry.download.url);
		if (!item) continue;
		const archive = unzipSync(item.file.bytes);
		for (const preview of item.previews) {
			const bytes = archive[preview.source];
			if (bytes) folder.set(preview.path, bytes);
		}
		if (!meta.baseUrl) folder.set(`packs/${item.file.fileName}`, item.file.bytes);
	}
	const paths = [...folder.keys()].sort();
	const zip = zipEntries([
		['repository.json', new TextEncoder().encode(index.json)],
		...paths.map((path): [string, Uint8Array] => [path, folder.get(path) as Uint8Array])
	]);
	return { kind: 'built', json: index.json, entries: listed.length, zip };
}
