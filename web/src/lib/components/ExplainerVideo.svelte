<!--
	The explainer video on Home (#42, ADR 0042; the brief is docs/explainer-video.md).

	- No video yet (`video` is null): the poster with a "coming soon" card.
	- Lazy: until it plays, only the poster (a lazy <picture>, JPEG XL first) is on the page; the <video> element and
	  its files load when it starts.
	- It never plays with sound on its own. Scrolled into view, it plays muted with captions, unless the viewer
	  prefers reduced motion or asked to save data: then it waits for the play button. A muted preview pauses when
	  scrolled away and resumes when it comes back.
	- Native controls (keyboard, captions menu, full screen), a captions track per language and a transcript built
	  from the captions at build time.
-->
<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { MediaQuery } from 'svelte/reactivity';
	import poster from '#lib/assets/images/explainer-poster.png?picture';
	import Icon from '#lib/components/Icon.svelte';
	import Picture from '#lib/components/Picture.svelte';
	import { messages, type Locale } from '#lib/i18n/index.js';
	import type { ExplainerVideo } from '#lib/server/explainer-video.js';

	interface Props {
		video: ExplainerVideo | null;
		locale: Locale;
	}

	let { video, locale }: Props = $props();

	const t = $derived(messages(locale).home);

	// Before hydration, assume reduced motion: nothing may start moving until the browser says it can.
	const reducedMotion = new MediaQuery('(prefers-reduced-motion: reduce)', true);

	/** `idle`: poster only. `preview`: playing muted because it scrolled into view. `playing`: the viewer pressed play. */
	let mode = $state<'idle' | 'preview' | 'playing'>('idle');
	let frame = $state<HTMLElement>();
	let player = $state<HTMLVideoElement>();
	let pausedOffscreen = false;

	const saveData = () =>
		(navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData ===
		true;

	onMount(() => {
		if (!video || !frame) return;
		const observer = new IntersectionObserver(
			([entry]) => {
				if (entry.isIntersecting) {
					if (mode === 'idle' && !reducedMotion.current && !saveData()) {
						mode = 'preview';
					} else if (mode === 'preview' && pausedOffscreen && player) {
						pausedOffscreen = false;
						player.play().catch(() => {});
					}
				} else if (mode === 'preview' && player && !player.paused) {
					pausedOffscreen = true;
					player.pause();
				}
			},
			{ threshold: 0.5 }
		);
		observer.observe(frame);
		return () => observer.disconnect();
	});

	// Start the muted preview once its element exists. Autoplay can be refused (power saving, browser settings):
	// the native controls are there either way.
	$effect(() => {
		if (mode !== 'preview' || !player) return;
		player.muted = true;
		player.play().catch(() => {});
	});

	// Reduced motion turned on while the preview runs: stop it.
	$effect(() => {
		if (reducedMotion.current && mode === 'preview') {
			pausedOffscreen = false;
			player?.pause();
		}
	});

	async function play() {
		mode = 'playing';
		await tick();
		if (!player) return;
		player.muted = false;
		// The play button is gone: keep keyboard focus on the player that replaced it.
		player.focus();
		player.play().catch(() => {});
	}

	/** Captions start on when the video plays muted, or when the viewer's language isn't the voice-over's. */
	const captionsOn = (srclang: Locale) =>
		srclang === locale && (mode === 'preview' || video?.audioLang !== locale);
</script>

<div class="explainer" class:soon={!video}>
	<div class="frame" bind:this={frame}>
		<Picture src={poster} alt={video ? '' : t.video.posterAlt} class="poster" />

		{#if video && mode !== 'idle'}
			<!-- Captions: one track per language with a file in static/video/ (the brief requires en and es). -->
			<video
				bind:this={player}
				class="player"
				controls
				playsinline
				preload="auto"
				aria-label={t.video.label}
			>
				{#each video.sources as source (source.src)}
					<source src={source.src} type={source.type} />
				{/each}
				{#each video.tracks as track (track.src)}
					<track
						kind="captions"
						src={track.src}
						srclang={track.srclang}
						label={track.label}
						default={captionsOn(track.srclang)}
					/>
				{/each}
				<p>{t.video.unsupported}</p>
			</video>
		{:else if video}
			<button type="button" class="play" onclick={play}>
				<span class="play-icon"><Icon name="play-arrow" /></span>
				<span class="visually-hidden">{t.video.play}</span>
			</button>
		{/if}
	</div>

	{#if !video}
		<div class="soon-card">
			<span class="badge">{t.video.soonBadge}</span>
			<p class="title-large">{t.videoSoonTitle}</p>
			<p class="body-medium on-surface-variant">{t.videoSoonText}</p>
		</div>
	{:else if video.transcript.length > 0}
		<details class="transcript">
			<summary class="title-medium">{t.video.transcript}</summary>
			<div class="transcript-text body-medium" lang={locale}>
				{#each video.transcript as line, i (i)}
					<p>{line}</p>
				{/each}
			</div>
		</details>
	{/if}
</div>

<style>
	.explainer {
		display: grid;
		margin-top: 1.5rem;
	}

	.frame {
		position: relative;
		overflow: hidden;
		aspect-ratio: 16 / 9;
		border-radius: 2rem;
		background: var(--md-sys-color-surface-container);
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.frame :global(.poster),
	.player {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
	}

	.frame :global(.poster) {
		object-fit: cover;
	}

	/* Transparent until the first frame decodes, so the poster shows through while it loads. */
	.player {
		background: transparent;
		object-fit: contain;
	}

	.play {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		border: 0;
		padding: 0;
		background: transparent;
		cursor: pointer;
		border-radius: inherit;
	}

	.play-icon {
		display: grid;
		place-items: center;
		width: clamp(4rem, 9vw, 6rem);
		height: clamp(4rem, 9vw, 6rem);
		border-radius: 50%;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
		fill: currentColor;
		box-shadow: 0 0.5rem 1.5rem
			color-mix(in srgb, var(--md-sys-color-shadow, #000) 25%, transparent);
		transition:
			border-radius var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial),
			scale var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial);
	}

	.play-icon :global(.icon) {
		width: 55%;
		height: 55%;
	}

	/* Expressive press: rounder square and a little smaller. */
	.play:hover .play-icon {
		border-radius: 38%;
	}

	.play:active .play-icon {
		scale: 0.92;
	}

	.play:focus-visible {
		outline: none;
	}

	.play:focus-visible .play-icon {
		outline: 3px solid var(--md-sys-color-on-surface);
		outline-offset: 4px;
	}

	/* Coming soon: the card sits on the poster's calm lower-left corner, or below it on narrow screens. */

	.soon-card {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 0.375rem;
		margin-top: 1rem;
		padding: 1.25rem 1.5rem 1.5rem;
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container-high);
	}

	.soon-card p {
		max-width: 26rem;
	}

	.badge {
		margin-bottom: 0.25rem;
		padding: 0.25rem 0.75rem;
		border-radius: 1rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
		font: 600 0.875rem/1.25rem var(--cucuto-font);
		font-variation-settings: 'ROND' var(--cucuto-rond-label);
	}

	@media (min-width: 52rem) {
		.soon > * {
			grid-area: 1 / 1;
		}

		.soon-card {
			/* Positioned, so it paints over the (positioned) frame it shares the grid cell with. */
			position: relative;
			align-self: end;
			justify-self: start;
			width: min(36%, 24rem);
			margin: 0 0 1.5rem 1.5rem;
			box-shadow: 0 0.25rem 1rem color-mix(in srgb, #000 12%, transparent);
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.play-icon {
			transition: none;
		}
	}

	/* Transcript */

	.transcript {
		margin-top: 1rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.transcript summary {
		min-height: 3rem;
		padding: 0.75rem 1.25rem;
		cursor: pointer;
		border-radius: inherit;
	}

	.transcript-text {
		display: flex;
		flex-direction: column;
		gap: 0.375rem;
		max-width: 44rem;
		padding: 0 1.25rem 1.25rem;
		color: var(--md-sys-color-on-surface-variant);
	}
</style>
