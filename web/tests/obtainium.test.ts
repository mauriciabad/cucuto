/// <reference types="bun" />
// The Install page's Obtainium link (ADR 0046): a config Obtainium can read, filters that pick exactly the app's APK
// from release.yml's assets, and the release certificate in Obtainium's format.
import { describe, expect, test } from 'bun:test';
import {
	OBTAINIUM_APK_FILTER,
	OBTAINIUM_CONFIG,
	OBTAINIUM_LINK,
	OBTAINIUM_TITLE_FILTER,
	RELEASE_CERT_COLONS
} from '../src/lib/obtainium.ts';
import { RELEASE_CERT_SHA256, REPO_URL } from '../src/lib/site.ts';

describe('Obtainium link', () => {
	test('decodes to the config, with the source settings as a JSON string', () => {
		const prefix = 'obtainium://app/';
		expect(OBTAINIUM_LINK.startsWith(prefix)).toBe(true);
		const config = JSON.parse(decodeURIComponent(OBTAINIUM_LINK.slice(prefix.length)));
		expect(config).toEqual(OBTAINIUM_CONFIG);
		expect(config).toMatchObject({ id: 'app.mauri.cucuto', url: REPO_URL, name: 'Cucuto' });
		const settings = JSON.parse(config.additionalSettings);
		expect(settings).toMatchObject({
			includePrereleases: false,
			fallbackToOlderReleases: true,
			apkFilterRegEx: OBTAINIUM_APK_FILTER,
			filterReleaseTitlesByRegEx: OBTAINIUM_TITLE_FILTER,
			allowedSigningCertHashes: RELEASE_CERT_COLONS
		});
	});

	test('the APK filter matches the app APK only', () => {
		const apk = new RegExp(OBTAINIUM_APK_FILTER);
		expect(apk.test('cucuto-0.1.2.apk')).toBe(true);
		expect(apk.test('cucuto-12.345.6.apk')).toBe(true);
		expect(apk.test('cucuto-0.1.2.apk.sha256')).toBe(false);
		expect(apk.test('chiptune-critters-1.0.0.soundpack.zip')).toBe(false);
	});

	test('the title filter keeps app releases and skips pack releases', () => {
		const title = new RegExp(OBTAINIUM_TITLE_FILTER);
		expect(title.test('Cucuto 0.1.2')).toBe(true);
		expect(title.test('Demo packs 1')).toBe(false);
	});

	test('the certificate is the release pin in colon form', () => {
		expect(RELEASE_CERT_SHA256).toMatch(/^[0-9a-f]{64}$/);
		expect(RELEASE_CERT_COLONS).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
		expect(RELEASE_CERT_COLONS.replaceAll(':', '').toLowerCase()).toBe(RELEASE_CERT_SHA256);
	});
});
