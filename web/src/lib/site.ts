export const SITE_URL = 'https://cucuto.mauri.app';
export const SITE_NAME = 'Cucuto';
export const PITCH = 'Give each contact a voice.';
export const DESCRIPTION =
	'Cucuto plays a different character’s sound and vibration for each WhatsApp contact, so you know who wrote before you look. For rooted Android 17 with LSPosed.';

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
export const ISSUES_URL = `${REPO_URL}/issues`;

export const REQUIREMENTS = {
	android: 'Android 17',
	libxposedApi: 102
} as const;
