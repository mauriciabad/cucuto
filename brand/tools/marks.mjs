// Ready-to-use mark and wordmark shapes (no side effects), shared by the build scripts.
import { markPaths, markSmallPaths, beakGapPaths, wordmarkParts, circle, WORDMARK } from './geometry.mjs';
import { union, subtract, pathData, transform } from './boolean.mjs';
import { MARK_COLORS } from './brand-colors.mjs';
import { pathEl } from './svg.mjs';

export const mp = markPaths();
export const LAYERS = ['house', 'door', 'opening', 'head', 'beak', 'eye'];

/** Layered color mark as SVG elements (48 × 48 grid). */
export const markBody = (colors = MARK_COLORS.full, paths = mp) =>
  LAYERS.filter((l) => paths[l]).map((l) => pathEl(paths[l], colors[l])).join('');

/** Single-color silhouette: house with the opening and a gap around the beak knocked out, bird inside. */
export const markMono = pathData(union(
  subtract(mp.house, mp.opening, beakGapPaths(1.6)),
  subtract(union(mp.head, mp.beak), mp.eye),
));

export const sp = markSmallPaths();
export const smallBody = (colors = MARK_COLORS.full) => markBody(colors, sp);

/** Wordmark as one path; native box x 0–246, y -14–40 (baseline 40). */
export const wordmark = pathData(union(...wordmarkParts().map((p) => (typeof p === 'string'
  ? p
  : subtract(circle(p.ring[0], p.ring[1], p.ring[2]), circle(p.ring[0], p.ring[1], p.ring[3]))))));

// Logo lockup: wordmark baseline on the house floor (y 44.5), x-height on the eaves line (y 20.5).
export const WM_SCALE = 0.6;
export const WM_X = 54;
export const wordmarkInLogo = pathData(transform(wordmark, { scale: WM_SCALE, tx: WM_X, ty: 44.5 - 40 * WM_SCALE }));
export const LOGO_W = Math.ceil(WM_X + WORDMARK.width * WM_SCALE + 3.5);
export { WORDMARK };
