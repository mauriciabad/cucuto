// The Create page's follow-ups (#191) in a real browser under the site's CSP: drafts that survive a reload, opening
// an existing pack to edit it, a cover, a custom vibration with "Copy for AI" and "Paste pattern", the bundle tool
// and the repository helper. Every file the page offers is checked with the same validator as packer and the app.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { unzipSync, zipSync } from 'fflate';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { packSpec, type PackSpecModule } from '../src/lib/pack-spec/core.ts';

const fixtures = join(import.meta.dirname, '..', '..', 'spec', 'fixtures', 'valid');
const generated = join(
	import.meta.dirname,
	'..',
	'src',
	'lib',
	'pack-spec',
	'generated',
	'pack-spec.mjs'
);
const built = existsSync(generated);

test.beforeEach(async () => {
	test.setTimeout(90_000);
	test.skip(!built, 'Pack validator not built (./gradlew syncWebPackSpec in shared/pack-spec)');
});

async function validator() {
	return packSpec((await import(generated)) as PackSpecModule);
}

/** A fixture folder as a zip, the way a pack file arrives. */
function zipFolder(folder: string): Buffer {
	const root = join(fixtures, folder);
	const files: Record<string, Uint8Array> = {};
	const walk = (dir: string) => {
		for (const name of readdirSync(dir)) {
			const path = join(dir, name);
			if (statSync(path).isDirectory()) walk(path);
			else files[relative(root, path).split('\\').join('/')] = new Uint8Array(readFileSync(path));
		}
	};
	walk(root);
	return Buffer.from(zipSync(files));
}

const zipFile = (name: string, folder: string) => ({
	name,
	mimeType: 'application/zip',
	buffer: zipFolder(folder)
});
const fixtureFile = (name: string) => ({
	name,
	mimeType: 'application/zip',
	buffer: readFileSync(join(fixtures, name))
});

function trackErrors(page: Page) {
	const errors: string[] = [];
	page.on('console', (message) => {
		if (message.type() === 'error') errors.push(message.text());
	});
	page.on('pageerror', (error) => errors.push(error.message));
	return errors;
}

const character = (page: Page, n: number) => page.locator('article.character').nth(n);

async function openCreator(page: Page, path = '/create/') {
	await page.goto(path);
	await expect(character(page, 0)).toBeVisible();
}

async function frame(page: Page, input: ReturnType<Page['locator']>, file: string) {
	await input.setInputFiles(file);
	const dialog = page.getByRole('dialog', { name: 'Frame the picture' });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('button', { name: 'Use picture' }).click();
	await expect(dialog).toBeHidden();
}

async function download(page: Page, button: string) {
	const [file] = await Promise.all([
		page.waitForEvent('download'),
		page.getByRole('button', { name: button }).click()
	]);
	return {
		name: file.suggestedFilename(),
		bytes: new Uint8Array(readFileSync(await file.path()))
	};
}

test('a draft survives a reload and can be resumed or deleted', async ({ page }) => {
	const errors = trackErrors(page);
	await openCreator(page);
	await page.getByLabel('Pack name').fill('Night owls');
	await character(page, 0).getByLabel('Name').fill('Barn owl');
	await expect(page.getByText('Draft saved on this device')).toBeVisible();

	await page.reload();
	await expect(page.getByLabel('Pack name')).toHaveValue('Night owls');
	await expect(character(page, 0).getByLabel('Name')).toHaveValue('Barn owl');

	// A new pack: the first one is listed, resumes, and is deleted after a confirmation.
	await page.getByRole('button', { name: 'Start a new pack' }).click();
	await expect(page.getByLabel('Pack name')).toHaveValue('');
	const drafts = page.locator('.draft-list li');
	await expect(drafts).toHaveCount(1);
	await expect(drafts.first()).toContainText('Night owls');
	await page.getByRole('button', { name: 'Resume: Night owls' }).click();
	await expect(page.getByLabel('Pack name')).toHaveValue('Night owls');

	await page.getByRole('button', { name: 'Start a new pack' }).click();
	await page.getByRole('button', { name: 'Delete: Night owls' }).click();
	await expect(page.getByText('Delete “Night owls”? This cannot be undone.')).toBeVisible();
	await page.getByRole('button', { name: 'Delete', exact: true }).click();
	await expect(drafts).toHaveCount(0);
	expect(errors).toEqual([]);
});

test('opens an existing pack and keeps everything the format allows', async ({ page }) => {
	const errors = trackErrors(page);
	await openCreator(page);
	await page
		.locator('input[type=file][accept^=".zip"]')
		.setInputFiles(zipFile('full.soundpack.zip', 'full-pack'));
	await expect(page.getByRole('heading', { name: 'Editing Full pack 2.1.0-beta.1' })).toBeVisible();
	await expect(
		page.getByText('Saves as an update: the same pack ID, version 2.1.0.')
	).toBeVisible();
	await expect(
		page.getByText(/^Animated pictures are kept from the original.*: 1\.$/)
	).toBeVisible();
	// Ringtone versions are listed for editing, with their conditions in plain words.
	await expect(character(page, 1).locator('.ringtones li')).toHaveCount(5);
	await expect(character(page, 1).locator('.ringtones li .condition').nth(1)).toHaveText(
		'At night · Autumn and Winter'
	);
	await expect(page.getByLabel('Pack name')).toHaveValue('Full pack');
	await expect(page.locator('article.character')).toHaveCount(3);
	await expect(page.locator('.pack-id code')).toHaveText('app.mauri.fixtures.full');
	await expect(character(page, 0).getByRole('radio', { name: 'Custom pattern' })).toBeChecked();
	await expect(character(page, 1).getByRole('radio', { name: 'No vibration' })).toBeChecked();

	await page.getByRole('button', { name: 'Check pack' }).click();
	await expect(page.locator('.report')).toContainText('Ready to import');
	const file = await download(page, 'Download pack');
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.errorCodes).toEqual([]);
	const entries = unzipSync(file.bytes);
	const manifest = JSON.parse(new TextDecoder().decode(entries['pack.json']));
	const original = JSON.parse(readFileSync(join(fixtures, 'full-pack/pack.json'), 'utf8'));
	expect(manifest).toMatchObject({
		id: 'app.mauri.fixtures.full',
		version: '2.1.0',
		homepage: original.homepage,
		tags: original.tags,
		cover: 'cover.svg'
	});
	expect(manifest.characters.map((c: { id: string }) => c.id)).toEqual(['robin', 'song', 'ringer']);
	expect(manifest.characters[0].haptic).toEqual(original.characters[0].haptic);
	expect(manifest.characters[0].spriteAnimated).toBe('characters/robin/sprite-anim.gif');
	expect(manifest.characters[0].clips[1].when).toEqual(original.characters[0].clips[1].when);
	// Special clips (#120) survive editing: `oneIn` and the `firstOfDay`, `burst` and `date` conditions.
	const rules = (clips: { when?: unknown; oneIn?: number }[]) =>
		clips.map((c) => ({ when: c.when, oneIn: c.oneIn }));
	expect(rules(manifest.characters[0].clips)).toEqual(rules(original.characters[0].clips));
	// Ringtone entries come back as they were, apart from their file names.
	const withoutFile = (entries: Record<string, unknown>[]) =>
		entries.map((entry) => ({ ...entry, file: undefined }));
	expect(withoutFile(manifest.characters[1].ringtones)).toEqual(
		withoutFile(original.characters[1].ringtones)
	);
	expect(manifest.characters[2].ringtones).toHaveLength(1);

	// Saved as a copy instead: a new derived ID and version 1.0.0.
	await page.getByRole('checkbox', { name: /Save as a new pack instead/ }).check();
	await expect(page.locator('.pack-id code')).toHaveText(
		/^local\.cucuto-fixtures\.full-pack-[a-z0-9]{6}$/
	);
	await expect(page.getByRole('textbox', { name: /^Version/ })).toHaveValue('1.0.0');
	expect(errors).toEqual([]);
});

test('opens one pack of a bundle', async ({ page }) => {
	await openCreator(page);
	await page
		.locator('input[type=file][accept^=".zip"]')
		.setInputFiles(zipFile('fixture-bundle.zip', 'bundle'));
	await expect(page.getByText('fixture-bundle.zip is a bundle of several packs.')).toBeVisible();
	await page.locator('.opening button').nth(1).click();
	await expect(page.getByRole('heading', { name: /^Editing / })).toBeVisible();
});

test('a cover and a custom vibration, written and pasted, end up in the pack', async ({
	page,
	context
}) => {
	const errors = trackErrors(page);
	await context.grantPermissions(['clipboard-read', 'clipboard-write']);
	await openCreator(page);
	await page.getByLabel('Pack name').fill('Taps');
	await page.getByRole('textbox', { name: 'Author' }).fill('Playwright');
	await frame(
		page,
		page.locator('.cover-picker input[type=file]'),
		join(fixtures, 'full-pack/characters/robin/sprite.svg')
	);
	await expect(page.getByRole('img', { name: 'Cover of Taps' })).toBeVisible();

	const card = character(page, 0);
	await card.getByLabel('Name').fill('Woodpecker');
	await frame(
		page,
		card.locator('input[type=file][accept^="image"]'),
		join(fixtures, 'minimal-pack/characters/beep/sprite.png')
	);
	await card
		.locator('input[type=file][accept^="audio"]')
		.setInputFiles(join(fixtures, 'minimal-pack/characters/beep/clips/beep.ogg'));
	const clipDialog = page.getByRole('dialog', { name: 'Edit clip' });
	await clipDialog.getByRole('button', { name: 'Save clip' }).click();
	await expect(clipDialog).toBeHidden({ timeout: 15_000 });

	// The timeline editor.
	await card.getByRole('radio', { name: 'Custom pattern' }).check();
	await expect(card.getByText('No events yet.')).toBeVisible();
	await card.getByRole('button', { name: 'Add a tap' }).click();
	await card.getByRole('button', { name: 'Add a swell' }).click();
	await expect(card.getByText('Valid pattern: 2 events, 290 ms.')).toBeVisible();
	await expect(card.getByRole('img', { name: /Pattern timeline: 2 events/ })).toBeVisible();

	// A swell that ends too late is caught by the shared rules.
	await card.getByLabel('Start (ms)').nth(1).fill('4900');
	await expect(card.getByText('The pattern lasts 5140 ms; it must end by 5000 ms.')).toBeVisible();
	await card.getByLabel('Start (ms)').nth(1).fill('50');
	await expect(card.getByText('Valid pattern: 2 events, 290 ms.')).toBeVisible();

	// Copy for AI: the shared prompt, with this clip and the current pattern.
	await card.getByRole('button', { name: 'Copy for AI' }).click();
	await expect(card.getByText('Copied. Paste it into an AI chat.')).toBeVisible();
	const prompt = await page.evaluate(() => navigator.clipboard.readText());
	expect(prompt).toContain('Character: Woodpecker.');
	expect(prompt).toMatch(/Clip 1, "clips\/beep\.(ogg|wav)": \d+ ms long\./);
	expect(prompt).toContain('ms,loudness,sharpness');

	// Paste pattern: a broken answer is explained, a good one replaces the timeline, Undo brings it back.
	const paste = card.getByLabel('Paste a pattern');
	await paste.fill('[{"at":0,"preset":"BUZZ"}]');
	await card.getByRole('button', { name: 'Use pattern' }).click();
	await expect(card.getByText('Event 1: “BUZZ” is not a kind of tap')).toBeVisible();
	await paste.fill(
		'Sure!\n```json\n{"mode":"custom","events":[{"at":0,"preset":"TICK","scale":0.5},{"at":100,"preset":"CLICK"},{"at":200,"preset":"LOW_TICK"}]}\n```'
	);
	await card.getByRole('button', { name: 'Use pattern' }).click();
	await expect(card.getByText('Valid pattern: 3 events, 250 ms.')).toBeVisible();
	await card.getByRole('button', { name: 'Undo' }).click();
	await expect(card.getByText('Valid pattern: 2 events')).toBeVisible();
	await paste.fill('[{"at":0,"preset":"TICK","scale":0.5},{"at":100,"preset":"CLICK"}]');
	await card.getByRole('button', { name: 'Use pattern' }).click();

	const axe = await new AxeBuilder({ page }).include('.creator').analyze();
	expect(axe.violations).toEqual([]);

	await page.getByRole('button', { name: 'Check pack' }).click();
	await expect(page.locator('.report')).toContainText('Ready to import');
	const file = await download(page, 'Download pack');
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.errorCodes).toEqual([]);
	const entries = unzipSync(file.bytes);
	const manifest = JSON.parse(new TextDecoder().decode(entries['pack.json']));
	expect(manifest.cover).toMatch(/^cover\.(webp|png|jxl)$/);
	expect(entries[manifest.cover]).toBeDefined();
	expect(manifest.characters[0].haptic).toEqual({
		mode: 'custom',
		events: [
			{ at: 0, preset: 'TICK', scale: 0.5 },
			{ at: 100, preset: 'CLICK' }
		]
	});
	expect(errors).toEqual([]);
});

test('bundles several packs into one file', async ({ page }) => {
	const errors = trackErrors(page);
	await page.goto('/create/');
	await page.getByRole('link', { name: /Bundle several packs/ }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Bundle packs');
	const input = page.locator('input[type=file]').first();
	await input.setInputFiles([
		fixtureFile('minimal-pack.soundpack.zip'),
		zipFile('full.soundpack.zip', 'full-pack')
	]);
	await expect(page.locator('.files li')).toHaveCount(2);
	await input.setInputFiles([zipFile('fixture-bundle.zip', 'bundle')]);
	await expect(page.getByText('fixture-bundle.zip is already a bundle.')).toBeVisible();

	await page.getByRole('button', { name: 'Check bundle' }).click();
	await expect(page.getByText('Give the bundle a name.')).toBeVisible();
	await page.getByLabel('Bundle name').fill('All my packs');
	await page.getByLabel('Author (optional)').fill('Playwright');
	await page.getByRole('textbox', { name: 'Homepage (optional)' }).fill('http://example.com');
	await expect(page.getByText('Write a full address that starts with https://.')).toBeVisible();
	await page.getByRole('textbox', { name: 'Homepage (optional)' }).fill('https://example.com');
	await page.getByRole('textbox', { name: 'Tags (optional)' }).fill('Demo, Animals');
	await expect(page.getByText('Saved as: demo, animals')).toBeVisible();
	await expect(page.getByLabel('Bundle ID')).toHaveValue(
		/^local\.playwright\.all-my-packs-[a-z0-9]{6}$/
	);

	const axe = await new AxeBuilder({ page }).include('main').analyze();
	expect(axe.violations).toEqual([]);

	await page.getByRole('button', { name: 'Check bundle' }).click();
	await expect(page.locator('.report')).toContainText('Ready to import');
	const file = await download(page, 'Download bundle');
	expect(file.name).toBe('all-my-packs.soundpack.zip');
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.valid).toBe(true);
	expect(result.kind).toBe('bundle');
	const bundle = JSON.parse(new TextDecoder().decode(unzipSync(file.bytes)['bundle.json']));
	expect(bundle).toMatchObject({ homepage: 'https://example.com', tags: ['demo', 'animals'] });
	expect(result.packs.map((p) => p.id)).toEqual([
		'app.mauri.fixtures.minimal',
		'app.mauri.fixtures.full'
	]);
	expect(errors).toEqual([]);
});

test('makes a repository folder ready to upload', async ({ page }) => {
	const errors = trackErrors(page);
	await page.goto('/create/repository/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Host your own repository');
	await page
		.locator('input[type=file]')
		.setInputFiles([fixtureFile('minimal-pack.soundpack.zip'), zipFile('My bundle.zip', 'bundle')]);
	await expect(page.locator('.files li')).toHaveCount(2);
	await page.getByRole('button', { name: 'Make repository' }).click();
	await expect(page.getByText('Give the repository a name.')).toBeVisible();
	await page.getByLabel('Repository name').fill('Playwright packs');
	await page.getByRole('button', { name: 'Make repository' }).click();
	await expect(page.getByText('Ready. Entries in the index: 2.')).toBeVisible();

	const axe = await new AxeBuilder({ page }).include('main').analyze();
	expect(axe.violations).toEqual([]);

	const file = await download(page, 'Download the folder (.zip)');
	const folder = unzipSync(file.bytes);
	const spec = await validator();
	expect(spec.validateDocument('repository', folder['repository.json']).valid).toBe(true);
	const index = JSON.parse(new TextDecoder().decode(folder['repository.json']));
	expect(index.name).toBe('Playwright packs');
	for (const entry of index.packs) {
		expect(folder[entry.download.url]).toBeDefined();
		expect(spec.validateArchive(folder[entry.download.url]).valid).toBe(true);
		if (entry.cover) expect(folder[entry.cover]).toBeDefined();
	}
	expect(Object.keys(folder)).toContain('packs/My-bundle.soundpack.zip');

	// Packs hosted elsewhere: absolute download addresses, no packs/ in the folder.
	await page.getByRole('radio', { name: /Somewhere else/ }).check();
	await page.getByLabel('Address of the folder with the pack files').fill('http://example.com');
	await expect(page.getByText('Use an https:// address.')).toBeVisible();
	await page
		.getByLabel('Address of the folder with the pack files')
		.fill('https://github.com/me/packs/releases/download/v1');
	await page.getByRole('button', { name: 'Make repository' }).click();
	await expect(page.locator('pre.index')).toContainText(
		'https://github.com/me/packs/releases/download/v1/minimal-pack.soundpack.zip'
	);

	// The add-to-app link for where it will live.
	await page
		.getByLabel('Your repository’s address (optional)')
		.fill('https://me.github.io/my-packs/');
	await expect(page.getByRole('link', { name: /cucuto:\/\/repo\/add/ })).toHaveAttribute(
		'href',
		'cucuto://repo/add?url=https%3A%2F%2Fme.github.io%2Fmy-packs%2F'
	);
	expect(errors).toEqual([]);
});

test('the /open/ file handler hands a pack to the creator', async ({ page }) => {
	// Chromium has a real (read-only) launchQueue: replace it with one the test can feed.
	await page.addInitScript(() => {
		const w = window as unknown as { __launch?: (params: unknown) => void };
		Object.defineProperty(window, 'launchQueue', {
			configurable: true,
			value: { setConsumer: (consumer: (params: unknown) => void) => (w.__launch = consumer) }
		});
	});
	await page.goto('/open/');
	const bytes = [...readFileSync(join(fixtures, 'minimal-pack.soundpack.zip'))];
	await page.waitForFunction(() => '__launch' in window);
	await page.evaluate((data) => {
		const file = new File([new Uint8Array(data)], 'minimal.soundpack.zip', {
			type: 'application/zip'
		});
		(window as unknown as { __launch: (params: unknown) => void }).__launch({
			files: [{ getFile: async () => file }]
		});
	}, bytes);
	await page.getByRole('button', { name: 'Edit in the pack creator' }).click();
	await expect(page).toHaveURL(/\/create\/#draft=/);
	await expect(page.getByRole('heading', { name: /^Editing / })).toBeVisible();
});

test('the tools work in Spanish and offline', async ({ page, context }) => {
	await page.goto('/es/create/bundle/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Juntar packs');
	await page.goto('/es/create/repository/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Aloja tu propio repositorio');
	await expect(page.getByRole('heading', { name: 'GitHub Pages (gratis)' })).toBeVisible();

	await page.evaluate(async () => {
		await navigator.serviceWorker.ready;
	});
	await page.reload();
	await context.setOffline(true);
	await page.goto('/create/bundle/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Bundle packs');
	await expect(page.getByRole('button', { name: 'Check bundle' })).toBeVisible();
	await context.setOffline(false);
});
