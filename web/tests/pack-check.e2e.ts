// The in-browser pack checker on /authors/ (shared/pack-spec compiled to JavaScript, ADR 0022), in a real browser
// under the site's Content Security Policy. tests/pack-spec.test.ts runs every golden fixture through the same module.
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const fixtures = join(import.meta.dirname, '..', '..', 'spec', 'fixtures');
const built = existsSync(
	join(import.meta.dirname, '..', 'src', 'lib', 'pack-spec', 'generated', 'pack-spec.mjs')
);

test.beforeEach(() => {
	test.skip(!built, 'Pack validator not built (./gradlew syncWebPackSpec in shared/pack-spec)');
});

test('checks packs on the device and reports the spec error codes', async ({ page }) => {
	const errors: string[] = [];
	page.on('console', (message) => {
		if (message.type() === 'error') errors.push(message.text());
	});
	page.on('pageerror', (error) => errors.push(error.message));

	await page.goto('/authors/');
	const input = page.locator('#check ~ .pack-check input[type=file]');
	await input.setInputFiles([
		join(fixtures, 'valid', 'minimal-pack.soundpack.zip'),
		join(fixtures, 'invalid', 'archive-crc-mismatch.soundpack.zip')
	]);

	const valid = page.locator('.report', { hasText: 'minimal-pack.soundpack.zip' });
	await expect(valid).toContainText('Ready to import');
	const invalid = page.locator('.report', { hasText: 'archive-crc-mismatch.soundpack.zip' });
	await expect(invalid).toContainText('The app would not import this file');
	await expect(invalid).toContainText('ARCHIVE_UNREADABLE');

	const axe = await new AxeBuilder({ page }).include('.pack-check').analyze();
	expect(axe.violations).toEqual([]);
	expect(errors).toEqual([]);
});
