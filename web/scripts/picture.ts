// Vite plugin: `import logo from './logo.png?picture'` (or `?w=192&picture` to resize to 192 px wide) encodes the
// image as JPEG XL, AVIF and PNG at build time and returns a `PictureData` object for `<Picture>`
// (src/lib/components/Picture.svelte), which renders a `<picture>` with the JPEG XL source first.
//
// JPEG XL comes from `cjxl` (libjxl: `brew install jpeg-xl`, `apt install libjxl-tools`); AVIF and PNG from sharp.
// Without cjxl the JPEG XL source is skipped with a warning, unless PICTURE_REQUIRE_JXL=1 (CI). Encoded files are
// cached in node_modules/.cache/picture by a hash of the source and the settings below, so rebuilds are instant.
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import sharp from 'sharp';
import type { Plugin } from 'vite';

const run = promisify(execFile);

/** Bump `version` (or change a setting) to re-encode everything. */
const SETTINGS = {
	version: 1,
	// Distance 1 is visually lossless; effort 7 is cjxl's default trade-off.
	jxl: ['--distance=1', '--effort=7', '--quiet'],
	avif: { quality: 60, effort: 4 },
	png: { compressionLevel: 9, adaptiveFiltering: true }
} as const;

export interface PictureFiles {
	width: number;
	height: number;
	png: string;
	avif: string;
	jxl?: string;
}

let cjxlAvailable: Promise<boolean> | undefined;
function hasCjxl() {
	cjxlAvailable ??= run('cjxl', ['--version']).then(
		() => true,
		() => false
	);
	return cjxlAvailable;
}

async function writeAtomic(file: string, data: Buffer) {
	const tmp = `${file}.${process.pid}.tmp`;
	await writeFile(tmp, data);
	await rename(tmp, file);
}

/** Encodes `source` (resized to `width` if given) into the cache folder; returns the file paths. */
export async function encodePicture(
	source: string,
	cacheDir: string,
	width?: number,
	warn: (message: string) => void = console.warn
): Promise<PictureFiles> {
	const input = await readFile(source);
	const key = createHash('sha256')
		.update(input)
		.update(JSON.stringify({ SETTINGS, width }))
		.digest('hex')
		.slice(0, 12);
	const base = path.join(cacheDir, `${path.parse(source).name}-${key}`);
	await mkdir(cacheDir, { recursive: true });

	const resized = () =>
		width ? sharp(input).resize({ width, withoutEnlargement: true }) : sharp(input);

	const png = `${base}.png`;
	if (!existsSync(png)) await writeAtomic(png, await resized().png(SETTINGS.png).toBuffer());
	const avif = `${base}.avif`;
	if (!existsSync(avif)) await writeAtomic(avif, await resized().avif(SETTINGS.avif).toBuffer());

	let jxl: string | undefined = `${base}.jxl`;
	if (!existsSync(jxl)) {
		if (await hasCjxl()) {
			// The resized PNG is the input, so JPEG XL and the fallbacks show exactly the same pixels.
			await run('cjxl', [png, `${jxl}.tmp`, ...SETTINGS.jxl]);
			await rename(`${jxl}.tmp`, jxl);
		} else if (process.env.PICTURE_REQUIRE_JXL === '1') {
			throw new Error('cjxl not found (install libjxl-tools / jpeg-xl) and PICTURE_REQUIRE_JXL=1');
		} else {
			warn(`picture: cjxl not found, serving ${path.basename(source)} without a JPEG XL source`);
			jxl = undefined;
		}
	}

	const meta = await sharp(png).metadata();
	return { width: meta.width, height: meta.height, png, avif, jxl };
}

export function picture(): Plugin {
	let cacheDir = '';
	return {
		name: 'cucuto:picture',
		enforce: 'pre',
		configResolved(config) {
			cacheDir = path.join(config.root, 'node_modules', '.cache', 'picture');
		},
		async load(id) {
			const [file, query] = id.split('?', 2);
			if (!query) return null;
			const params = new URLSearchParams(query);
			if (!params.has('picture')) return null;
			const width = params.get('w') ? Number(params.get('w')) : undefined;
			if (width !== undefined && !(Number.isInteger(width) && width > 0)) {
				this.error(`picture: invalid width in ${id}`);
			}
			this.addWatchFile(file);
			const files = await encodePicture(file, cacheDir, width, (message) => this.warn(message));
			// `?url&no-inline` hands the files to Vite's asset pipeline: hashed names in the build, served in dev.
			const url = (f: string) => JSON.stringify(`${f}?url&no-inline`);
			return [
				`import png from ${url(files.png)};`,
				`import avif from ${url(files.avif)};`,
				files.jxl ? `import jxl from ${url(files.jxl)};` : '',
				`export default {`,
				`  width: ${files.width}, height: ${files.height}, fallback: png,`,
				`  sources: [${files.jxl ? `{ type: 'image/jxl', srcset: jxl }, ` : ''}{ type: 'image/avif', srcset: avif }]`,
				`};`
			].join('\n');
		}
	};
}
