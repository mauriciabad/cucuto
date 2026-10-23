// Approximations of a few Material 3 Expressive MaterialShapes for illustrations (preview, Open Graph image).
// In the app, always use androidx.compose.material3.MaterialShapes; these only mimic their silhouettes.
const f = (n) => Number(n.toFixed(2));

/** Polar shape r(θ) sampled as a closed polyline path. */
function polar(cx, cy, rFn, steps = 360, rotate = -90) {
  let d = '';
  for (let i = 0; i < steps; i++) {
    const a = (i / steps) * Math.PI * 2;
    const r = rFn(a);
    const x = cx + r * Math.cos(a + (rotate * Math.PI) / 180);
    const y = cy + r * Math.sin(a + (rotate * Math.PI) / 180);
    d += `${i ? 'L' : 'M'}${f(x)} ${f(y)}`;
  }
  return `${d}Z`;
}

/** Scalloped "cookie" (MaterialShapes.Cookie4Sided … Cookie12Sided): n soft bumps. */
export const cookie = (cx, cy, R, n = 9, depth = 0.08) =>
  polar(cx, cy, (a) => R * (1 - depth + depth * Math.cos(n * a)));

/** "Sunny" (MaterialShapes.Sunny / VerySunny): more, sharper rays. */
export const sunny = (cx, cy, R, n = 8, depth = 0.1) =>
  polar(cx, cy, (a) => R * (1 - depth + depth * Math.sign(Math.cos(n * a)) * Math.abs(Math.cos(n * a)) ** 0.6));

/** "Clover4Leaf". */
export const clover = (cx, cy, R, n = 4) => polar(cx, cy, (a) => R * (0.72 + 0.28 * Math.abs(Math.cos((n / 2) * a)) ** 0.8), 360, -45);

/** "SoftBurst": many shallow points. */
export const softBurst = (cx, cy, R, n = 10) => polar(cx, cy, (a) => R * (0.86 + 0.14 * Math.abs(Math.cos((n / 2) * a)) ** 2));

export const circlePath = (cx, cy, r) => `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0Z`;

export const roundRect = (x, y, w, h, r) =>
  `M${x + r} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;

/** "Arch" (MaterialShapes.Arch): round top, flat-ish bottom with soft corners. */
export const arch = (x, y, w, h) => {
  const r = w / 2;
  const c = w * 0.12;
  return `M${x} ${y + r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${y + h - c}A${c} ${c} 0 0 1 ${x + w - c} ${y + h}H${x + c}A${c} ${c} 0 0 1 ${x} ${y + h - c}Z`;
};
