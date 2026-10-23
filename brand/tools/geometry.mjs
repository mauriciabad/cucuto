// Hand-built geometry for the Cucuto mark and wordmark. Every shape is a plain filled path (no strokes, no fonts),
// so the same data feeds SVG, Android VectorDrawable and rasters.

const f = (n) => Number(n.toFixed(3)).toString();

/**
 * A polygon with rounded corners (circular arcs tangent to both edges), as an SVG path.
 * `points` is [[x, y], ...] in clockwise order; `radii` is one radius or one per corner.
 */
export function roundedPolygon(points, radii) {
  const n = points.length;
  const rs = Array.isArray(radii) ? radii : points.map(() => radii);
  const corners = points.map((p, i) => {
    const prev = points[(i - 1 + n) % n];
    const next = points[(i + 1) % n];
    const v1 = [prev[0] - p[0], prev[1] - p[1]];
    const v2 = [next[0] - p[0], next[1] - p[1]];
    const l1 = Math.hypot(...v1);
    const l2 = Math.hypot(...v2);
    const u1 = [v1[0] / l1, v1[1] / l1];
    const u2 = [v2[0] / l2, v2[1] / l2];
    const angle = Math.acos(Math.max(-1, Math.min(1, u1[0] * u2[0] + u1[1] * u2[1])));
    let r = rs[i];
    let t = r / Math.tan(angle / 2);
    const maxT = Math.min(l1, l2) / 2;
    if (t > maxT) { t = maxT; r = t * Math.tan(angle / 2); }
    const cross = u1[0] * u2[1] - u1[1] * u2[0];
    return {
      start: [p[0] + u1[0] * t, p[1] + u1[1] * t],
      end: [p[0] + u2[0] * t, p[1] + u2[1] * t],
      r,
      sweep: cross < 0 ? 1 : 0,
    };
  });
  let d = `M${f(corners[0].start[0])} ${f(corners[0].start[1])}`;
  corners.forEach((c, i) => {
    if (i > 0) d += `L${f(c.start[0])} ${f(c.start[1])}`;
    d += `A${f(c.r)} ${f(c.r)} 0 0 ${c.sweep} ${f(c.end[0])} ${f(c.end[1])}`;
  });
  return `${d}Z`;
}

/** Circle as a path (two arcs), so VectorDrawable can use it too. `ccw` reverses direction for cutouts. */
export function circle(cx, cy, r, ccw = false) {
  const s = ccw ? 0 : 1;
  return `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 ${s} ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 ${s} ${f(cx - r)} ${f(cy)}Z`;
}

export function ellipse(cx, cy, rx, ry, ccw = false) {
  const s = ccw ? 0 : 1;
  return `M${f(cx)} ${f(cy - ry)}A${f(rx)} ${f(ry)} 0 1 ${s} ${f(cx)} ${f(cy + ry)}A${f(rx)} ${f(ry)} 0 1 ${s} ${f(cx)} ${f(cy - ry)}Z`;
}

/** Thick stroke along a center line with round caps, as a filled outline path: a capsule from p to q. */
export function capsule([x1, y1], [x2, y2], w) {
  const r = w / 2;
  const dx = x2 - x1;
  const dy = y2 - y1;
  const l = Math.hypot(dx, dy);
  const nx = (-dy / l) * r;
  const ny = (dx / l) * r;
  return `M${f(x1 + nx)} ${f(y1 + ny)}L${f(x2 + nx)} ${f(y2 + ny)}A${f(r)} ${f(r)} 0 0 0 ${f(x2 - nx)} ${f(y2 - ny)}L${f(x1 - nx)} ${f(y1 - ny)}A${f(r)} ${f(r)} 0 0 0 ${f(x1 + nx)} ${f(y1 + ny)}Z`;
}

/**
 * Thick circular arc with round caps as a filled outline. Angles in degrees, 0 = +x, clockwise (SVG y-down),
 * from `a0` sweeping clockwise to `a1`.
 */
export function arcStroke(cx, cy, r, a0, a1, w) {
  const ro = r + w / 2;
  const ri = r - w / 2;
  const rad = (a) => (a * Math.PI) / 180;
  const pt = (rr, a) => [cx + rr * Math.cos(rad(a)), cy + rr * Math.sin(rad(a))];
  const sweepDeg = ((a1 - a0) % 360 + 360) % 360;
  const large = sweepDeg > 180 ? 1 : 0;
  const [o0, o1, i1, i0] = [pt(ro, a0), pt(ro, a1), pt(ri, a1), pt(ri, a0)];
  const c = w / 2;
  return `M${f(o0[0])} ${f(o0[1])}A${f(ro)} ${f(ro)} 0 ${large} 1 ${f(o1[0])} ${f(o1[1])}`
    + `A${f(c)} ${f(c)} 0 0 1 ${f(i1[0])} ${f(i1[1])}`
    + `A${f(ri)} ${f(ri)} 0 ${large} 0 ${f(i0[0])} ${f(i0[1])}`
    + `A${f(c)} ${f(c)} 0 0 1 ${f(o0[0])} ${f(o0[1])}Z`;
}

/** Moves every vertex of a convex polygon outward so each edge shifts by `g` (a miter offset). */
export function offsetPolygon(points, g) {
  const n = points.length;
  const cx = points.reduce((s, p) => s + p[0], 0) / n;
  const cy = points.reduce((s, p) => s + p[1], 0) / n;
  return points.map((p, i) => {
    const prev = points[(i - 1 + n) % n];
    const next = points[(i + 1) % n];
    const u1 = norm([p[0] - prev[0], p[1] - prev[1]]);
    const u2 = norm([next[0] - p[0], next[1] - p[1]]);
    // Outward normals (pointing away from the centroid).
    const n1 = outward([-u1[1], u1[0]], p, cx, cy);
    const n2 = outward([-u2[1], u2[0]], p, cx, cy);
    const bis = norm([n1[0] + n2[0], n1[1] + n2[1]]);
    const cosHalf = bis[0] * n1[0] + bis[1] * n1[1];
    const dist = g / cosHalf;
    return [p[0] + bis[0] * dist, p[1] + bis[1] * dist];
  });
}
const norm = ([x, y]) => { const l = Math.hypot(x, y); return [x / l, y / l]; };
const outward = (nv, p, cx, cy) => ((p[0] - cx) * nv[0] + (p[1] - cy) * nv[1] < 0 ? [-nv[0], -nv[1]] : nv);

// ---------------------------------------------------------------------------------------------------------------
// The mark: a cuckoo-clock house whose round door has popped open and a bird leans out, beak open, singing.
// 48 × 48 grid; the silhouette spans 3.5–44.5 so it keeps clear space at every size.

const BEAK_UPPER = [[29.6, 24.6], [42.6, 27.6], [30.6, 30.0]];
const BEAK_UPPER_R = [1.4, 1.4, 1.0];
const BEAK_LOWER = [[31.2, 31.3], [40.2, 35.0], [29.8, 34.9]];
const BEAK_LOWER_R = [1.0, 1.4, 1.0];

export const MARK = {
  size: 48,
  housePoints: [[24, 3], [44.5, 20.5], [44.5, 44.5], [3.5, 44.5], [3.5, 20.5]],
  houseRadii: [4.5, 3.5, 6.5, 6.5, 3.5],
  opening: { cx: 23.5, cy: 30, r: 10 },
  door: { cx: 12.4, cy: 30, rx: 3.2, ry: 9.4 },
  head: { cx: 25.2, cy: 30, r: 7 },
  eye: { cx: 26.2, cy: 27.2, r: 2 },
};

/** Colored layers of the mark, bottom to top. */
export const markPaths = () => ({
  house: roundedPolygon(MARK.housePoints, MARK.houseRadii),
  door: ellipse(MARK.door.cx, MARK.door.cy, MARK.door.rx, MARK.door.ry),
  opening: circle(MARK.opening.cx, MARK.opening.cy, MARK.opening.r),
  head: circle(MARK.head.cx, MARK.head.cy, MARK.head.r),
  beak: roundedPolygon(BEAK_UPPER, BEAK_UPPER_R) + roundedPolygon(BEAK_LOWER, BEAK_LOWER_R),
  eye: circle(MARK.eye.cx, MARK.eye.cy, MARK.eye.r),
});

/** Gap shapes used to knock the beak out of the house in single-color versions. */
export const beakGapPaths = (g = 1.6) =>
  roundedPolygon(offsetPolygon(BEAK_UPPER, g), BEAK_UPPER_R.map((r) => r + g))
  + roundedPolygon(offsetPolygon(BEAK_LOWER, g), BEAK_LOWER_R.map((r) => r + g));

/**
 * Small-size mark (favicons, 16–32 px): no door, a bigger bird, one solid beak and a bigger eye,
 * so the bird still reads when each grid unit is a third of a pixel.
 */
export const markSmallPaths = () => ({
  house: roundedPolygon([[24, 2.5], [45.5, 20.5], [45.5, 45.5], [2.5, 45.5], [2.5, 20.5]], [4, 3, 6, 6, 3]),
  opening: circle(22.5, 31, 12),
  head: circle(24.5, 31, 9),
  beak: roundedPolygon([[29.5, 24.5], [44, 30], [29.5, 35.5]], [2, 2.2, 2]),
  eye: circle(26.5, 27.5, 2.8),
});

// ---------------------------------------------------------------------------------------------------------------
// The wordmark: lowercase "cucuto" constructed from one monoline round-capped stroke (weight 10 on a 40 x-height),
// echoing Google Sans Flex at full roundness. Baseline y = 40, x-height y = 0, t ascender to y = -14.

export const WORDMARK = { stroke: 10, xHeight: 40, ascender: -14, width: 0, height: 0 };

export function wordmarkParts() {
  const w = WORDMARK.stroke;
  const parts = [];
  const letterC = (cx) => arcStroke(cx, 20, 15, 42, 318, w);
  const letterU = (x0) => [
    capsule([x0, 5], [x0, 20], w),
    arcStroke(x0 + 15, 20, 15, 0, 180, w),
    capsule([x0 + 30, 5], [x0 + 30, 35], w),
  ];
  const letterT = (x) => [
    capsule([x, -9], [x, 25], w),
    arcStroke(x + 10, 25, 10, 90, 180, w),
    capsule([x + 10, 35], [x + 13, 35], w),
    capsule([x - 4, 5], [x + 13, 5], w),
  ];
  const letterO = (cx) => ({ ring: [cx, 20, 20, 10] });
  parts.push(letterC(20));
  parts.push(...letterU(47.5));
  parts.push(letterC(108));
  parts.push(...letterU(135.5));
  parts.push(...letterT(184.5));
  parts.push(letterO(226));
  WORDMARK.width = 246;
  return parts;
}

