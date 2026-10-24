// Builds the Google Play feature graphic (1024 × 500, ADR 0071) in English and Spanish: brand/store/feature-graphic-*.
// The same layout as the Open Graph image (build-preview.mjs), tightened for Play's banner: the clock and the words
// stay inside the middle of the canvas, which Play never crops, and the background reaches every edge (no alpha).
// No "WhatsApp" here: Play's IP policy and Meta's brand guidelines keep other brands out of the feature graphic.
// Also writes the listing copies, fastlane/metadata/android/<locale>/images/featureGraphic.png, as 24-bit PNGs
// (Play refuses alpha in a feature graphic) with ffmpeg.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { BRAND, MARK_COLORS } from './brand-colors.mjs';
import { write, svgDoc, pathEl, png, out } from './svg.mjs';
import { markBody, wordmark } from './marks.mjs';
import { t, text } from './text.mjs';
import { cookie } from './shapes.mjs';

const tokens = JSON.parse(readFileSync(out('tokens/colors.json'), 'utf8'));
const L = Object.fromEntries(Object.entries(tokens.scheme.light).map(([k, v]) => [k, v.$value]));

const W = 1024;
const H = 500;
const at = (x, y, s, body) => `<svg x="${x}" y="${y}" width="${s}" height="${s}" viewBox="0 0 48 48">${body}</svg>`;
const wm = (x, y, xh, fill) => `<path transform="translate(${x} ${y - xh}) scale(${xh / 40})" d="${wordmark}" fill="${fill}"/>`;

/** fastlane locale folder of each language. */
const LOCALES = { en: 'en-US', es: 'es-ES' };

const LANGUAGES = {
  en: { tagline: 'Give each contact a voice.', title: 'Cucuto: give each contact a voice' },
  es: { tagline: 'Dale voz a cada contacto.', title: 'Cucuto: dale voz a cada contacto' },
};

for (const [lang, { tagline, title }] of Object.entries(LANGUAGES)) {
  const CX = 300;
  const CY = 250;
  let b = `<rect width="${W}" height="${H}" fill="${L.surface}"/>`;
  // Sound rings spreading from the clock, cut by the canvas edges like the website's hero.
  for (const [r, op] of [[330, 0.35], [262, 0.5], [196, 0.7]]) {
    b += `<circle cx="${CX}" cy="${CY}" r="${r}" fill="none" stroke="${L.surfaceContainerHigh}" stroke-width="30" opacity="${op}"/>`;
  }
  b += pathEl(cookie(CX, CY, 172, 9, 0.06), BRAND.tangerine);
  b += at(CX - 128, CY - 140, 256, markBody(MARK_COLORS.onColor));
  // Text column, x 520–900: the wordmark sets the width, the tagline fits under it.
  const X = 520;
  const COL = 900 - X;
  const xh = (COL * 40) / 246;
  b += wm(X, 236, xh, BRAND.walnut);
  const size = Math.min(40, (40 * COL) / text(tagline, { size: 40, wght: 650 }).width);
  b += t(tagline, { x: X + 2, y: 312, size, wght: 650, ROND: 100, fill: BRAND.walnut });
  write(`store/feature-graphic-${lang}.svg`, svgDoc(`0 0 ${W} ${H}`, b, { title }));
  png(`store/feature-graphic-${lang}.svg`, `store/feature-graphic-${lang}.png`, W, H);
  const listing = out(`../fastlane/metadata/android/${LOCALES[lang]}/images/featureGraphic.png`);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', out(`store/feature-graphic-${lang}.png`), '-pix_fmt', 'rgb24', listing]);
}
console.log('store graphics built');
