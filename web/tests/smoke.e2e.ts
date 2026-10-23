import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

const pages = [
	{ path: '/', heading: 'Give each contact a voice.' },
	{ path: '/packs/', heading: 'Packs' },
	{ path: '/install/', heading: 'Install guide' },
	{ path: '/authors/', heading: 'For pack authors' },
	{ path: '/privacy/', heading: 'Privacy' },
	{ path: '/open/', heading: 'Open a pack' }
];

/** Collects console errors and uncaught exceptions (CSP violations show up here too). */
function trackErrors(page: Page) {
	const errors: string[] = [];
	page.on('console', (message) => {
		if (message.type() === 'error') errors.push(message.text());
	});
	page.on('pageerror', (error) => errors.push(error.message));
	return errors;
}

for (const colorScheme of ['light', 'dark'] as const) {
	test.describe(`${colorScheme} theme`, () => {
		test.use({ colorScheme });

		for (const { path, heading } of pages) {
			test(`${path} renders without errors and passes axe`, async ({ page }) => {
				const errors = trackErrors(page);
				await page.goto(path);

				await expect(page.getByRole('heading', { level: 1 })).toHaveText(heading);
				await expect(page).toHaveTitle(/Cucuto/);
				await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
				await expect(page.getByRole('contentinfo')).toContainText('GPL-3.0-or-later');

				const results = await new AxeBuilder({ page })
					.withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'])
					.analyze();
				expect(results.violations).toEqual([]);
				expect(errors).toEqual([]);
			});
		}
	});
}

test('home links to the install guide', async ({ page }) => {
	await page.goto('/');
	await page.getByRole('link', { name: 'How to install' }).click();
	await expect(page).toHaveURL(/\/install\/$/);
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Install guide');
});

test('the cuckoo clock replays when tapped', async ({ page }) => {
	await page.goto('/');
	const clock = page.getByRole('button', { name: 'Play the cuckoo clock animation again' });
	await clock.click();
	await expect(clock.locator('.door svg')).toBeVisible();
});

test.describe('reduced motion', () => {
	test.use({ reducedMotion: 'reduce' });

	test('shows the open clock as a still image', async ({ page }) => {
		await page.goto('/');
		await expect(
			page.getByRole('img', { name: 'A cuckoo clock with its round door open and a bird singing' })
		).toBeVisible();
		await expect(page.getByRole('button', { name: /cuckoo clock/ })).toHaveCount(0);
	});
});

test('serves a web app manifest with maskable icons', async ({ request }) => {
	const response = await request.get('/manifest.webmanifest');
	expect(response.ok()).toBe(true);
	const manifest = await response.json();
	expect(manifest.name).toBe('Cucuto');
	expect(manifest.icons.some((icon: { purpose: string }) => icon.purpose === 'maskable')).toBe(
		true
	);
});

test('unknown pages show the not-found page', async ({ page }) => {
	await page.goto('/does-not-exist/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Nobody’s home at this address');
});
