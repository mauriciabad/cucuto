import source from '#lib/assets/door-open.svg?raw';

/**
 * The brand's door-open animation (brand/motion/door-open.svg), ready to inline. Its embedded style sheet becomes
 * global once inlined, so class and keyframe names get a `cucuto-` prefix. The SVG title is dropped: the wrapper
 * element carries the accessible name.
 */
export const doorOpenSvg = source
	.replace(/<title>[\s\S]*?<\/title>/, '')
	.replace(/\s(width|height|role)="[^"]*"/g, '')
	.replace('<svg ', '<svg aria-hidden="true" focusable="false" ')
	.replace(/\b(door-front|door-back|door|bird|beak-upper|beak-lower|waves)\b/g, 'cucuto-$1');
