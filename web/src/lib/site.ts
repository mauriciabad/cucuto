// Language-independent facts about the site. Page copy lives in src/lib/i18n.
export const SITE_URL = 'https://cucuto.mauri.app';
export const SITE_NAME = 'Cucuto';

export const REPO_URL = 'https://github.com/mauriciabad/cucuto';
/** All releases: the app (`v*`) and the demo packs (`packs-v*`). */
export const RELEASES_URL = `${REPO_URL}/releases`;

/** The latest app release, read from the GitHub Releases API at build time (scripts/build-data.ts). */
export interface AppRelease {
	version: string;
	tag: string;
	releaseUrl: string;
	publishedAt: string | null;
	apk: { name: string; url: string; size: number };
}
export const LICENSE_URL = `${REPO_URL}/blob/main/LICENSE`;
export const PACK_FORMAT_URL = `${REPO_URL}/blob/main/docs/pack-format.md`;
export const PACKER_README_URL = `${REPO_URL}/blob/main/tools/packer/README.md`;
export const ISSUES_URL = `${REPO_URL}/issues`;

/** When the privacy page last changed in substance (shown on the page). */
export const PRIVACY_UPDATED = '2026-10-24';
