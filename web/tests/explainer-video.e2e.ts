import { expect, test } from '@playwright/test';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

// The explainer video slot on Home (#42, ADR 0042). Until static/video/ has the English cut, Home shows the poster
// with "coming soon"; once it exists, the same tests check the player instead.
const dir = join(import.meta.dirname, '..', 'static', 'video');
const hasVideo = ['explainer.webm', 'explainer.mp4'].some((file) => existsSync(join(dir, file)));

const section = (page: import('@playwright/test').Page) =>
	page.locator('section[aria-labelledby="video-title"]');

test.describe('explainer video', () => {
	test.skip(hasVideo, 'the video exists: the player tests below apply');

	test('shows the poster with a coming soon note', async ({ page }) => {
		await page.goto('/');
		const slot = section(page);
		await expect(slot.getByRole('heading', { level: 2 })).toHaveText('See it in action');
		const poster = slot.getByRole('img', { name: /^Illustration: a phone shows Cucuto/ });
		await expect(poster).toBeVisible();
		await expect(poster).toHaveAttribute('loading', 'lazy');
		// JPEG XL first when the build had cjxl, then AVIF; the <img> itself is PNG.
		const types = await slot
			.locator('picture source')
			.evaluateAll((sources) => sources.map((s) => s.getAttribute('type')));
		expect(types.at(-1)).toBe('image/avif');
		await expect(slot.getByText('Coming soon')).toBeVisible();
		await expect(slot.getByText('The explainer video is on its way')).toBeVisible();
		await expect(slot.locator('video')).toHaveCount(0);
		await expect(slot.getByRole('button')).toHaveCount(0);
	});

	test('says coming soon in Spanish', async ({ page }) => {
		await page.goto('/es/');
		const slot = section(page);
		await expect(slot.getByText('Próximamente')).toBeVisible();
		await expect(slot.getByRole('img', { name: /^Ilustración:/ })).toBeVisible();
	});
});

test.describe('explainer video player', () => {
	test.skip(!hasVideo, 'no video in static/video yet');

	test('waits for the play button when the viewer prefers reduced motion', async ({ page }) => {
		await page.emulateMedia({ reducedMotion: 'reduce' });
		await page.goto('/');
		const slot = section(page);
		await slot.scrollIntoViewIfNeeded();
		await page.waitForTimeout(500);
		await expect(slot.locator('video')).toHaveCount(0);

		await slot.getByRole('button', { name: 'Play the video' }).click();
		const video = slot.locator('video');
		await expect(video).toHaveAttribute('controls', '');
		await expect(video).toBeFocused();
		await expect(video.locator('track[kind="captions"][srclang="en"]')).toHaveCount(1);
		expect(await video.evaluate((v: HTMLVideoElement) => v.muted)).toBe(false);
	});

	test('plays muted with captions when scrolled into view', async ({ page }) => {
		await page.emulateMedia({ reducedMotion: 'no-preference' });
		await page.goto('/');
		const slot = section(page);
		await slot.scrollIntoViewIfNeeded();
		const video = slot.locator('video');
		await expect(video).toHaveCount(1);
		expect(await video.evaluate((v: HTMLVideoElement) => v.muted)).toBe(true);
		await expect(video.locator('track[srclang="en"]')).toHaveAttribute('default', '');
		await expect(slot.getByText('Read the transcript')).toBeVisible();
	});

	test('plays the English cut with Spanish captions on /es/', async ({ page }) => {
		await page.emulateMedia({ reducedMotion: 'reduce' });
		await page.goto('/es/');
		const slot = section(page);
		await slot.getByRole('button', { name: 'Reproducir el vídeo' }).click();
		const video = slot.locator('video');
		await expect(video.locator('source').first()).toHaveAttribute('src', '/video/explainer.webm');
		await expect(video.locator('track[srclang="es"]')).toHaveAttribute('default', '');
		await expect(slot.getByText('Leer la transcripción')).toBeVisible();
	});
});

test('serves the draft captions as WebVTT', async ({ request }) => {
	for (const lang of ['en', 'es']) {
		const response = await request.get(`/video/explainer.${lang}.vtt`);
		expect(response.ok()).toBe(true);
		expect(await response.text()).toMatch(/^WEBVTT\n/);
	}
});
