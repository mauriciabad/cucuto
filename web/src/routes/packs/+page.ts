import type { RepositoryEntry } from '#lib/repository.js';
import type { PageLoad } from './$types';

export const load: PageLoad = () => {
	// The build will run `packer index` over the demo packs and publish /repo/index.json (#39); the gallery then
	// reads that index at build time. Until then there are no packs to list.
	const packs: RepositoryEntry[] = [];
	return { packs };
};
