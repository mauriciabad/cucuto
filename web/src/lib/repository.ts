// Shape of a pack repository index (`repository.json`), as described in docs/project-brief.md ("Pack repositories").
// The JSON Schema in spec/repository.schema.json is the contract; these types mirror it for the website gallery.
import type { HapticJson } from '#lib/pack-spec/core.js';

export interface RepositoryIndex {
	formatVersion: 1;
	name: string;
	description?: string;
	homepage?: string;
	generatedAt?: string;
	packs: RepositoryEntry[];
}

export interface CharacterPreview {
	id: string;
	name: string;
	/** Relative to the index URL. */
	sprite?: string;
	/** A short preview clip, relative to the index URL. */
	sample?: string;
	seedColor?: string;
	/**
	 * The character's haptic from pack.json (ADR 0069). The app plays it with the sample; browsers can't play rich
	 * haptics, so the Packs page draws it instead (HapticPreview, #301). Absent means unknown: nothing is drawn.
	 */
	'x-haptic'?: HapticJson;
}

export interface BundledPack {
	id: string;
	name: string;
	version: string;
	license?: string;
	cover?: string;
	characters?: CharacterPreview[];
}

export interface RepositoryEntry {
	id: string;
	type: 'pack' | 'bundle';
	packFormatVersion?: number;
	name: string;
	version: string;
	author?: string;
	license?: string;
	description?: string;
	tags?: string[];
	/** Relative to the index URL. */
	cover?: string;
	/** Packs only. */
	characters?: CharacterPreview[];
	/** Bundles only. */
	packs?: BundledPack[];
	sizeUnpacked?: number;
	download: { url: string; size: number; sha256: string };
}

/** Where the site publishes its own index, which the app adds as the default repository. */
export const DEFAULT_REPOSITORY_PATH = '/repo/index.json';
export const DEFAULT_REPOSITORY_URL = `https://cucuto.mauri.app${DEFAULT_REPOSITORY_PATH}`;
/** Deep link that asks the app to add a repository (brief: "tap 'Add to app' on a website"). */
export const ADD_TO_APP_URL = `cucuto://repo/add?url=${DEFAULT_REPOSITORY_URL}`;

/** Resolves a URL from the index against the index URL; same-site results become root-relative paths. */
export function resolveRepositoryUrl(url: string, indexUrl = DEFAULT_REPOSITORY_URL): string {
	const resolved = new URL(url, indexUrl);
	const index = new URL(indexUrl);
	return resolved.origin === index.origin ? resolved.pathname : resolved.href;
}
