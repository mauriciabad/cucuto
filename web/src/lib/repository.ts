// Shape of a pack repository index (`repository.json`), as described in docs/project-brief.md ("Pack repositories").
// The JSON Schema in spec/ is the contract; these types mirror it for the website gallery.

export interface RepositoryIndex {
	formatVersion: 1;
	name: string;
	homepage?: string;
	packs: RepositoryEntry[];
}

export interface RepositoryEntry {
	id: string;
	type: 'pack' | 'bundle';
	name: string;
	version: string;
	author: string;
	license: string;
	tags?: string[];
	/** Relative to the index URL. */
	cover?: string;
	characters?: Array<{ id: string; name: string; sprite?: string; sample?: string }>;
	download: { url: string; size: number; sha256: string };
}

/** Where the site publishes its own index, which the app adds as the default repository. */
export const DEFAULT_REPOSITORY_PATH = '/repo/index.json';
