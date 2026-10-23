// Builds brand/motion/door-open.svg (CSS-animated, spring-sampled) and a still filmstrip of its key poses.
import { pose, bodyFor, keyframesCss, LOOP, T } from './motion.mjs';
import { MARK_COLORS, BRAND } from './brand-colors.mjs';
import { write, svgDoc, png, jxl } from './svg.mjs';
import { t } from './text.mjs';

const colors = { ...MARK_COLORS.full, waves: BRAND.tangerine };
const VB = '0 0 60 48';

// Reduced motion and non-animating renderers show the settled "open" pose.
const rest = pose(2.5);
const m = (a) => `matrix(${a.map((n) => Number(n.toFixed(3))).join(',')})`;
const css = `
.door,.bird,.beak-upper,.beak-lower,.waves,.door-back{transform-box:view-box;transform-origin:0 0;animation-duration:${LOOP}s;animation-iteration-count:infinite;animation-timing-function:linear}
.door{transform:${m(rest.door)};opacity:0;animation-name:door,door-front}
.door-back{opacity:1;animation-name:door-back}
.bird{animation-name:bird}
.beak-upper{animation-name:beak-upper}
.beak-lower{animation-name:beak-lower}
.waves{opacity:0;animation-name:waves}
@media (prefers-reduced-motion: reduce){.door,.bird,.beak-upper,.beak-lower,.waves,.door-back{animation:none}}
${keyframesCss()}`;

write('motion/door-open.svg', svgDoc(VB, bodyFor(colors, rest, { animated: true }), {
  title: 'cucuto: the cuckoo door pops open and the bird sings',
  width: 480,
  extra: `<style>${css}</style>`,
}));

// Filmstrip of key poses (static transforms baked in).
const times = [0.4, T.doorOpen + 0.06, T.birdOut + 0.06, T.birdOut + 0.14, 0.9, T.call1 + 0.05, T.call1 + 0.25, T.birdIn + 0.12, T.doorClose + 0.12, 3.8];
const FW = 150;
const FH = 150;
let strip = `<rect width="${times.length * FW}" height="${FH + 34}" fill="${BRAND.cream}"/>`;
times.forEach((time, i) => {
  strip += `<svg x="${i * FW + 15}" y="10" width="${FW - 30}" height="${FH - 30}" viewBox="${VB}">${bodyFor(colors, pose(time))}</svg>`;
  strip += t(`${Math.round(time * 1000)} ms`, { x: i * FW + FW / 2, y: FH + 10, size: 18, fill: BRAND.walnut, anchor: 'middle', wght: 600 });
});
write('motion/door-open-frames.svg', svgDoc(`0 0 ${times.length * FW} ${FH + 34}`, strip, { title: 'cucuto door-open animation, key poses' }));
png('motion/door-open-frames.svg', 'motion/door-open-frames.png', times.length * FW);
jxl('motion/door-open-frames.png');
console.log('motion built');
