// An approximation of Material 3 Expressive's `Cookie9Sided` shape, the silhouette the app gives every character
// avatar (brand/README.md, Shape): a circle with nine soft scallops.

/** The cookie as an SVG path centered on (cx, cy), fitting a circle of radius r. */
export function cookiePath(
	cx: number,
	cy: number,
	r: number,
	sides = 9,
	depth = 0.05,
	steps = 180
): string {
	const points: string[] = [];
	for (let i = 0; i < steps; i++) {
		const a = (i / steps) * Math.PI * 2 - Math.PI / 2;
		const radius = r * (1 - depth + depth * Math.cos(sides * (a + Math.PI / 2)));
		points.push(
			`${(cx + radius * Math.cos(a)).toFixed(2)} ${(cy + radius * Math.sin(a)).toFixed(2)}`
		);
	}
	return `M${points.join('L')}Z`;
}

/** The same shape as a CSS `clip-path` polygon, in percentages, so it scales with the element. */
export function cookiePolygon(sides = 9, depth = 0.05, steps = 90): string {
	const points: string[] = [];
	for (let i = 0; i < steps; i++) {
		const a = (i / steps) * Math.PI * 2 - Math.PI / 2;
		const radius = 50 * (1 - depth + depth * Math.cos(sides * (a + Math.PI / 2)));
		points.push(
			`${(50 + radius * Math.cos(a)).toFixed(2)}% ${(50 + radius * Math.sin(a)).toFixed(2)}%`
		);
	}
	return `polygon(${points.join(', ')})`;
}
