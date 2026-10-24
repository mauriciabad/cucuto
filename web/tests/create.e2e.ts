// The Create page (#48) in a real browser under the site's CSP: make a pack from pictures, an uploaded clip and a
// microphone recording (a generated tone), download it, and check the file with the same validator as packer
// and the app (shared/pack-spec compiled to JavaScript). Media from the golden fixtures goes in as input, so every
// format the browser can read is covered. tests/creator.test.ts covers the building blocks under Bun.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { unzipSync } from 'fflate';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
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

test.beforeEach(async ({ page }) => {
	// Each test makes a whole pack (decoding, Kotlin DSP, encoding, zipping, validating): give slow CI machines room.
	test.setTimeout(90_000);
	test.skip(!built, 'Pack validator not built (./gradlew syncWebPackSpec in shared/pack-spec)');
	// The microphone: a 440 Hz tone from Web Audio instead of a real device, so the recording path (getUserMedia,
	// MediaRecorder, decoding) runs the same on every machine. Chromium's fake capture device needs the OS's
	// microphone permission on macOS and hangs without it.
	await page.addInitScript(() => {
		navigator.mediaDevices.getUserMedia = async () => {
			const ctx = new AudioContext();
			const tone = ctx.createOscillator();
			const gain = ctx.createGain();
			gain.gain.value = 0.2;
			const out = ctx.createMediaStreamDestination();
			tone.connect(gain).connect(out);
			tone.start();
			await ctx.resume();
			return out.stream;
		};
	});
});

async function validator() {
	return packSpec((await import(generated)) as PackSpecModule);
}

/** Collects console errors and uncaught exceptions (CSP violations show up here too). */
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

async function fillDetails(page: Page, name: string) {
	await page.getByLabel('Pack name').fill(name);
	await page.getByRole('textbox', { name: 'Author' }).fill('Playwright');
}

async function addPicture(page: Page, n: number, file: string) {
	await character(page, n).locator('input[type=file][accept^="image"]').setInputFiles(file);
	const dialog = page.getByRole('dialog', { name: 'Frame the picture' });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('application', { name: 'Picture framing area' }).press('+');
	await dialog.getByRole('button', { name: 'Use picture' }).click();
	await expect(dialog).toBeHidden();
	await expect(character(page, n).locator('.sprite img')).toBeVisible();
}

async function addClipFile(page: Page, n: number, file: string) {
	await character(page, n).locator('input[type=file][accept^="audio"]').setInputFiles(file);
	await saveClipDialog(page);
}

async function saveClipDialog(page: Page) {
	const dialog = page.getByRole('dialog', { name: 'Edit clip' });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('button', { name: 'Save clip' }).click();
	await expect(dialog).toBeHidden({ timeout: 15_000 });
}

async function checkAndDownload(page: Page) {
	await page.getByRole('button', { name: 'Check pack' }).click();
	await expect(page.locator('.report')).toContainText('Ready to import');
	const [download] = await Promise.all([
		page.waitForEvent('download'),
		page.getByRole('button', { name: 'Download pack' }).click()
	]);
	const path = await download.path();
	return { name: download.suggestedFilename(), bytes: new Uint8Array(readFileSync(path)) };
}

test('creates a pack from pictures, a file and a recording, and the validator accepts it', async ({
	page
}) => {
	const errors = trackErrors(page);
	await openCreator(page);
	await fillDetails(page, 'Garden birds');
	await page.getByRole('radio', { name: /^CC0 1.0/ }).check();
	await expect(page.locator('.pack-id code')).toHaveText(
		/^local\.playwright\.garden-birds-[a-z0-9]{6}$/
	);

	// Character 1: an SVG picture, an uploaded clip and a recording from the (simulated) microphone.
	await character(page, 0).getByLabel('Name').fill('Robin');
	await addPicture(page, 0, join(fixtures, 'full-pack/characters/robin/sprite.svg'));
	await addClipFile(page, 0, join(fixtures, 'full-pack/characters/robin/clips/chirp.ogg'));
	await character(page, 0).getByRole('button', { name: 'Record' }).click();
	await expect(character(page, 0).getByRole('status')).toContainText('Recording');
	await page.waitForTimeout(1500);
	await character(page, 0).getByRole('button', { name: 'Stop recording' }).click();
	await saveClipDialog(page);
	await expect(character(page, 0).locator('.clips li')).toHaveCount(2);

	// Character 2: a JPEG picture and an MP3 clip.
	await page.getByRole('button', { name: 'Add a character' }).click();
	await character(page, 1).getByLabel('Name').fill('Song thrush');
	await addPicture(page, 1, join(fixtures, 'all-formats/characters/jpeg/sprite.jpg'));
	await addClipFile(page, 1, join(fixtures, 'all-formats/characters/jxl/clip.mp3'));

	const axe = await new AxeBuilder({ page }).include('.creator').analyze();
	expect(axe.violations).toEqual([]);

	const file = await checkAndDownload(page);
	expect(file.name).toBe('garden-birds.soundpack.zip');

	// The same validator as packer and the app, on the downloaded bytes.
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.issues).toEqual([]);
	expect(result.valid).toBe(true);

	const entries = unzipSync(file.bytes);
	const paths = Object.keys(entries);
	expect(paths[0]).toBe('pack.json');
	expect(paths).toEqual([
		'pack.json',
		expect.stringMatching(/^characters\/robin\/sprite\.(webp|png|jxl)$/),
		'characters/robin/clips/chirp.ogg',
		'characters/robin/clips/recording-1.ogg',
		expect.stringMatching(/^characters\/song-thrush\/sprite\.(webp|jpg|jxl)$/),
		'characters/song-thrush/clips/clip.ogg'
	]);
	// Clips are Ogg Opus written from WebCodecs output.
	const clip = entries['characters/robin/clips/recording-1.ogg'];
	expect(new TextDecoder().decode(clip.subarray(0, 4))).toBe('OggS');
	expect(new TextDecoder().decode(clip.subarray(28, 36))).toBe('OpusHead');

	const manifest = JSON.parse(new TextDecoder().decode(entries['pack.json']));
	expect(manifest).toMatchObject({
		formatVersion: 1,
		id: expect.stringMatching(/^local\.playwright\.garden-birds-[a-z0-9]{6}$/),
		name: 'Garden birds',
		version: '1.0.0',
		author: 'Playwright',
		license: 'CC0-1.0',
		characters: [
			{
				id: 'robin',
				name: 'Robin',
				clips: [
					{ file: 'characters/robin/clips/chirp.ogg', label: 'chirp' },
					{ file: 'characters/robin/clips/recording-1.ogg', label: 'Recording 1' }
				]
			},
			{ id: 'song-thrush', name: 'Song thrush', clips: [{ label: 'clip' }] }
		]
	});
	expect(errors).toEqual([]);
});

test('works offline once the service worker has cached the site', async ({ page, context }) => {
	await openCreator(page);
	await page.evaluate(async () => {
		await navigator.serviceWorker.ready;
	});
	await page.reload(); // now controlled by the service worker
	await expect(character(page, 0)).toBeVisible();
	await context.setOffline(true);
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Create a pack');
	await expect(character(page, 0)).toBeVisible(); // the validator chunk came from the cache too
	await context.setOffline(false);
});

test('lists what the draft still needs', async ({ page }) => {
	await openCreator(page);
	await page.getByRole('button', { name: 'Check pack' }).click();
	const card = page.locator('.incomplete');
	await expect(card).toContainText('Give the pack a name.');
	await expect(card).toContainText('Add an author.');
	await expect(card).toContainText('Choose a picture for Character 1.');
	await expect(card).toContainText('Add a clip or a ringtone to Character 1.');
});

test('writes WAV clips where the browser has no Opus encoder', async ({ page }) => {
	await page.addInitScript(() => {
		// @ts-expect-error: simulate a browser without WebCodecs audio encoding
		delete window.AudioEncoder;
	});
	await openCreator(page);
	await expect(page.getByRole('note')).toContainText('saved as WAV');
	await fillDetails(page, 'Wave');
	await character(page, 0).getByLabel('Name').fill('Beep');
	await addPicture(page, 0, join(fixtures, 'minimal-pack/characters/beep/sprite.png'));
	await addClipFile(page, 0, join(fixtures, 'all-formats/characters/png/clip.wav'));
	const file = await checkAndDownload(page);
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.valid).toBe(true);
	expect(Object.keys(unzipSync(file.bytes))).toContain('characters/beep/clips/clip.wav');
	// The license the app's creator starts with too: free text, so a warning and never an error.
	expect(result.warningCodes).toEqual(['LICENSE_NOT_SPDX']);
});

test('takes the golden fixtures’ media formats as input (Spanish page)', async ({ page }) => {
	await page.goto('/es/create/');
	await expect(character(page, 0)).toBeVisible();
	await page.getByLabel('Nombre del pack').fill('Formatos');
	await page.getByRole('textbox', { name: 'Autor' }).fill('Playwright');

	// Every picture format Chromium decodes, one character each.
	const sprites = ['png/sprite.png', 'jpeg/sprite.jpg', 'webp/sprite.webp', 'gif/sprite.gif'];
	sprites.push('avif/sprite.avif', 'svg/sprite.svg');
	for (const [i, sprite] of sprites.entries()) {
		if (i > 0) await page.getByRole('button', { name: 'Añadir un personaje' }).click();
		const card = character(page, i);
		await card.getByLabel('Nombre').fill(`Formato ${i + 1}`);
		await card
			.locator('input[type=file][accept^="image"]')
			.setInputFiles(join(fixtures, 'all-formats/characters', sprite));
		const dialog = page.getByRole('dialog', { name: 'Encuadra la imagen' });
		await dialog.getByRole('button', { name: 'Usar imagen' }).click();
		await expect(card.locator('.sprite img')).toBeVisible();
	}

	// Every audio format of the fixtures that Chromium plays, added together (short files skip the editor).
	const clips = ['png/clip.wav', 'avif/clip.ogg', 'svg/clip.opus', 'jxl/clip.mp3', 'gif/clip.flac'];
	await character(page, 0)
		.locator('input[type=file][accept^="audio"]')
		.setInputFiles(clips.map((clip) => join(fixtures, 'all-formats/characters', clip)));
	await expect(character(page, 0).locator('.clips li')).toHaveCount(clips.length);
	for (let i = 1; i < sprites.length; i++) {
		await character(page, i)
			.locator('input[type=file][accept^="audio"]')
			.setInputFiles(join(fixtures, 'all-formats/characters/png/clip.wav'));
		await page
			.getByRole('dialog', { name: 'Editar clip' })
			.getByRole('button', { name: 'Guardar clip' })
			.click();
		await expect(character(page, i).locator('.clips li')).toHaveCount(1);
	}

	await page.getByRole('button', { name: 'Comprobar el pack' }).click();
	await expect(page.locator('.report')).toContainText('Listo para importar');
	const [download] = await Promise.all([
		page.waitForEvent('download'),
		page.getByRole('button', { name: 'Descargar el pack' }).click()
	]);
	const bytes = new Uint8Array(readFileSync(await download.path()));
	const result = (await validator()).validateArchive(bytes);
	expect(result.valid).toBe(true);
	expect(result.packs[0].characters).toBe(sprites.length);
});

/** A 440 Hz tone of `seconds` as 16-bit mono WAV at 48 kHz (synthesized, CC0). */
function toneWav(seconds: number): Buffer {
	const rate = 48_000;
	const frames = Math.round(rate * seconds);
	const out = Buffer.alloc(44 + frames * 2);
	out.write('RIFF', 0);
	out.writeUInt32LE(36 + frames * 2, 4);
	out.write('WAVEfmt ', 8);
	out.writeUInt32LE(16, 16);
	out.writeUInt16LE(1, 20);
	out.writeUInt16LE(1, 22);
	out.writeUInt32LE(rate, 24);
	out.writeUInt32LE(rate * 2, 28);
	out.writeUInt16LE(2, 32);
	out.writeUInt16LE(16, 34);
	out.write('data', 36);
	out.writeUInt32LE(frames * 2, 40);
	for (let i = 0; i < frames; i++) {
		out.writeInt16LE(Math.round(8000 * Math.sin((2 * Math.PI * 440 * i) / rate)), 44 + i * 2);
	}
	return out;
}

test('sets when a clip plays: at night, on Saturdays, rarely', async ({ page }) => {
	const errors = trackErrors(page);
	await openCreator(page);
	await fillDetails(page, 'Night frog');
	await character(page, 0).getByLabel('Name').fill('Frog');
	await addPicture(page, 0, join(fixtures, 'minimal-pack/characters/beep/sprite.png'));
	await addClipFile(page, 0, join(fixtures, 'full-pack/characters/robin/clips/chirp.ogg'));

	await character(page, 0).getByRole('button', { name: 'When it plays: chirp' }).click();
	const dialog = page.getByRole('dialog', { name: /When it plays/ });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('checkbox', { name: /Rare clip/ }).check();
	await dialog.getByRole('spinbutton', { name: 'About one message in' }).fill('13');
	await dialog.getByRole('radio', { name: 'At night' }).check();
	await dialog.getByRole('button', { name: 'Saturday' }).click();
	await expect(dialog.getByRole('button', { name: 'Saturday' })).toHaveAttribute(
		'aria-pressed',
		'true'
	);

	// A date that is not a day of the year is named in plain words and blocks saving.
	await dialog.getByRole('button', { name: 'Add a date' }).click();
	await dialog.getByRole('textbox', { name: 'Date 1: Day' }).fill('02-30');
	await expect(dialog).toContainText('Date 1 is not a day of the year.');
	await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();
	await dialog.getByRole('button', { name: 'Remove date 1' }).click();

	const axe = await new AxeBuilder({ page }).include('dialog').analyze();
	expect(axe.violations).toEqual([]);
	await dialog.getByRole('button', { name: 'Save' }).click();
	await expect(dialog).toBeHidden();
	await expect(character(page, 0).locator('.clips li .condition')).toHaveText(
		'At night · Saturday · Rare: about 1 in 13'
	);

	const file = await checkAndDownload(page);
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.errorCodes).toEqual([]);
	const manifest = JSON.parse(new TextDecoder().decode(unzipSync(file.bytes)['pack.json']));
	expect(manifest.characters[0].clips[0]).toMatchObject({
		label: 'chirp',
		when: { time: 'night', weekday: 'sat' },
		oneIn: 13
	});
	expect(errors).toEqual([]);
});

test('makes a ringtone-only character with two versions of one song', async ({ page }) => {
	const errors = trackErrors(page);
	await openCreator(page);
	await fillDetails(page, 'Songs');
	await page.getByText('More details (optional)').click();
	await page.getByRole('textbox', { name: 'Homepage' }).fill('https://example.com/songs');
	await page.getByRole('textbox', { name: 'Tags' }).fill('Songs, Calls');
	await expect(page.getByText('Saved as: songs, calls')).toBeVisible();

	const card = character(page, 0);
	await card.getByLabel('Name').fill('Pond');
	await addPicture(page, 0, join(fixtures, 'minimal-pack/characters/beep/sprite.png'));
	await card.getByRole('textbox', { name: 'Description (optional)' }).fill('A tune for calls.');
	const ringtoneInput = card.locator('input[type=file][accept^=".ogg"]');

	// Too short for a ringtone: refused in plain words.
	await ringtoneInput.setInputFiles({
		name: 'blip.wav',
		mimeType: 'audio/wav',
		buffer: toneWav(2)
	});
	await expect(card).toContainText('Ringtones need at least 5 seconds.');

	await ringtoneInput.setInputFiles({
		name: 'pond-song.wav',
		mimeType: 'audio/wav',
		buffer: toneWav(6)
	});
	await expect(card.locator('.ringtones li')).toHaveCount(1);
	await expect(card.locator('.ringtones li .condition')).toHaveText('Always');

	await card.getByRole('button', { name: 'Add a version: pond-song' }).click();
	const dialog = page.getByRole('dialog', { name: /Edit ringtone/ });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('radio', { name: 'At night' }).check();
	await dialog.getByRole('radio', { name: 'Play an intro once, then loop a section' }).check();
	await dialog.getByRole('slider', { name: 'Loop starts' }).fill('1000');
	const axe = await new AxeBuilder({ page }).include('dialog').analyze();
	expect(axe.violations).toEqual([]);
	await dialog.getByRole('button', { name: 'Save' }).click();
	await expect(dialog).toBeHidden();
	await expect(card.locator('.ringtones li')).toHaveCount(2);
	await expect(card.locator('.ringtones li .condition').nth(1)).toHaveText('At night');

	const file = await checkAndDownload(page);
	const result = (await validator()).validateArchive(file.bytes);
	expect(result.errorCodes).toEqual([]);
	const entries = unzipSync(file.bytes);
	const manifest = JSON.parse(new TextDecoder().decode(entries['pack.json']));
	expect(manifest.homepage).toBe('https://example.com/songs');
	expect(manifest.tags).toEqual(['songs', 'calls']);
	expect(manifest.characters[0]).toMatchObject({
		name: 'Pond',
		description: 'A tune for calls.',
		ringtones: [
			{ file: 'characters/pond/ringtones/pond-song.wav', label: 'pond-song' },
			{
				file: 'characters/pond/ringtones/pond-song.wav',
				label: 'pond-song (version 2)',
				when: { time: 'night' },
				loop: { startMs: 1000, endMs: 6000 }
			}
		]
	});
	expect(manifest.characters[0].clips).toBeUndefined();
	expect(Object.keys(entries).filter((p) => p.includes('/ringtones/'))).toEqual([
		'characters/pond/ringtones/pond-song.wav'
	]);
	expect(errors).toEqual([]);
});
