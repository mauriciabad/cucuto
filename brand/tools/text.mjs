// Sets text in Google Sans Flex as SVG paths (fontkit), so rendered brand images need no installed font.
// The variable font (OFL-1.1) is downloaded once from the google/fonts repository into brand/.work/fonts/.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { out } from './svg.mjs';

const require = createRequire(import.meta.url);
const fontkit = require('fontkit');

const FONT_URL = 'https://github.com/google/fonts/raw/main/ofl/googlesansflex/GoogleSansFlex%5BROND,opsz,slnt,wdth,wght%5D.ttf';
const FONT_FILE = out('.work/fonts/GoogleSansFlex.ttf');

if (!existsSync(FONT_FILE)) {
  mkdirSync(out('.work/fonts'), { recursive: true });
  execFileSync('curl', ['-sSL', '-o', FONT_FILE, FONT_URL]);
}
const base = fontkit.openSync(FONT_FILE);
const cache = new Map();

/** Google Sans Flex instance; defaults are the cucuto UI settings (fully round). */
function instance({ wght = 400, ROND = 100, opsz, wdth = 100, size }) {
  const o = opsz ?? Math.max(6, Math.min(144, size));
  const key = `${wght}/${ROND}/${o}/${wdth}`;
  if (!cache.has(key)) cache.set(key, base.getVariation({ wght, ROND, opsz: o, wdth }));
  return cache.get(key);
}

/**
 * Returns { svg, width } for a line of text with its baseline at (x, y).
 * anchor: 'start' | 'middle' | 'end'. tracking in em.
 */
export function text(str, { x = 0, y = 0, size = 16, fill = '#000', anchor = 'start', tracking = 0, ...axes }) {
  const font = instance({ ...axes, size });
  const run = font.layout(str);
  const s = size / font.unitsPerEm;
  const track = tracking * font.unitsPerEm;
  let advance = 0;
  const parts = [];
  run.glyphs.forEach((g, i) => {
    const pos = run.positions[i];
    const d = g.path.toSVG();
    if (d) parts.push(`<path transform="translate(${(advance + pos.xOffset).toFixed(1)} 0)" d="${d}"/>`);
    advance += pos.xAdvance + track;
  });
  advance -= track;
  const width = advance * s;
  const x0 = anchor === 'middle' ? x - width / 2 : anchor === 'end' ? x - width : x;
  return {
    svg: `<g fill="${fill}" transform="translate(${x0.toFixed(2)} ${y.toFixed(2)}) scale(${s.toFixed(5)} ${(-s).toFixed(5)})">${parts.join('')}</g>`,
    width,
  };
}

export const t = (str, opts) => text(str, opts).svg;
export const FONT_PATH = FONT_FILE;
export { writeFileSync };
