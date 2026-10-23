// Copies the generated brand assets the website uses from `brand/` into `web/`.
// The brand folder is the source of truth: run `pnpm sync:brand` after rebuilding the brand, then commit the copies.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';

const web = join(import.meta.dirname, '..');
const brand = join(web, '..', 'brand');

const files: Array<[from: string, to: string]> = [
	// Icons, social card (static, served at the site root)
	['web/favicon.ico', 'static/favicon.ico'],
	['web/favicon.svg', 'static/favicon.svg'],
	['web/apple-touch-icon.png', 'static/apple-touch-icon.png'],
	['web/icon-192.png', 'static/icon-192.png'],
	['web/icon-512.png', 'static/icon-512.png'],
	['web/icon-maskable-512.png', 'static/icon-maskable-512.png'],
	['web/og-image.png', 'static/og-image.png'],
	// Imported by components (hashed by Vite)
	['logo/cucuto-logo-light.svg', 'src/lib/assets/cucuto-logo-light.svg'],
	['logo/cucuto-logo-dark.svg', 'src/lib/assets/cucuto-logo-dark.svg'],
	['logo/cucuto-mark.svg', 'src/lib/assets/cucuto-mark.svg'],
	['motion/door-open.svg', 'src/lib/assets/door-open.svg'],
	// Raster sources for `?picture` imports (encoded to JPEG XL, AVIF and PNG at build time, see scripts/picture.ts)
	['app-icon/cucuto-icon-rounded-512.png', 'src/lib/assets/images/cucuto-icon.png'],
	// Design tokens
	['tokens/colors.css', 'src/lib/styles/colors.css'],
	['tokens/typography.css', 'src/lib/styles/typography.css']
];

for (const [from, to] of files) {
	const target = join(web, to);
	mkdirSync(dirname(target), { recursive: true });
	copyFileSync(join(brand, from), target);
	console.log(`brand/${from} -> web/${to}`);
}
