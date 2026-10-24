import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// Every page in both languages (English at the root, Spanish under /es/, issue #70).
const pages = [
	{ path: '/', heading: 'Give each contact a voice.', lang: 'en', nav: 'Main' },
	{ path: '/packs/', heading: 'Packs', lang: 'en', nav: 'Main' },
	{ path: '/create/', heading: 'Create a pack', lang: 'en', nav: 'Main' },
	{ path: '/install/', heading: 'Install guide', lang: 'en', nav: 'Main' },
	{ path: '/authors/', heading: 'For pack authors', lang: 'en', nav: 'Main' },
	{ path: '/authors/pack-format/', heading: 'Pack format', lang: 'en', nav: 'Main' },
	{ path: '/privacy/', heading: 'Privacy', lang: 'en', nav: 'Main' },
	{ path: '/open/', heading: 'Open a pack', lang: 'en', nav: 'Main' },
	{ path: '/es/', heading: 'Dale voz a cada contacto.', lang: 'es', nav: 'Principal' },
	{ path: '/es/packs/', heading: 'Packs', lang: 'es', nav: 'Principal' },
	{ path: '/es/create/', heading: 'Crear un pack', lang: 'es', nav: 'Principal' },
	{ path: '/es/install/', heading: 'Guía de instalación', lang: 'es', nav: 'Principal' },
	{ path: '/es/authors/', heading: 'Crear packs', lang: 'es', nav: 'Principal' },
	{
		path: '/es/authors/pack-format/',
		heading: 'Formato de los packs',
		lang: 'es',
		nav: 'Principal'
	},
	{ path: '/es/privacy/', heading: 'Privacidad', lang: 'es', nav: 'Principal' },
	{ path: '/es/open/', heading: 'Abrir un pack', lang: 'es', nav: 'Principal' }
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

		for (const { path, heading, lang, nav } of pages) {
			test(`${path} renders without errors and passes axe`, async ({ page }) => {
				const errors = trackErrors(page);
				await page.goto(path);

				await expect(page.getByRole('heading', { level: 1 })).toHaveText(heading);
				await expect(page.locator('html')).toHaveAttribute('lang', lang);
				await expect(page).toHaveTitle(/Cucuto/);
				await expect(page.getByRole('navigation', { name: nav })).toBeVisible();
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

test('the language switch opens the same page in the other language', async ({ page }) => {
	await page.goto('/install/');
	await page.getByRole('link', { name: 'Español' }).click();
	await expect(page).toHaveURL(/\/es\/install\/$/);
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Guía de instalación');
	await expect(page.locator('html')).toHaveAttribute('lang', 'es');

	await page.getByRole('link', { name: 'English' }).click();
	await expect(page).toHaveURL(/\/install\/$/);
	await expect(page.locator('html')).toHaveAttribute('lang', 'en');
});

test('pages link to their translations for search engines', async ({ page }) => {
	await page.goto('/es/privacy/');
	await expect(page.locator('link[rel="alternate"][hreflang="en"]')).toHaveAttribute(
		'href',
		'https://cucuto.mauri.app/privacy/'
	);
	await expect(page.locator('link[rel="alternate"][hreflang="es"]')).toHaveAttribute(
		'href',
		'https://cucuto.mauri.app/es/privacy/'
	);
});

test('the pack format page renders docs/pack-format.md with working anchors', async ({ page }) => {
	await page.goto('/authors/pack-format/');
	await expect(page.locator('.spec h2').first()).toBeVisible();
	// Every in-document link points at a heading that exists.
	const anchors = await page
		.locator('.spec a[href^="#"], .toc a[href^="#"]')
		.evaluateAll((links) => links.map((link) => link.getAttribute('href')!.slice(1)));
	expect(anchors.length).toBeGreaterThan(5);
	for (const id of anchors) await expect(page.locator(`[id="${id}"]`)).toHaveCount(1);
	// Relative links go to the repository on GitHub.
	const relative = await page.locator('.spec a:not([href^="#"]):not([href^="https://"])').count();
	expect(relative).toBe(0);
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

test('unknown Spanish pages show the not-found page in Spanish', async ({ page }) => {
	await page.goto('/es/no-existe/');
	await expect(page.getByRole('heading', { level: 1 })).toHaveText(
		'No hay nadie en esta dirección'
	);
	await expect(page.locator('html')).toHaveAttribute('lang', 'es');
});
