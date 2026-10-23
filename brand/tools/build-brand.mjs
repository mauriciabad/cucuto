// Builds every logo, app icon and web icon from the hand-made geometry in geometry.mjs.
//   cd brand/tools && bun install && bun run build
import { readFileSync } from 'node:fs';
import { circle } from './geometry.mjs';
import { subtract, pathData, transform, bounds } from './boolean.mjs';
import { BRAND, MARK_COLORS } from './brand-colors.mjs';
import { write, svgDoc, pathEl, png, jxl, ico, out } from './svg.mjs';
import { mp, LAYERS, markBody, markMono, smallBody, smallMono, wordmark, wordmarkInLogo, LOGO_W, WORDMARK } from './marks.mjs';

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

// Adaptive icon: 108 × 108 dp canvas, 72 dp visible viewport (the largest any mask shows: a circle of radius 36
// around 54,54), 66 dp safe circle (radius 33). Everything sits inside the circle mask, so every launcher shape
// (circle, squircle, rounded square, teardrop, cookie) shows the same complete drawing.
// The house's farthest point from its visual center is ~26.5 grid units; scale 1.06 puts it at r 28 (43.5 dp wide),
// which leaves a band for the rings between the house and the mask edge.
const ICON = { scale: 1.06, cx: 24, cy: 24.3 };
const iconTx = 54 - ICON.cx * ICON.scale;
const iconTy = 54 - ICON.cy * ICON.scale;
const iconBg = BRAND.tangerine;
const iconColors = MARK_COLORS.onColor;

// Sound rings: two full rings concentric with the icon (and the house's visual center), the voice spreading out
// and fading. Both stay inside the 72 dp circle with a 3.5 dp margin, so no mask ever cuts them into stray arcs.
// The inner ring passes behind the house's lower corners. They live in the foreground layer, locked to the house,
// so launcher motion never pulls them off-center. Colors are the logo's two light tints (apricot, door on color).
const RINGS = [
  { r: 25.5, w: 3.4, color: BRAND.apricot, monoAlpha: 0.5 },
  { r: 31, w: 3, color: MARK_COLORS.onColor.door, monoAlpha: 0.3 },
];
const ring = ({ r, w }) => pathData(subtract(circle(54, 54, r + w / 2), circle(54, 54, r - w / 2)));
const ringPaths = RINGS.map(ring);

const fgPaths = Object.fromEntries(LAYERS.map((l) => [l, pathData(transform(mp[l], { scale: ICON.scale, tx: iconTx, ty: iconTy }))]));
const monoPath = pathData(transform(markMono, { scale: ICON.scale, tx: iconTx, ty: iconTy }));
const bgRect = 'M0 0H108V108H0Z';

const fgBody = ringPaths.map((d, i) => pathEl(d, RINGS[i].color)).join('') + LAYERS.map((l) => pathEl(fgPaths[l], iconColors[l])).join('');
// Themed icons: the launcher tints the monochrome layer by its alpha, so the rings come along as fainter tints.
const monoBody = ringPaths.map((d, i) => pathEl(d, '#000000', ` fill-opacity="${RINGS[i].monoAlpha}"`)).join('') + pathEl(monoPath, '#000000');
write('app-icon/source/ic_launcher_foreground.svg', svgDoc('0 0 108 108', fgBody, { title: 'cucuto icon foreground' }));
write('app-icon/source/ic_launcher_background.svg', svgDoc('0 0 108 108', pathEl(bgRect, iconBg), { title: 'cucuto icon background' }));
write('app-icon/source/ic_launcher_monochrome.svg', svgDoc('0 0 108 108', monoBody, { title: 'cucuto icon monochrome' }));

const iconLayers = pathEl(bgRect, iconBg) + fgBody;
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
// `paths`: [pathData, color, alpha?]. `size`: the drawable's dp size and viewport.
const vd = (paths, comment, size = 108) => `<?xml version="1.0" encoding="utf-8"?>
<!-- ${comment} Generated by brand/tools/build-brand.mjs; edit the geometry there, not here. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="${size}"
    android:viewportHeight="${size}">
${paths.map(([d, color, alpha]) => `    <path
        android:fillColor="${color}"${alpha === undefined ? '' : `
        android:fillAlpha="${alpha}"`}
        android:pathData="${d}" />`).join('\n')}
</vector>
`;
const RES = 'app-icon/android-res';
write(`${RES}/drawable/ic_launcher_foreground.xml`, vd([
  ...ringPaths.map((d, i) => [d, RINGS[i].color]),
  ...LAYERS.map((l) => [fgPaths[l], iconColors[l]]),
], 'cucuto adaptive icon foreground: the cuckoo-clock house with the bird singing out of its door, inside two sound rings.'));
write(`${RES}/drawable/ic_launcher_background.xml`, vd([[bgRect, iconBg]], 'cucuto adaptive icon background: tangerine.'));
write(`${RES}/drawable/ic_launcher_monochrome.xml`, vd([
  ...ringPaths.map((d, i) => [d, '#FF000000', RINGS[i].monoAlpha]),
  [monoPath, '#FF000000'],
], 'cucuto themed (monochrome) icon: the launcher tints this silhouette (rings at partial alpha).'));

// Status-bar notification icon: 24 dp, white on transparent (the system uses only its alpha). The small mark's
// silhouette (bigger bird, no door) fills the 22 dp live area like other apps' icons, centered on its bounds.
const STAT = { size: 24, live: 22 };
const smallBounds = bounds(smallMono);
const statScale = STAT.live / Math.max(smallBounds.width, smallBounds.height);
const statPath = pathData(transform(smallMono, {
  scale: statScale,
  tx: STAT.size / 2 - smallBounds.center.x * statScale,
  ty: STAT.size / 2 - smallBounds.center.y * statScale,
}));
write('app-icon/source/ic_stat_cucuto.svg', svgDoc(`0 0 ${STAT.size} ${STAT.size}`, pathEl(statPath, '#FFFFFF'), { title: 'cucuto notification icon' }));
write(`${RES}/drawable/ic_stat_cucuto.xml`, vd([[statPath, '#FFFFFFFF']], 'cucuto status-bar notification icon: white silhouette filling the 22 dp live area of a 24 dp icon.', STAT.size));
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
// PWA maskable: the same drawing as the adaptive icon, framed so the outer ring's edge sits on the 80 % safe circle
// (radius 40 % of the size): the outer ring stays whole under any mask.
const ringEdge = RINGS.at(-1).r + RINGS.at(-1).w / 2;
const maskHalf = ringEdge / 0.8; // half the side; the safe radius is 0.8 of it
write('web/source/icon-maskable.svg', svgDoc(`${54 - maskHalf} ${54 - maskHalf} ${2 * maskHalf} ${2 * maskHalf}`, iconLayers, { title: 'cucuto' }));
png('web/source/icon-maskable.svg', 'web/icon-maskable-512.png', 512);
for (const f of ['apple-touch-icon', 'icon-192', 'icon-512', 'icon-maskable-512']) jxl(`web/${f}.png`);

console.log('brand assets built');
