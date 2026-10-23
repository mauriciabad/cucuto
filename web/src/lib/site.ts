export const SITE_URL = 'https://cucuto.mauri.app';
export const SITE_NAME = 'cucuto';
export const PITCH = 'Give each contact a voice.';
export const DESCRIPTION =
	'cucuto plays a different character’s sound and vibration for each WhatsApp contact, so you know who wrote before you look. For rooted Android 17 with LSPosed.';

export const REPO_URL = 'https://github.com/mauriciabad/cucuto';
/** Until the release-driven build lands (#39), the button points to the latest release page. */
export const LATEST_RELEASE_URL = `${REPO_URL}/releases/latest`;
export const LICENSE_URL = `${REPO_URL}/blob/main/LICENSE`;
export const PACK_FORMAT_URL = `${REPO_URL}/blob/main/docs/pack-format.md`;
export const ISSUES_URL = `${REPO_URL}/issues`;

export const REQUIREMENTS = {
	android: 'Android 17',
	libxposedApi: 102
} as const;
