// Pictures for sprites: decode what the browser can (PNG, JPEG, WebP, GIF, AVIF, SVG, and JPEG XL where supported;
// the first frame of animations), crop the square the person framed, and encode it. Same geometry and targets as the
// app's creator (SpriteCrop and SpriteImages in android/core/creator): square, at most 512 px, never enlarged.
// Format: JPEG XL when the browser can encode it (the format's recommendation); otherwise WebP like the app, lossless
// for pictures with transparency and lossy at quality 90 for photos; PNG or JPEG where the browser cannot write WebP
// (Safari). Every one is a pack image format (docs/pack-format.md#images).

/** Pictures are decoded at most this big for cropping (as the app). */
export const MAX_DECODE_SIDE = 2048;
export const LOSSY_QUALITY = 0.9;
export const MIN_ZOOM = 1;
export const MAX_ZOOM = 8;

/** A decoded picture, scaled down to at most [MAX_DECODE_SIDE]. */
export interface Picture {
	bitmap: ImageBitmap;
	width: number;
	height: number;
}

/**
 * The cropper's state, as `SpriteCrop` in the app: the picture covers a square viewport, then is zoomed by `zoom`
 * and moved by `panX`, `panY` (fractions of the viewport side, positive to the right and down).
 */
export interface Crop {
	zoom: number;
	panX: number;
	panY: number;
}

export interface Region {
	left: number;
	top: number;
	size: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Keeps the viewport inside the picture. `aspect` is width / height. */
export function clampCrop(crop: Crop, aspect: number): Crop {
	const zoom = clamp(crop.zoom, MIN_ZOOM, MAX_ZOOM);
	const reachX = (zoom * Math.max(1, aspect) - 1) / 2;
	const reachY = (zoom * Math.max(1, 1 / aspect) - 1) / 2;
	return {
		zoom,
		panX: clamp(crop.panX, -reachX, reachX) + 0,
		panY: clamp(crop.panY, -reachY, reachY) + 0
	};
}

/** The visible square of a `width` × `height` picture. */
export function cropRegion(crop: Crop, width: number, height: number): Region {
	const c = clampCrop(crop, width / Math.max(1, height));
	const short = Math.min(width, height);
	const size = clamp(Math.round(short / c.zoom), 1, short);
	const centerX = width / 2 - c.panX * size;
	const centerY = height / 2 - c.panY * size;
	return {
		left: clamp(Math.round(centerX - size / 2), 0, width - size),
		top: clamp(Math.round(centerY - size / 2), 0, height - size),
		size
	};
}

async function bitmapOf(file: Blob): Promise<ImageBitmap> {
	try {
		return await createImageBitmap(file);
	} catch (error) {
		// Some browsers decode SVG only through an <img>; a data: URL, which the site's CSP allows for images.
		if (!file.type.includes('svg') && !(await looksLikeSvg(file))) throw error;
		const img = new Image();
		img.decoding = 'async';
		img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(await file.text())}`;
		await img.decode();
		const side = MAX_DECODE_SIDE / 2;
		const w = img.naturalWidth || side;
		const h = img.naturalHeight || side;
		const scale = side / Math.max(w, h);
		return await createImageBitmap(img, {
			resizeWidth: Math.max(1, Math.round(w * scale)),
			resizeHeight: Math.max(1, Math.round(h * scale)),
			resizeQuality: 'high'
		});
	}
}

async function looksLikeSvg(file: Blob): Promise<boolean> {
	const head = await file.slice(0, 512).text();
	return /<svg[\s>]/i.test(head);
}

/** Decodes a picture, scaled down so its longest side is at most [MAX_DECODE_SIDE]. */
export async function loadPicture(file: Blob): Promise<Picture> {
	let bitmap = await bitmapOf(file);
	const longest = Math.max(bitmap.width, bitmap.height);
	if (longest > MAX_DECODE_SIDE) {
		const scale = MAX_DECODE_SIDE / longest;
		const scaled = await createImageBitmap(bitmap, {
			resizeWidth: Math.max(1, Math.round(bitmap.width * scale)),
			resizeHeight: Math.max(1, Math.round(bitmap.height * scale)),
			resizeQuality: 'high'
		});
		bitmap.close();
		bitmap = scaled;
	}
	return { bitmap, width: bitmap.width, height: bitmap.height };
}

/** The square `crop` of `picture` on a canvas, at most `maxSide` px and never enlarged. */
export function renderCrop(picture: Picture, crop: Crop, maxSide: number): HTMLCanvasElement {
	const region = cropRegion(crop, picture.width, picture.height);
	const side = Math.min(region.size, maxSide);
	const canvas = document.createElement('canvas');
	canvas.width = side;
	canvas.height = side;
	const ctx = canvas.getContext('2d');
	if (!ctx) throw new Error('No 2D canvas');
	ctx.imageSmoothingEnabled = true;
	ctx.imageSmoothingQuality = 'high';
	ctx.drawImage(
		picture.bitmap,
		region.left,
		region.top,
		region.size,
		region.size,
		0,
		0,
		side,
		side
	);
	return canvas;
}

export interface EncodedImage {
	bytes: Uint8Array;
	/** File extension, without the dot. */
	extension: 'jxl' | 'webp' | 'png' | 'jpg';
	type: string;
	/** A data: URL for previews (the site's CSP allows data: images). */
	preview: string;
}

const EXTENSIONS: Record<string, EncodedImage['extension']> = {
	'image/jxl': 'jxl',
	'image/webp': 'webp',
	'image/png': 'png',
	'image/jpeg': 'jpg'
};

function toBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
	return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

function hasTransparency(canvas: HTMLCanvasElement): boolean {
	const ctx = canvas.getContext('2d');
	if (!ctx) return false;
	const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
	for (let i = 3; i < data.length; i += 4) if (data[i] !== 255) return true;
	return false;
}

/**
 * Encodes a sprite. A browser that cannot write the asked type returns PNG instead, so the blob's own type says what
 * it really is.
 */
export async function encodeSprite(canvas: HTMLCanvasElement): Promise<EncodedImage> {
	const transparent = hasTransparency(canvas);
	// WebP at quality 1 is lossless in Chromium; elsewhere it is the best lossy quality, still a fine sprite.
	const attempts: [string, number | undefined][] = transparent
		? [
				['image/jxl', 1],
				['image/webp', 1],
				['image/png', undefined]
			]
		: [
				['image/jxl', LOSSY_QUALITY],
				['image/webp', LOSSY_QUALITY],
				['image/jpeg', LOSSY_QUALITY]
			];
	for (const [type, quality] of attempts) {
		const blob = await toBlob(canvas, type, quality);
		if (blob && blob.type === type) return encoded(blob, type, canvas);
	}
	const png = await toBlob(canvas, 'image/png');
	if (!png) throw new Error('The browser could not encode the picture');
	return encoded(png, 'image/png', canvas);
}

async function encoded(blob: Blob, type: string, canvas: HTMLCanvasElement): Promise<EncodedImage> {
	return {
		bytes: new Uint8Array(await blob.arrayBuffer()),
		extension: EXTENSIONS[type],
		type,
		// A PNG preview: every browser shows it, whatever was written to the pack.
		preview: canvas.toDataURL('image/png')
	};
}

/**
 * A seed color suggestion from a sprite: the average of the most common vivid hue, `#RRGGBB`, or null for a grey or
 * transparent picture. The app re-themes each character from its seed (brand/README.md, "Character accents").
 */
export function suggestSeedColor(canvas: HTMLCanvasElement): string | null {
	const small = document.createElement('canvas');
	small.width = 48;
	small.height = 48;
	const ctx = small.getContext('2d');
	if (!ctx) return null;
	ctx.drawImage(canvas, 0, 0, 48, 48);
	const { data } = ctx.getImageData(0, 0, 48, 48);
	const buckets = Array.from({ length: 24 }, () => ({ weight: 0, r: 0, g: 0, b: 0 }));
	for (let i = 0; i < data.length; i += 4) {
		const [r, g, b, a] = [data[i], data[i + 1], data[i + 2], data[i + 3]];
		if (a < 128) continue;
		const max = Math.max(r, g, b);
		const min = Math.min(r, g, b);
		const chroma = max - min;
		if (chroma < 40 || max < 50) continue;
		let hue: number;
		if (max === r) hue = ((g - b) / chroma + 6) % 6;
		else if (max === g) hue = (b - r) / chroma + 2;
		else hue = (r - g) / chroma + 4;
		const bucket = buckets[Math.floor((hue / 6) * 24) % 24];
		const weight = chroma / 255;
		bucket.weight += weight;
		bucket.r += r * weight;
		bucket.g += g * weight;
		bucket.b += b * weight;
	}
	const best = buckets.reduce((a, b) => (b.weight > a.weight ? b : a));
	if (best.weight < 1) return null;
	const hex = (v: number) =>
		Math.round(v / best.weight)
			.toString(16)
			.padStart(2, '0')
			.toUpperCase();
	return `#${hex(best.r)}${hex(best.g)}${hex(best.b)}`;
}
