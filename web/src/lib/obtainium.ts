// Obtainium (https://github.com/ImranR98/Obtainium) installs and updates apps straight from their GitHub releases, on
// the phone. Cucuto has no update checker of its own (minimum intrusion), so the Install page offers Obtainium's
// deep link with Cucuto's settings filled in (ADR 0046). Format: https://wiki.obtainium.imranr.dev/deep_links/ — the
// bare `obtainium://app/<url-encoded JSON>` scheme, not the apps.obtainium.imranr.dev redirect page, which loads
// analytics and ads (the site promises no third-party requests).
import { RELEASE_CERT_SHA256, REPO_URL } from '#lib/site.js';

/** Release assets are `cucuto-<version>.apk` (release.yml); the `.apk.sha256` next to it must not match. */
export const OBTAINIUM_APK_FILTER = String.raw`^cucuto-\d+\.\d+\.\d+\.apk$`;

/** App releases are titled `Cucuto <version>`; this skips the demo pack releases (`Demo packs 1`), which have no APK. */
export const OBTAINIUM_TITLE_FILTER = '^Cucuto ';

/** The certificate as Obtainium shows it: uppercase hex pairs separated by colons. */
export const RELEASE_CERT_COLONS = RELEASE_CERT_SHA256.toUpperCase().match(/../g)!.join(':');

/** Obtainium's app config: the fields its exports and the crowdsourced configs use. */
export const OBTAINIUM_CONFIG = {
	id: 'app.mauri.cucuto',
	url: REPO_URL,
	author: 'mauriciabad',
	name: 'Cucuto',
	// Obtainium stores the source settings as a JSON string inside the config.
	additionalSettings: JSON.stringify({
		includePrereleases: false,
		fallbackToOlderReleases: true,
		filterReleaseTitlesByRegEx: OBTAINIUM_TITLE_FILTER,
		apkFilterRegEx: OBTAINIUM_APK_FILTER,
		// Refuse an APK signed by any other key (the same pin as release.yml's RELEASE_CERT_SHA256).
		allowedSigningCertHashes: RELEASE_CERT_COLONS
	})
};

/** Opens Obtainium's "add app" screen with Cucuto's config; does nothing where Obtainium isn't installed. */
export const OBTAINIUM_LINK = `obtainium://app/${encodeURIComponent(JSON.stringify(OBTAINIUM_CONFIG))}`;

/** Where to get Obtainium itself. */
export const OBTAINIUM_URL = 'https://github.com/ImranR98/Obtainium';
