// The signature "door pops open" animation, driven by real spring physics with the Material 3 Expressive
// motion tokens (androidx.compose.material3.tokens.ExpressiveMotionTokens). One pose(t) function feeds both the
// CSS keyframes of brand/motion/door-open.svg and the still frames in the motion filmstrip.
import { markPaths, MARK, arcStroke } from './geometry.mjs';

export const SPRINGS = {
  fastSpatial: { damping: 0.6, stiffness: 800 },
  defaultSpatial: { damping: 0.8, stiffness: 380 },
  slowSpatial: { damping: 0.8, stiffness: 200 },
  fastEffects: { damping: 1, stiffness: 3800 },
  defaultEffects: { damping: 1, stiffness: 1600 },
  slowEffects: { damping: 1, stiffness: 800 },
};

/** Position (0 → 1) and velocity (per second) of a unit-mass spring released at rest. */
export function spring(t, { damping: z, stiffness: k }) {
  if (t <= 0) return { x: 0, v: 0 };
  const w0 = Math.sqrt(k);
  if (z < 1) {
    const wd = w0 * Math.sqrt(1 - z * z);
    const e = Math.exp(-z * w0 * t);
    const x = 1 - e * (Math.cos(wd * t) + ((z * w0) / wd) * Math.sin(wd * t));
    const v = e * ((w0 * w0) / wd) * Math.sin(wd * t);
    return { x, v };
  }
  const e = Math.exp(-w0 * t);
  return { x: 1 - e * (1 + w0 * t), v: e * w0 * w0 * t };
}

/** A value animated by a chain of springs: segments [{ at, to, spec }], starting from `from`. */
const track = (from, segments) => (t) => {
  let value = from;
  let velocity = 0;
  let prev = from;
  for (const s of segments) {
    if (t < s.at) break;
    const { x, v } = spring(t - s.at, SPRINGS[s.spec]);
    value = prev + (s.to - prev) * x;
    velocity = (s.to - prev) * v;
    prev = s.to;
  }
  return { value, velocity };
};

export const LOOP = 4.2; // seconds
export const T = { doorOpen: 0.45, birdOut: 0.55, call1: 1.15, call2: 1.6, birdIn: 2.9, doorClose: 3.15 };

// Door: scaleX about a hinge just inside the opening's left rim. 1 = closed (covers the opening), -0.32 = open.
const HINGE = [15.5, MARK.opening.cy];
const DOOR_OPEN = -0.32;
const door = track(1, [
  { at: T.doorOpen, to: DOOR_OPEN, spec: 'fastSpatial' },
  { at: T.doorClose, to: 1, spec: 'defaultSpatial' },
]);
// Bird: 0 = tucked inside, 1 = leaning out.
const bird = track(0, [
  { at: T.birdOut, to: 1, spec: 'fastSpatial' },
  { at: T.birdIn, to: 0, spec: 'defaultSpatial' },
]);
// Each "cu-ckoo" call: beak opens (fast spatial) then closes (default spatial); the head squashes on the beat.
const call = (at) => [
  { at, to: 1, spec: 'fastSpatial' },
  { at: at + 0.16, to: 0, spec: 'defaultSpatial' },
];
const beak = track(0, [...call(T.call1), ...call(T.call2)]);
// Sound arcs: effects springs (no overshoot) for opacity, spatial for their outward travel.
const wave = (at) => track(0, [{ at, to: 1, spec: 'defaultEffects' }, { at: at + 0.22, to: 0, spec: 'slowEffects' }]);
const waveTravel = (at) => track(0, [{ at, to: 1, spec: 'slowSpatial' }, { at: at + 0.6, to: 0, spec: 'fastEffects' }]);
const waves = [wave(T.call1), wave(T.call2)];
const travels = [waveTravel(T.call1), waveTravel(T.call2)];

// 2D affine matrices [a b c d e f].
const about = ([ox, oy], sx, sy, tx = 0, ty = 0) => [sx, 0, 0, sy, ox + tx - sx * ox, oy + ty - sy * oy];
const rotateAbout = ([ox, oy], deg) => {
  const r = (deg * Math.PI) / 180;
  const c = Math.cos(r);
  const s = Math.sin(r);
  return [c, s, -s, c, ox - c * ox + s * oy, oy - s * ox - c * oy];
};
const HEAD = [MARK.head.cx, MARK.head.cy];

/** The full pose at time t (seconds). */
export function pose(t) {
  const d = door(t);
  const b = bird(t);
  const k = beak(t);
  // Squash and stretch: stretch along the motion while moving fast, keep the area constant.
  // The bird travels along +x, so it stretches in x and thins in y; each call squashes it vertically.
  const stretch = Math.max(-0.2, Math.min(0.2, b.velocity * 0.012));
  const callSquash = -0.09 * Math.max(0, k.value);
  const scale = 0.35 + 0.65 * Math.max(0, b.value);
  const sx = (scale * (1 + stretch)) / (1 + callSquash);
  const sy = (scale * (1 + callSquash)) / (1 + stretch);
  const birdM = about(HEAD, sx, sy, -6 * (1 - b.value), 0);
  const doorSettledOpen = t > T.doorOpen + 0.4 && t < T.doorClose;
  const wavesNow = waves.map((w, i) => ({ opacity: Math.max(0, Math.min(1, w(t).value)), travel: travels[i](t).value }));
  const waveOpacity = Math.max(...wavesNow.map((w) => w.opacity));
  const waveTravelNow = wavesNow.reduce((a, w) => (w.opacity > 0.01 ? Math.max(a, w.travel) : a), 0);
  return {
    door: about(HINGE, d.value, 1),
    doorFrontOpacity: doorSettledOpen ? 0 : 1,
    doorBackOpacity: doorSettledOpen ? 1 : 0,
    bird: birdM,
    beakUpper: rotateAbout([30.2, 27.6], -10 * Math.max(0, k.value)),
    beakLower: rotateAbout([30.6, 32.4], 22 * Math.max(0, k.value)),
    waves: about([33, MARK.opening.cy], 0.85 + 0.25 * waveTravelNow, 0.85 + 0.25 * waveTravelNow, 2 * waveTravelNow, 0),
    waveOpacity,
  };
}

const mp = markPaths();
const r = (n) => Number(n.toFixed(3));
const m = (a) => `matrix(${a.map(r).join(',')})`;
export const WAVE_PATHS = [9.5, 14, 18.5].map((rad) => arcStroke(33, MARK.opening.cy, rad, -32, 32, 2.6)).join('');

/** Layered SVG body for a pose (viewBox 0 0 64 48). `css` adds class hooks instead of baked transforms. */
export function bodyFor(colors, pz, { animated = false } = {}) {
  const attr = (name, matrix, extra = '') => (animated ? ` class="${name}"${extra}` : ` transform="${m(matrix)}"${extra}`);
  const op = (v) => (animated ? '' : ` opacity="${v}"`);
  // Closed door: a disc over the opening, a touch darker at its rim so it reads as a door.
  const doorDisc = `M${MARK.opening.cx - 10} ${MARK.opening.cy}a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z`;
  const knob = `M${MARK.opening.cx + 5.5} ${MARK.opening.cy}a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z`;
  return [
    `<path d="${mp.house}" fill="${colors.house}"/>`,
    `<path d="${mp.door}" fill="${colors.door}"${animated ? ' class="door-back"' : op(pz.doorBackOpacity)}/>`,
    `<path d="${mp.opening}" fill="${colors.opening}"/>`,
    `<g${attr('bird', pz.bird)}>`,
    `<path d="${mp.head}" fill="${colors.head}"/>`,
    `<path d="${mp.eye}" fill="${colors.eye}"/>`,
    `<path d="${upperBeak}" fill="${colors.beak}"${attr('beak-upper', pz.beakUpper)}/>`,
    `<path d="${lowerBeak}" fill="${colors.beak}"${attr('beak-lower', pz.beakLower)}/>`,
    '</g>',
    `<g${attr('door', pz.door)}${animated ? '' : op(pz.doorFrontOpacity)}><path d="${doorDisc}" fill="${colors.door}"/><path d="${knob}" fill="${colors.opening}"/></g>`,
    `<g${attr('waves', pz.waves)}${animated ? '' : op(pz.waveOpacity)}><path d="${WAVE_PATHS}" fill="${colors.waves}"/></g>`,
  ].join('');
}

// Beak halves as separate paths (the mark's beak path holds both subpaths).
const [upperBeak, lowerBeak] = mp.beak.split('Z').filter(Boolean).map((s) => `${s}Z`);

/** CSS keyframes sampled from the springs every 20 ms; identical consecutive frames are dropped. */
export function keyframesCss() {
  const STEP = 0.02;
  const props = {
    door: (p) => `transform:${m(p.door)}`,
    'door-front': (p) => `opacity:${p.doorFrontOpacity}`,
    'door-back': (p) => `opacity:${p.doorBackOpacity}`,
    bird: (p) => `transform:${m(p.bird)}`,
    'beak-upper': (p) => `transform:${m(p.beakUpper)}`,
    'beak-lower': (p) => `transform:${m(p.beakLower)}`,
    waves: (p) => `transform:${m(p.waves)};opacity:${r(p.waveOpacity)}`,
  };
  const frames = [];
  for (let t = 0; t <= LOOP + 1e-9; t += STEP) frames.push([t, pose(t)]);
  let css = '';
  for (const [name, fn] of Object.entries(props)) {
    const values = frames.map(([t, p]) => [t, fn(p)]);
    const kept = values.filter(([, v], i) => i === 0 || i === values.length - 1 || v !== values[i - 1][1] || v !== values[i + 1][1]);
    css += `@keyframes ${name}{${kept.map(([t, v]) => `${r((t / LOOP) * 100)}%{${v}}`).join('')}}\n`;
  }
  return css;
}
