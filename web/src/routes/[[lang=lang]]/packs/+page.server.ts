import { repositoryIndex } from '#lib/server/build-data.js';
import { resolveRepositoryUrl, type CharacterPreview } from '#lib/repository.js';
import type { PageServerLoad } from './$types';

// The gallery is the site's own pack repository (/repo/index.json, fetched from the newest packs-v* release by
// scripts/build-data.ts), rendered at build time. Preview URLs in the index are relative to the index URL.
export const load: PageServerLoad = () => {
	const index = repositoryIndex();
	const resolve = (url?: string) => (url ? resolveRepositoryUrl(url) : undefined);
	const characters = (list: CharacterPreview[] = []) =>
		list.map((c) => ({ ...c, sprite: resolve(c.sprite), sample: resolve(c.sample) }));

	const packs = index.packs.map((entry) => ({
		id: entry.id,
		type: entry.type,
		name: entry.name,
		version: entry.version,
		author: entry.author,
		license: entry.license,
		description: entry.description,
		cover: resolve(entry.cover),
		characters: characters(entry.characters),
		packs: (entry.packs ?? []).map((p) => ({ id: p.id, name: p.name })),
		download: { ...entry.download, url: resolveRepositoryUrl(entry.download.url) }
	}));
	// Single packs first (they have samples to play), then bundles.
	packs.sort((a, b) => Number(a.type === 'bundle') - Number(b.type === 'bundle'));
	return { repositoryName: index.name, packs };
};
