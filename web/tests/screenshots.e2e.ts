// Full-page screenshots of every page in both themes, for reviews and PRs. Not part of the CI run:
//   SCREENSHOTS=1 bun run playwright test screenshots --project=desktop
import { test } from '@playwright/test';

const paths = [
	'/',
	'/packs/',
	'/install/',
	'/authors/',
	'/authors/pack-format/',
	'/privacy/',
	'/es/'
];

test.skip(!process.env.SCREENSHOTS, 'set SCREENSHOTS=1 to capture screenshots');

for (const colorScheme of ['light', 'dark'] as const) {
	test.describe(colorScheme, () => {
		test.use({ colorScheme });

		for (const path of paths) {
			test(`capture ${path}`, async ({ page }, testInfo) => {
				await page.goto(path);
				await page.evaluate(() => document.fonts.ready);
				// Let the hero animation reach its resting pose.
				await page.waitForTimeout(3000);
				const name = path === '/' ? 'home' : path.replaceAll('/', '');
				await page.screenshot({
					path: `test-results/screenshots/${name}-${colorScheme}-${testInfo.project.name}.png`,
					fullPage: true
				});
			});
		}
	});
}
