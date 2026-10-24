// No page is wider than a phone screen (#135): sideways scrolling and the "margin on the right" that mobile browsers
// show when something pokes out, often a long word or inline code, or a flex child that cannot shrink.
import { expect, test, type Page } from '@playwright/test';

const paths = [
	'/',
	'/packs/',
	'/create/',
	'/install/',
	'/authors/',
	'/authors/pack-format/',
	'/privacy/',
	'/open/'
];
const locales = ['', '/es'];
// Small phones, then just above the 40rem breakpoint where the header nav stops scrolling edge to edge.
const widths = [320, 360, 656];

/**
 * Elements that end outside the viewport without a scrolling ancestor (overflow-x auto or scroll) to hold them.
 * This also catches overflow hidden by `overflow: clip` or `hidden`, which keeps scrollWidth right but cuts content.
 */
function offenders(page: Page, width: number) {
	return page.evaluate((width) => {
		const found: string[] = [];
		for (const el of document.body.querySelectorAll('*')) {
			const rect = el.getBoundingClientRect();
			if (rect.width === 0 || rect.height === 0) continue;
			if (rect.left >= -0.5 && rect.right <= width + 0.5) continue;
			let scrolled = false;
			for (let a = el.parentElement; a && !scrolled; a = a.parentElement) {
				scrolled = ['auto', 'scroll'].includes(getComputedStyle(a).overflowX);
			}
			if (scrolled) continue;
			const name = el.tagName.toLowerCase() + [...el.classList].map((c) => `.${c}`).join('');
			const text = (el.textContent ?? '').trim().slice(0, 60);
			found.push(`${name} [${Math.round(rect.left)}, ${Math.round(rect.right)}] ${text}`);
		}
		return found;
	}, width);
}

test.describe('no horizontal overflow', () => {
	// The mobile project is enough: it emulates a phone (meta viewport, touch), where overflow shows.
	test.skip(({ isMobile }) => !isMobile, 'phones only');

	for (const locale of locales) {
		for (const path of paths) {
			const url = locale + path;
			test(`${url} fits ${widths.join(', ')} px`, async ({ page }) => {
				for (const width of widths) {
					await page.setViewportSize({ width, height: 800 });
					await page.goto(url);
					await page.evaluate(() => document.fonts.ready);
					// Compare with the configured width, not innerWidth: a phone browser widens its layout viewport
					// (zooms out) to fit content that overflows, so innerWidth grows along with the page.
					const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
					expect(
						scrollWidth,
						`${url} at ${width} px is ${scrollWidth} px wide`
					).toBeLessThanOrEqual(width);
					expect(await offenders(page, width), `${url} at ${width} px`).toEqual([]);
				}
			});
		}
	}
});
