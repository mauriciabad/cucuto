<script lang="ts" module>
	import { doorOpenSvg } from '#lib/door-open.js';

	/** A Material-style soft burst: a circle whose radius waves `lobes` times. */
	function burst(cx: number, cy: number, r: number, lobes: number, depth: number, steps = 180) {
		const points: string[] = [];
		for (let i = 0; i < steps; i++) {
			const t = (i / steps) * Math.PI * 2;
			const radius = r * (1 + depth * Math.cos(lobes * t));
			points.push(
				`${(cx + radius * Math.sin(t)).toFixed(2)} ${(cy - radius * Math.cos(t)).toFixed(2)}`
			);
		}
		return `M${points.join('L')}Z`;
	}

	const backdrop = burst(50, 50, 38, 10, 0.055);

	type Particle = {
		x: number;
		y: number;
		size: number;
		shape: 'circle' | 'pill' | 'sunny';
		color: string;
		rotate: number;
	};

	// Confetti in the character accent colors (brand/README.md), like the social card.
	const particles: Particle[] = [
		{ x: 16, y: 13, size: 3.4, shape: 'sunny', color: '#3FAE5A', rotate: 0 },
		{ x: 34, y: 5, size: 2.4, shape: 'circle', color: '#3D8BFF', rotate: 0 },
		{ x: 70, y: 8, size: 2.6, shape: 'circle', color: '#FFC233', rotate: 0 },
		{ x: 88, y: 24, size: 3, shape: 'sunny', color: '#F5A61C', rotate: 20 },
		{ x: 94, y: 56, size: 3, shape: 'pill', color: '#D9468F', rotate: -30 },
		{ x: 84, y: 86, size: 3, shape: 'sunny', color: '#8E5CFF', rotate: 10 },
		{ x: 56, y: 95, size: 3.2, shape: 'pill', color: '#3FAE5A', rotate: 10 },
		{ x: 20, y: 88, size: 2.2, shape: 'circle', color: '#3D8BFF', rotate: 0 },
		{ x: 5, y: 60, size: 3, shape: 'pill', color: '#FFC233', rotate: 70 },
		{ x: 8, y: 34, size: 2.2, shape: 'circle', color: '#A83900', rotate: 0 }
	];
	const sunny = burst(0, 0, 1, 8, 0.22, 64);
</script>

<script lang="ts">
	import { MediaQuery } from 'svelte/reactivity';
	import { page } from '$app/state';
	import { localeFromPath, messages } from '#lib/i18n/index.js';

	const t = $derived(messages(localeFromPath(page.url.pathname)).clock);

	// SSR assumes motion is fine; the browser corrects it before anything plays.
	const motion = new MediaQuery('(prefers-reduced-motion: no-preference)', true);
	let plays = $state(0);
</script>

{#snippet art()}
	<svg class="backdrop" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
		<circle class="ring" cx="50" cy="50" r="44.5" />
		<circle class="ring outer" cx="50" cy="50" r="49" />
		<path class="burst" d={backdrop} />
		{#each particles as p, i (i)}
			<g transform="translate({p.x} {p.y}) rotate({p.rotate})">
				<g class="particle" style:--i={i}>
					{#if p.shape === 'circle'}
						<circle r={p.size / 2} fill={p.color} />
					{:else if p.shape === 'pill'}
						<rect
							x={-p.size}
							y={-p.size / 3}
							width={p.size * 2}
							height={(p.size * 2) / 3}
							rx={p.size / 3}
							fill={p.color}
						/>
					{:else}
						<path d={sunny} transform="scale({p.size / 2})" fill={p.color} />
					{/if}
				</g>
			</g>
		{/each}
	</svg>
	<div class="door">
		<!-- eslint-disable-next-line svelte/no-at-html-tags -- trusted brand asset bundled at build time -->
		{@html doorOpenSvg}
	</div>
{/snippet}

{#if motion.current}
	<button type="button" class="clock" aria-label={t.replay} onclick={() => plays++}>
		<!-- Re-creating the drawing restarts its CSS animations. -->
		{#key plays}
			{@render art()}
		{/key}
	</button>
{:else}
	<div class="clock" role="img" aria-label={t.still}>
		{@render art()}
	</div>
{/if}

<style>
	.clock {
		position: relative;
		display: block;
		width: 100%;
		aspect-ratio: 1;
		padding: 0;
		border: 0;
		background: none;
		color: inherit;
		border-radius: 50%;
	}

	button.clock {
		cursor: pointer;
		-webkit-tap-highlight-color: transparent;
		transition: scale var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial);
	}

	button.clock:active {
		scale: 0.97;
	}

	.backdrop {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		overflow: visible;
	}

	.ring {
		fill: none;
		stroke: var(--md-sys-color-surface-container-high);
		stroke-width: 2.6;
		opacity: 0.8;
	}

	.ring.outer {
		opacity: 0.45;
	}

	.burst {
		fill: var(--md-sys-color-surface-container-highest);
	}

	/* House centered on the burst: the house spans x 3.5–44.5 and y 5–44.5 of the 60 × 48 animation. */
	.door {
		position: absolute;
		left: 20%;
		top: 18.5%;
		width: 75%;
	}

	.door :global(svg) {
		width: 100%;
		height: auto;
	}

	/* Play once and hold the open pose (60 % of the 4.2 s loop: both calls done, bird still out). */
	.door
		:global(
			:is(
				.cucuto-door,
				.cucuto-door-back,
				.cucuto-bird,
				.cucuto-beak-upper,
				.cucuto-beak-lower,
				.cucuto-waves
			)
		) {
		animation-iteration-count: 0.6;
		animation-fill-mode: forwards;
	}

	/* Particles pop out with the bird (550 ms), on the fast spatial spring, then stay as confetti. */
	.particle {
		transform-box: fill-box;
		transform-origin: center;
	}

	@media (prefers-reduced-motion: no-preference) {
		.particle {
			animation: pop var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial)
				both;
			animation-delay: calc(550ms + var(--i) * 25ms);
		}
	}

	@keyframes pop {
		from {
			opacity: 0;
			scale: 0.2;
		}
		to {
			opacity: 1;
			scale: 1;
		}
	}
</style>
