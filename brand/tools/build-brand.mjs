// Builds every logo, app icon and web icon from the hand-made geometry in geometry.mjs.
//   cd brand/tools && bun install && bun run build
import { readFileSync } from 'node:fs';
import { circle } from './geometry.mjs';
import { subtract, pathData, transform } from './boolean.mjs';
import { BRAND, MARK_COLORS } from './brand-colors.mjs';
import { write, svgDoc, pathEl, png, jxl, ico, out } from './svg.mjs';
import { mp, LAYERS, markBody, markMono, smallBody, wordmark, wordmarkInLogo, LOGO_W, WORDMARK } from './marks.mjs';

// ------------------------------------------------------------------------------------------------ logo files

const DARK_TEXT_CSS = (sel) => `<style>@media (prefers-color-scheme: dark){${sel}{fill:${BRAND.cream}}}</style>`;

write('logo/cucuto-mark.svg', svgDoc('0 0 48 48', markBody(), { title: 'cucuto' }));
write('logo/cucuto-mark-on-color.svg', svgDoc('0 0 48 48', markBody(MARK_COLORS.onColor), { title: 'cucuto' }));
write('logo/cucuto-mark-small.svg', svgDoc('0 0 48 48', smallBody(), { title: 'cucuto' }));
write('logo/cucuto-mark-mono.svg', svgDoc('0 0 48 48', pathEl(markMono, 'currentColor'), { title: 'cucuto' }));

const WM_VB = `0 -14 ${WORDMARK.width} 54`;
write('logo/cucuto-wordmark.svg', svgDoc(WM_VB, pathEl(wordmark, BRAND.walnut, ' class="t"'), { title: 'cucuto', extra: DARK_TEXT_CSS('.t') }));
write('logo/cucuto-wordmark-light.svg', svgDoc(WM_VB, pathEl(wordmark, BRAND.walnut), { title: 'cucuto' }));
write('logo/cucuto-wordmark-dark.svg', svgDoc(WM_VB, pathEl(wordmark, BRAND.cream), { title: 'cucuto' }));
write('logo/cucuto-wordmark-mono.svg', svgDoc(WM_VB, pathEl(wordmark, 'currentColor'), { title: 'cucuto' }));

const LOGO_VB = `0 0 ${LOGO_W} 48`;
write('logo/cucuto-logo.svg', svgDoc(LOGO_VB, markBody() + pathEl(wordmarkInLogo, BRAND.walnut, ' class="t"'), { title: 'cucuto', extra: DARK_TEXT_CSS('.t') }));
write('logo/cucuto-logo-light.svg', svgDoc(LOGO_VB, markBody() + pathEl(wordmarkInLogo, BRAND.walnut), { title: 'cucuto' }));
write('logo/cucuto-logo-dark.svg', svgDoc(LOGO_VB, markBody() + pathEl(wordmarkInLogo, BRAND.cream), { title: 'cucuto' }));
write('logo/cucuto-logo-on-color.svg', svgDoc(LOGO_VB, markBody(MARK_COLORS.onColor) + pathEl(wordmarkInLogo, BRAND.walnut), { title: 'cucuto' }));
write('logo/cucuto-logo-mono.svg', svgDoc(LOGO_VB, pathEl(markMono, 'currentColor') + pathEl(wordmarkInLogo, 'currentColor'), { title: 'cucuto' }));

// ------------------------------------------------------------------------------------------------ app icon

// Adaptive icon: 108 × 108 dp canvas, 72 dp visible viewport, 66 dp safe circle (radius 33 around 54,54).
// The house's farthest point from its visual center is ~26.5 grid units, so scale 1.22 keeps it inside r 33.
const ICON = { scale: 1.22, cx: 24, cy: 24.3 };
const iconTx = 54 - ICON.cx * ICON.scale;
const iconTy = 54 - ICON.cy * ICON.scale;
const iconBg = BRAND.tangerine;
const iconColors = MARK_COLORS.onColor;

// Background: tangerine with soft "sound rings" spreading from the door, a hint of the voice. They sit in the
// background layer so launchers' parallax moves them against the house.
const ringCenter = [iconTx + 23.5 * ICON.scale, iconTy + 30 * ICON.scale];
const rings = [31, 41, 51].map((r, i) => pathData(subtract(circle(ringCenter[0], ringCenter[1], r + 3.2), circle(ringCenter[0], ringCenter[1], r))));
const RING_COLOR = '#FF8048';

const fgPaths = Object.fromEntries(LAYERS.map((l) => [l, pathData(transform(mp[l], { scale: ICON.scale, tx: iconTx, ty: iconTy }))]));
const monoPath = pathData(transform(markMono, { scale: ICON.scale, tx: iconTx, ty: iconTy }));
const bgRect = 'M0 0H108V108H0Z';

const fgSvg = svgDoc('0 0 108 108', LAYERS.map((l) => pathEl(fgPaths[l], iconColors[l])).join(''), { title: 'cucuto icon foreground' });
const bgSvg = svgDoc('0 0 108 108', pathEl(bgRect, iconBg) + rings.map((r) => pathEl(r, RING_COLOR)).join(''), { title: 'cucuto icon background' });
const monoSvg = svgDoc('0 0 108 108', pathEl(monoPath, '#000000'), { title: 'cucuto icon monochrome' });
write('app-icon/source/ic_launcher_foreground.svg', fgSvg);
write('app-icon/source/ic_launcher_background.svg', bgSvg);
write('app-icon/source/ic_launcher_monochrome.svg', monoSvg);

const iconLayers = pathEl(bgRect, iconBg) + rings.map((r) => pathEl(r, RING_COLOR)).join('') + LAYERS.map((l) => pathEl(fgPaths[l], iconColors[l])).join('');
// Full-bleed square of the 72 dp viewport (Google Play / GitHub avatar style; the platform applies its own mask).
write('app-icon/source/cucuto-icon-full.svg', svgDoc('18 18 72 72', iconLayers, { title: 'cucuto' }));
// Pre-masked rounded square for places that show the file as is (README, website, social).
const clip = '<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="20"/></clipPath>';
write('app-icon/source/cucuto-icon-rounded.svg', svgDoc('18 18 72 72', `<defs>${clip}</defs><g clip-path="url(#m)">${iconLayers}</g>`, { title: 'cucuto' }));

png('app-icon/source/cucuto-icon-full.svg', 'app-icon/cucuto-icon-512.png', 512);
png('app-icon/source/cucuto-icon-rounded.svg', 'app-icon/cucuto-icon-rounded-512.png', 512);
jxl('app-icon/cucuto-icon-512.png');
jxl('app-icon/cucuto-icon-rounded-512.png');

// VectorDrawables (res/drawable) + adaptive icon XML (res/mipmap-anydpi; minSdk 37, so no -v26 qualifier needed).
const vd = (paths, comment) => `<?xml version="1.0" encoding="utf-8"?>
<!-- ${comment} Generated by brand/tools/build-brand.mjs; edit the geometry there, not here. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${paths.map(([d, color]) => `    <path
        android:fillColor="${color}"
        android:pathData="${d}" />`).join('\n')}
</vector>
`;
const RES = 'app-icon/android-res';
write(`${RES}/drawable/ic_launcher_foreground.xml`, vd(LAYERS.map((l) => [fgPaths[l], iconColors[l]]), 'cucuto adaptive icon foreground: the cuckoo-clock house with the bird singing out of its door.'));
write(`${RES}/drawable/ic_launcher_background.xml`, vd([[bgRect, iconBg], ...rings.map((r) => [r, RING_COLOR])], 'cucuto adaptive icon background: tangerine with sound rings.'));
write(`${RES}/drawable/ic_launcher_monochrome.xml`, vd([[monoPath, '#FF000000']], 'cucuto themed (monochrome) icon: the launcher tints this silhouette.'));
const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`;
write(`${RES}/mipmap-anydpi/ic_launcher.xml`, adaptive);
write(`${RES}/mipmap-anydpi/ic_launcher_round.xml`, adaptive);

// ------------------------------------------------------------------------------------------------ web icons

write('web/favicon.svg', svgDoc('0 0 48 48', smallBody(), { title: 'cucuto' }));
const icoSizes = [16, 32];
icoSizes.forEach((s) => png('web/favicon.svg', `.work/favicon-${s}.png`, s));
ico(icoSizes.map((s) => ({ size: s, data: readFileSync(out(`.work/favicon-${s}.png`)) })), 'web/favicon.ico');

// apple-touch-icon: opaque full-bleed square, iOS rounds the corners itself.
png('app-icon/source/cucuto-icon-full.svg', 'web/apple-touch-icon.png', 180);
// PWA "any" icons: pre-rounded with transparent corners.
png('app-icon/source/cucuto-icon-rounded.svg', 'web/icon-192.png', 192);
png('app-icon/source/cucuto-icon-rounded.svg', 'web/icon-512.png', 512);
// PWA maskable: full bleed, mark inside the 80 % safe circle (radius 40 % of the size).
const MASK = { size: 72 };
const maskScale = (MASK.size * 0.4 - 1) / 26.5;
const mTx = MASK.size / 2 - ICON.cx * maskScale;
const mTy = MASK.size / 2 - ICON.cy * maskScale;
const mRingC = [mTx + 23.5 * maskScale, mTy + 30 * maskScale];
const mRings = [44, 58, 72].map((r) => pathData(subtract(circle(mRingC[0], mRingC[1], (r + 3.2) * maskScale / ICON.scale), circle(mRingC[0], mRingC[1], r * maskScale / ICON.scale))));
write('web/source/icon-maskable.svg', svgDoc(`0 0 ${MASK.size} ${MASK.size}`, pathEl(`M0 0H${MASK.size}V${MASK.size}H0Z`, iconBg)
  + mRings.map((r) => pathEl(r, RING_COLOR)).join('')
  + LAYERS.map((l) => pathEl(pathData(transform(mp[l], { scale: maskScale, tx: mTx, ty: mTy })), iconColors[l])).join(''), { title: 'cucuto' }));
png('web/source/icon-maskable.svg', 'web/icon-maskable-512.png', 512);
for (const f of ['apple-touch-icon', 'icon-192', 'icon-512', 'icon-maskable-512']) jxl(`web/${f}.png`);

console.log('brand assets built');
