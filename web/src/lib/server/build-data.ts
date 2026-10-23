// Build-time data written by scripts/build-data.ts (which runs before `vite build` and `vite dev`). Read with fs
// in server load functions, so type-checking never depends on the generated files; everything is prerendered.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { RepositoryIndex } from '#lib/repository.js';
import type { AppRelease } from '#lib/site.js';

function readJson<T>(file: string, fallback: T): T {
	const path = join(process.cwd(), file);
	if (!existsSync(path)) return fallback;
	return JSON.parse(readFileSync(path, 'utf8')) as T;
}

/** The latest app release, or null before the first one ("coming soon"). */
export function appRelease(): AppRelease | null {
	return readJson<AppRelease | null>('.build-data/app-release.json', null);
}

/** The site's own pack repository, served at /repo/index.json. */
export function repositoryIndex(): RepositoryIndex {
	return readJson<RepositoryIndex>('static/repo/index.json', {
		formatVersion: 1,
		name: 'Cucuto demo packs',
		packs: []
	});
}
