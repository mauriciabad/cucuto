// Builds the Open Graph image (brand/web/og-image.*) and the one-page brand preview (brand/preview.png).
// Run after build-brand.mjs, build-motion.mjs and generate-tokens.mjs (it reads their output).
import { readFileSync } from 'node:fs';
import { BRAND, MARK_COLORS } from './brand-colors.mjs';
import { write, svgDoc, pathEl, png, jxl, out } from './svg.mjs';
import { markBody, markMono, smallBody, wordmark, wordmarkInLogo, LOGO_W } from './marks.mjs';
import { t, text } from './text.mjs';
import { cookie, sunny, clover, softBurst, circlePath, roundRect, arch } from './shapes.mjs';
import { pose, bodyFor, T } from './motion.mjs';

const tokens = JSON.parse(readFileSync(out('tokens/colors.json'), 'utf8'));
const scheme = (name) => Object.fromEntries(Object.entries(tokens.scheme[name]).map(([k, v]) => [k, v.$value]));
const L = scheme('light');
const D = scheme('dark');
const inner = (rel) => readFileSync(out(rel), 'utf8').replace(/<\/?svg[^>]*>|<title>.*?<\/title>/g, '');
const ICON_BG = inner('app-icon/source/ic_launcher_background.svg');
const ICON_FG = inner('app-icon/source/ic_launcher_foreground.svg');
const ICON_MONO = inner('app-icon/source/ic_launcher_monochrome.svg');

/** Places a 48-grid drawing at (x, y) with size s. */
const at = (x, y, s, body, vb = '0 0 48 48') => `<svg x="${x}" y="${y}" width="${s}" height="${s}" viewBox="${vb}">${body}</svg>`;
/** Wordmark with its baseline at (x, y) and an x-height of xh. */
const wm = (x, y, xh, fill) => `<path transform="translate(${x} ${y - xh}) scale(${xh / 40})" d="${wordmark}" fill="${fill}"/>`;
let clipId = 0;
const icon = (x, y, s, maskPath, mono = null) => {
  const id = `c${clipId++}`;
  const content = mono
    ? `<rect width="108" height="108" fill="${mono.bg}"/>${ICON_MONO.replace(/#000000/g, mono.fg)}`
    : ICON_BG + ICON_FG;
  return `<svg x="${x}" y="${y}" width="${s}" height="${s}" viewBox="18 18 72 72"><defs><clipPath id="${id}"><path d="${maskPath}"/></clipPath></defs><g clip-path="url(#${id})">${content}</g></svg>`;
};
const MASKS = {
  circle: circlePath(54, 54, 36),
  squircle: 'M54 18C84 18 90 24 90 54S84 90 54 90 18 84 18 54 24 18 54 18Z',
  rounded: roundRect(18, 18, 72, 72, 16),
  teardrop: 'M54 18A36 36 0 0 1 90 54V82A8 8 0 0 1 82 90H54A36 36 0 0 1 54 18Z',
  cookie: cookie(54, 54, 36, 9, 0.06),
};
const ICON_STAT = inner('app-icon/source/ic_stat_cucuto.svg');

// Confetti: the assign particle burst, frozen.
const burst = (cx, cy, R, colors, seed = 1) => {
  let s = seed;
  const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647; };
  const shapes = [];
  for (let i = 0; i < 14; i++) {
    const a = (i / 14) * Math.PI * 2 + rnd() * 0.3;
    const r = R * (0.75 + rnd() * 0.5);
    const x = cx + Math.cos(a) * r;
    const y = cy + Math.sin(a) * r;
    const size = 6 + rnd() * 9;
    const c = colors[i % colors.length];
    const kind = i % 4;
    const d = kind === 0 ? circlePath(x, y, size / 2)
      : kind === 1 ? sunny(x, y, size * 0.7, 6, 0.18)
        : kind === 2 ? clover(x, y, size * 0.65)
          : roundRect(x - size / 2, y - size / 4, size, size / 2, size / 4);
    shapes.push(pathEl(d, c));
  }
  return shapes.join('');
};

// ------------------------------------------------------------------------------------------------ Open Graph

{
  const W = 1200;
  const H = 630;
  let b = `<rect width="${W}" height="${H}" fill="${L.surface}"/>`;
  // Sound rings spreading from the clock.
  for (const [r, op] of [[380, 0.35], [300, 0.5], [225, 0.7]]) b += `<circle cx="330" cy="330" r="${r}" fill="none" stroke="${L.surfaceContainerHigh}" stroke-width="34" opacity="${op}"/>`;
  b += pathEl(cookie(330, 330, 215, 9, 0.06), BRAND.tangerine);
  b += burst(330, 300, 250, [BRAND.sunny, L.tertiaryContainer, BRAND.rust, '#3FAE5A', '#3D8BFF', '#D9468F'], 7);
  b += at(170, 160, 320, markBody(MARK_COLORS.onColor));
  // Right column, x 630–1140: fit each line to the column width.
  const X = 630;
  const COL = 1140 - X;
  const fit = (s, size, axes) => Math.min(size, (size * COL) / text(s, { size, ...axes }).width);
  const xh = Math.min(80, (COL * 40) / 246);
  b += t('koo-KOO-toe', { x: X + 2, y: 214, size: 26, wght: 600, ROND: 100, fill: L.primary, tracking: 0.02 });
  b += wm(X, 228 + xh, xh, BRAND.walnut);
  const tag = 'Give each contact a voice.';
  b += t(tag, { x: X + 2, y: 400, size: fit(tag, 46, { wght: 650 }), wght: 650, ROND: 100, fill: BRAND.walnut });
  const sub = 'Open-source Android app · cucuto.mauri.app';
  b += t(sub, { x: X + 3, y: 450, size: fit(sub, 24, { wght: 500 }), wght: 500, ROND: 60, fill: L.onSurfaceVariant });
  write('web/source/og-image.svg', svgDoc(`0 0 ${W} ${H}`, b, { title: 'Cucuto: give each contact a voice' }));
  png('web/source/og-image.svg', 'web/og-image.png', W);
  jxl('web/og-image.png', 'web/og-image.jxl', { lossless: false });
}

// ------------------------------------------------------------------------------------------------ preview

const W = 1600;
const PAD = 64;
let y = 0;
let body = '';
const section = (h, bg, title, fn, dark = false) => {
  body += `<rect x="0" y="${y}" width="${W}" height="${h}" fill="${bg}"/>`;
  if (title) body += t(title, { x: PAD, y: y + 56, size: 22, wght: 700, ROND: 100, fill: dark ? D.primary : L.primary, tracking: 0.08 });
  fn(y);
  y += h;
};
const label = (s, x, yy, { dark = false, anchor = 'start', size = 15 } = {}) =>
  t(s, { x, y: yy, size, wght: 500, ROND: 60, fill: dark ? D.onSurfaceVariant : L.onSurfaceVariant, anchor });

// 1. Hero
section(400, L.surface, null, (y0) => {
  body += at(PAD - 6, y0 + 70, 0, '');
  body += `<svg x="${PAD}" y="${y0 + 80}" width="760" height="${(760 * 48) / LOGO_W}" viewBox="0 0 ${LOGO_W} 48">${markBody()}<path d="${wordmarkInLogo}" fill="${BRAND.walnut}"/></svg>`;
  body += t('Give each contact a voice.', { x: 900, y: y0 + 150, size: 44, wght: 700, ROND: 100, fill: BRAND.walnut });
  body += t('Cucuto (koo-KOO-toe) is Catalan cucut, the cuckoo.', { x: 900, y: y0 + 200, size: 22, wght: 450, ROND: 30, fill: L.onSurfaceVariant });
  body += t('Like a cuckoo clock, it announces who is there:', { x: 900, y: y0 + 232, size: 22, wght: 450, ROND: 30, fill: L.onSurfaceVariant });
  body += t('the little door pops open and their character sings.', { x: 900, y: y0 + 264, size: 22, wght: 450, ROND: 30, fill: L.onSurfaceVariant });
  body += t('Brand guide  ·  brand/README.md', { x: 900, y: y0 + 330, size: 18, wght: 600, ROND: 70, fill: L.primary });
});

// 2. Logo and mark
section(460, '#FFFFFF', 'LOGO AND MARK', (y0) => {
  const tiles = [
    ['#FFFFFF', markBody(), 'full color', false],
    [BRAND.night, markBody(), 'on dark', true],
    [BRAND.tangerine, markBody(MARK_COLORS.onColor), 'on color', false],
    [L.surfaceContainer, `<path d="${markMono}" fill="${BRAND.walnut}"/>`, 'one color', false],
  ];
  tiles.forEach(([bg, m, name], i) => {
    const x = PAD + i * 200;
    body += `<rect x="${x}" y="${y0 + 90}" width="176" height="176" rx="28" fill="${bg}" stroke="${L.outlineVariant}" stroke-width="${bg === '#FFFFFF' ? 1.5 : 0}"/>`;
    body += at(x + 28, y0 + 118, 120, m);
    body += label(name, x + 88, y0 + 296, { anchor: 'middle' });
  });
  // Sizes, rendered at true pixel size.
  const sizes = [16, 24, 32, 48, 64, 96];
  let sx = 880;
  sizes.forEach((s) => {
    body += at(sx, y0 + 266 - s, s, s <= 32 ? smallBody() : markBody());
    body += label(`${s}`, sx + s / 2, y0 + 296, { anchor: 'middle' });
    sx += s + 34;
  });
  body += label('16–32 px use the small mark (no door, bigger bird)', 880, y0 + 330);
  body += `<rect x="${PAD}" y="${y0 + 342}" width="${W - 2 * PAD}" height="0"/>`;
  body += `<svg x="${PAD}" y="${y0 + 336}" width="300" height="${(300 * 48) / LOGO_W}" viewBox="0 0 ${LOGO_W} 48"><path d="${markMono}" fill="${BRAND.walnut}"/><path d="${wordmarkInLogo}" fill="${BRAND.walnut}"/></svg>`;
  body += `<rect x="${PAD + 340}" y="${y0 + 322}" width="420" height="88" rx="44" fill="${BRAND.night}"/>`;
  body += `<svg x="${PAD + 380}" y="${y0 + 336}" width="300" height="${(300 * 48) / LOGO_W}" viewBox="0 0 ${LOGO_W} 48">${markBody()}<path d="${wordmarkInLogo}" fill="${BRAND.cream}"/></svg>`;
});

// 3. App icon
section(380, L.surfaceContainerLow, 'APP ICON  ·  ADAPTIVE + THEMED', (y0) => {
  const names = Object.keys(MASKS);
  names.forEach((n, i) => {
    body += icon(PAD + i * 150, y0 + 116, 130, MASKS[n]);
    body += label(n, PAD + i * 150 + 65, y0 + 290, { anchor: 'middle' });
  });
  const themed = [
    { bg: '#3B2A20', fg: '#FFDBC9', name: 'themed, dark' },
    { bg: '#D7E8FF', fg: '#103A6B', name: 'themed, light' },
  ];
  themed.forEach((m, i) => {
    body += icon(PAD + 770 + i * 150, y0 + 116, 130, MASKS.circle, m);
    body += label(m.name, PAD + 770 + i * 150 + 65, y0 + 290, { anchor: 'middle' });
  });
  [48, 32].forEach((s, i) => {
    body += icon(PAD + 1080 + i * 70, y0 + 181 - s / 2, s, MASKS.circle);
    body += label(`${s} dp`, PAD + 1080 + i * 70 + s / 2, y0 + 290, { anchor: 'middle' });
  });
  // Status bar: ic_stat_cucuto at 24 dp (drawn at 2×) next to the clock, as Android shows it.
  const sbX = PAD + 1240;
  body += `<rect x="${sbX}" y="${y0 + 157}" width="228" height="48" rx="24" fill="${D.surface}"/>`;
  body += t('9:41', { x: sbX + 24, y: y0 + 189, size: 22, wght: 600, ROND: 30, fill: D.onSurface });
  [0, 1].forEach((i) => { body += at(sbX + 92 + i * 56, y0 + 157, 48, ICON_STAT, '0 0 24 24'); });
  body += label('status bar · 24 dp', sbX + 114, y0 + 290, { anchor: 'middle' });
  body += label('108 dp canvas · 66 dp safe zone · rings inside the 72 dp circle · VectorDrawables in brand/app-icon/android-res', PAD, y0 + 340);
});

// 4. Color
section(640, '#FFFFFF', 'COLOR  ·  SEED #FF6B2C, SCHEMEVIBRANT, SPEC 2025', (y0) => {
  const roles = ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'secondary', 'secondaryContainer', 'tertiary', 'tertiaryContainer', 'surface', 'surfaceContainer', 'onSurface', 'outline'];
  [[L, 'light', false], [D, 'dark', true]].forEach(([s, name, dark], row) => {
    const yy = y0 + 92 + row * 130;
    body += `<rect x="${PAD - 12}" y="${yy - 12}" width="${W - 2 * PAD + 24}" height="118" rx="20" fill="${s.surface}"/>`;
    body += t(name, { x: PAD + 6, y: yy + 52, size: 18, wght: 700, ROND: 100, fill: s.onSurface });
    roles.forEach((r, i) => {
      const x = PAD + 90 + i * 112;
      body += `<rect x="${x}" y="${yy}" width="104" height="64" rx="14" fill="${s[r]}" stroke="${s.outlineVariant}" stroke-width="${r.startsWith('surface') ? 1 : 0}"/>`;
      body += t(r.replace('Container', 'Cont.').replace(/([A-Z])/g, ' $1').toLowerCase(), { x, y: yy + 82, size: 11, wght: 550, ROND: 60, fill: s.onSurfaceVariant });
      body += t(s[r], { x, y: yy + 98, size: 11, wght: 450, ROND: 30, fill: s.onSurfaceVariant });
    });
  });
  const tones = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100];
  ['primary', 'secondary', 'tertiary', 'neutral', 'neutralVariant', 'error'].forEach((p, row) => {
    const yy = y0 + 370 + row * 40;
    body += label(p.replace(/([A-Z])/g, ' $1').toLowerCase(), PAD, yy + 22);
    tones.forEach((tone, i) => {
      const hex = tokens.palette[p][tone].$value;
      body += `<rect x="${PAD + 150 + i * 70}" y="${yy}" width="70" height="32" fill="${hex}"/>`;
      if (row === 0) body += t(String(tone), { x: PAD + 150 + i * 70 + 35, y: yy - 8, size: 11, wght: 500, fill: L.onSurfaceVariant, anchor: 'middle' });
    });
  });
  // Character accents
  const chars = Object.entries(tokens.character);
  body += label('character accents (from each sprite)', PAD + 1110, y0 + 362);
  chars.forEach(([name, c], i) => {
    const cx = PAD + 1150 + (i % 3) * 120;
    const cy = y0 + 430 + Math.floor(i / 3) * 130;
    body += pathEl(cookie(cx, cy, 44, 9, 0.07), c.light.primaryContainer.$value);
    body += pathEl(circlePath(cx, cy, 16), c.light.onPrimaryContainer.$value);
    body += label(name, cx, cy + 66, { anchor: 'middle', size: 13 });
  });
});

// 5. Type
section(540, L.surfaceContainerLow, 'TYPE  ·  GOOGLE SANS FLEX (OFL), ROND AXIS FOR THE PLAYFUL SIDE', (y0) => {
  body += t('Aa', { x: PAD, y: y0 + 270, size: 220, wght: 800, ROND: 100, fill: BRAND.tangerine });
  const rows = [
    ['Display · 600 · round 100', 'Pika is calling!', 57, 600, 100],
    ['Headline emphasized · 800', 'Assign a character', 32, 800, 100],
    ['Title · 600 · round 100', 'Recent senders', 22, 600, 100],
    ['Body · 400 · round 30', 'Tap a sprite to hear it, tap again to assign it to Marta.', 16, 400, 30],
    ['Label · 600 · round 70', 'IMPORT PACK   ·   SIMULATE MESSAGE', 14, 600, 70],
  ];
  let yy = y0 + 160;
  rows.forEach(([name, sample, size, wght, rond]) => {
    body += label(name, 420, yy - size * 0.95 - 6, { size: 13 });
    body += t(sample, { x: 420, y: yy, size, wght, ROND: rond, fill: L.onSurface });
    yy += size + 46;
  });
  body += t('Monospace: Google Sans Code, for pack manifests and code samples', { x: 420, y: y0 + 505, size: 15, wght: 400, ROND: 30, fill: L.onSurfaceVariant });
});

// 6. Shape and motion
section(470, '#FFFFFF', 'SHAPE AND MOTION', (y0) => {
  const shapes = [
    ['avatar · Cookie9Sided', cookie(0, 0, 50, 9, 0.07), L.primaryContainer],
    ['pressed · SoftBurst', softBurst(0, 0, 50, 10), L.tertiaryContainer],
    ['badge · Sunny', sunny(0, 0, 50, 8, 0.09), L.secondaryContainer],
    ['door · Arch', arch(-40, -50, 80, 100), L.surfaceContainerHighest],
    ['particle · Clover4Leaf', clover(0, 0, 48), '#FFD25E'],
  ];
  shapes.forEach(([name, d, fill], i) => {
    const cx = PAD + 70 + i * 170;
    body += `<g transform="translate(${cx} ${y0 + 160})">${pathEl(d, fill)}</g>`;
    body += label(name, cx, y0 + 250, { anchor: 'middle', size: 13 });
  });
  // Buttons: M3 Expressive pill at rest, squarer when pressed.
  body += `<rect x="${PAD + 840}" y="${y0 + 110}" width="180" height="56" rx="28" fill="${L.primary}"/>`;
  body += t('Assign', { x: PAD + 930, y: y0 + 145, size: 17, wght: 650, ROND: 80, fill: L.onPrimary, anchor: 'middle' });
  body += `<rect x="${PAD + 1040}" y="${y0 + 110}" width="180" height="56" rx="12" fill="${L.primary}"/>`;
  body += t('Assign', { x: PAD + 1130, y: y0 + 145, size: 17, wght: 650, ROND: 80, fill: L.onPrimary, anchor: 'middle' });
  body += label('button at rest · pressed (shape morph, fast spatial spring)', PAD + 840, y0 + 196, { size: 13 });
  body += burst(PAD + 1380, y0 + 140, 56, [BRAND.tangerine, BRAND.sunny, L.tertiary, BRAND.rust], 3);
  body += pathEl(cookie(PAD + 1380, y0 + 140, 30, 9, 0.07), L.primaryContainer);
  body += label('assign burst', PAD + 1380, y0 + 250, { anchor: 'middle', size: 13 });
  // Door filmstrip
  const times = [0.4, T.doorOpen + 0.06, T.birdOut + 0.06, T.birdOut + 0.14, T.call1 + 0.25, T.birdIn + 0.12, T.doorClose + 0.12];
  times.forEach((time, i) => {
    const x = PAD + i * 150;
    body += `<svg x="${x}" y="${y0 + 290}" width="125" height="100" viewBox="0 0 60 48">${bodyFor({ ...MARK_COLORS.full, waves: BRAND.tangerine }, pose(time))}</svg>`;
    body += label(`${Math.round(time * 1000)} ms`, x + 52, y0 + 414, { anchor: 'middle', size: 13 });
  });
  body += label('door: fast spatial spring (0.6 / 800)', PAD + 1080, y0 + 330, { size: 14 });
  body += label('bird: fast spatial + squash and stretch', PAD + 1080, y0 + 356, { size: 14 });
  body += label('fades: effects springs (no bounce)', PAD + 1080, y0 + 382, { size: 14 });
  body += label('reduced motion: door shown open, no loop', PAD + 1080, y0 + 408, { size: 14 });
});

// 7. Sound and voice
section(150, BRAND.night, null, (y0) => {
  const chips = ['tap', 'toggle on', 'toggle off', 'success', 'error', 'assign: cu-cu!'];
  let x = PAD;
  chips.forEach((c, i) => {
    const w = text(c, { size: 17, wght: 650, ROND: 100 }).width + 48;
    body += `<rect x="${x}" y="${y0 + 44}" width="${w}" height="48" rx="24" fill="${i === chips.length - 1 ? BRAND.tangerine : D.surfaceContainerHigh}"/>`;
    body += t(c, { x: x + w / 2, y: y0 + 74, size: 17, wght: 650, ROND: 100, fill: i === chips.length - 1 ? BRAND.walnut : D.onSurface, anchor: 'middle' });
    x += w + 14;
  });
  body += t('Voice: Cucuto, capital C · short · friendly · never shouty', { x: W - PAD, y: y0 + 75, size: 18, wght: 500, ROND: 60, fill: D.onSurfaceVariant, anchor: 'end' });
}, true);

write('.work/preview.svg', svgDoc(`0 0 ${W} ${y}`, body));
png('.work/preview.svg', 'preview.png', W);
jxl('preview.png');
console.log('preview built');
