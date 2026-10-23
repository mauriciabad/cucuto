// Release-driven data (#39) and generated images (#40): the pack repository the site serves at /repo/index.json,
// the Packs gallery built from it, the APK button and the <picture> sources.
import { expect, test } from '@playwright/test';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const spec = join(import.meta.dirname, '..', '..', 'spec');
const readJson = (file: string) => JSON.parse(readFileSync(file, 'utf8'));

const ADD_TO_APP = 'cucuto://repo/add?url=https://cucuto.mauri.app/repo/index.json';

test('/repo/index.json validates against spec/repository.schema.json', async ({ request }) => {
	const response = await request.get('/repo/index.json');
	expect(response.ok()).toBe(true);
	const index = await response.json();

	const ajv = new Ajv2020({ allErrors: true });
	ajv.addSchema(readJson(join(spec, 'common.schema.json')));
	const validate = ajv.compile(readJson(join(spec, 'repository.schema.json')));
	expect(validate(index), JSON.stringify(validate.errors, null, 2)).toBe(true);

	// packer's own file name is served too, so the folder URL works as a repository as well.
	expect(await (await request.get('/repo/repository.json')).json()).toEqual(index);
});

test('every preview in the index is served', async ({ request }) => {
	const index = await (await request.get('/repo/index.json')).json();
	const base = new URL('/repo/index.json', 'http://localhost');
	const previews = new Set<string>();
	const add = (url?: string) => url && previews.add(new URL(url, base).pathname);
	type Character = { sprite?: string; sample?: string };
	type Entry = { cover?: string; characters?: Character[]; packs?: Entry[] };
	const visit = (entry: Entry) => {
		add(entry.cover);
		entry.characters?.forEach((c) => (add(c.sprite), add(c.sample)));
		entry.packs?.forEach(visit);
	};
	index.packs.forEach(visit);
	for (const path of previews) {
		expect((await request.get(path)).status(), path).toBe(200);
	}
});

test('the Packs page lists every pack in the index', async ({ page, request }) => {
	const index = await (await request.get('/repo/index.json')).json();
	await page.goto('/packs/');
	const cards = page.locator('.gallery > li.pack.card');
	await expect(cards).toHaveCount(index.packs.length);
	test.skip(index.packs.length === 0, 'No packs-v* release yet: the page shows the empty state');

	for (const entry of index.packs) {
		const card = cards.filter({
			has: page.getByRole('heading', { name: entry.name, exact: true })
		});
		await expect(card.getByRole('link', { name: /Download .*\.soundpack\.zip/ })).toHaveAttribute(
			'href',
			new URL(entry.download.url, 'https://cucuto.mauri.app/repo/index.json').href.replace(
				'https://cucuto.mauri.app',
				''
			)
		);
		await expect(card.getByRole('link', { name: 'Add to app' })).toHaveAttribute(
			'href',
			ADD_TO_APP
		);
		await expect(card).toContainText(entry.license);
		for (const character of entry.characters ?? []) {
			if (character.sample) {
				await expect(
					card.getByRole('button', { name: `Play ${character.name}’s sample` })
				).toBeVisible();
			}
		}
	}

	const play = page.getByRole('button', { name: /^Play .*’s sample$/ }).first();
	await play.click();
	await expect(page.getByRole('button', { name: /^Stop .*’s sample$/ })).toHaveAttribute(
		'aria-pressed',
		'true'
	);
});

test('Download APK uses the latest app release, or says it is coming soon', async ({ page }) => {
	const file = join(import.meta.dirname, '..', '.build-data', 'app-release.json');
	const release = existsSync(file) ? readJson(file) : null;
	await page.goto('/');
	if (release) {
		await expect(page.getByRole('link', { name: 'Download APK' })).toHaveAttribute(
			'href',
			release.apk.url
		);
		await expect(page.locator('main')).toContainText(`Version ${release.version}`);
	} else {
		await expect(page.getByRole('link', { name: 'APK coming soon' })).toHaveAttribute(
			'href',
			'https://github.com/mauriciabad/cucuto/releases'
		);
	}
});

test('raster images are served as <picture> with JPEG XL first', async ({ page, request }) => {
	await page.goto('/install/');
	const picture = page.locator('picture', { has: page.getByAltText('The Cucuto app icon') });
	const types = await picture
		.locator('source')
		.evaluateAll((sources) => sources.map((s) => s.getAttribute('type')));
	expect(types).toEqual(['image/jxl', 'image/avif']);
	const urls = await picture
		.locator('source, img')
		.evaluateAll((elements) =>
			elements.map((e) => e.getAttribute('srcset') ?? e.getAttribute('src') ?? '')
		);
	expect(urls[2]).toMatch(/\.png$/);
	for (const url of urls) expect((await request.get(url)).status(), url).toBe(200);
	await expect(page.getByAltText('The Cucuto app icon')).toBeVisible();
});
