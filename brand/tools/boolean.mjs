// Path booleans (union, subtract, …) with paper.js running headless, so silhouettes such as the monochrome icon
// are real single paths instead of overlapping layers.
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const paper = require('paper/dist/paper-core.js');

paper.setup(new paper.Size(1, 1));

const toItem = (d) => (typeof d === 'string' ? new paper.CompoundPath({ pathData: d, insert: false }) : d);

export const union = (...ds) => ds.map(toItem).reduce((a, b) => a.unite(b, { insert: false }));
export const subtract = (a, ...bs) => bs.map(toItem).reduce((acc, b) => acc.subtract(b, { insert: false }), toItem(a));
export const intersect = (a, b) => toItem(a).intersect(toItem(b), { insert: false });

/** Rounds coordinates to 3 decimals and returns SVG path data. */
export const d = (item) => {
  const it = toItem(item);
  return it.exportSVG({ precision: 3 }).getAttribute?.('d') ?? it.pathData;
};

export const pathData = (item) => {
  const it = toItem(item);
  // paper's pathData already uses relative-free absolute commands with precision 5; trim to 3 decimals.
  return it.pathData.replace(/-?\d+\.\d+/g, (n) => Number(Number(n).toFixed(3)).toString());
};

export const bounds = (item) => toItem(item).bounds;
export const transform = (item, { scale = 1, tx = 0, ty = 0 }) => {
  const it = toItem(item).clone({ insert: false });
  it.scale(scale, new paper.Point(0, 0));
  it.translate(new paper.Point(tx, ty));
  return it;
};
export { paper };
