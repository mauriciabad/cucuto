<script lang="ts">
	import { page } from '$app/state';
	import DownloadApk from '#lib/components/DownloadApk.svelte';
	import HeroClock from '#lib/components/HeroClock.svelte';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).home);

	// Character accents (brand/README.md) give each card its own color.
	const stepAccents = ['leaf', 'sky', 'berry'];
	const shotAccents = ['leaf', 'sky', 'berry', 'plum'];
</script>

<Seo />

<section class="hero">
	<div class="container hero-inner">
		<div class="hero-copy">
			<p class="eyebrow">{t.eyebrow}</p>
			<h1 class="display-large emphasized">{t.pitch}</h1>
			<p class="lead body-large">{t.lead}</p>
			<div class="actions">
				<DownloadApk release={data.appRelease} />
				<a class="button tonal" href={pageHref('/install/', locale)}>
					{t.howToInstall}
					<Icon name="arrow-forward" />
				</a>
			</div>
			<p class="fine-print"><Html text={t.finePrintHtml} /></p>
		</div>
		<div class="hero-art">
			<HeroClock />
		</div>
	</div>
</section>

<!--
	Explainer video slot (#42). Until the video exists this is a placeholder; the video goes here as
	static/video/explainer.webm + .mp4 with a poster and WebVTT captions, lazy-loaded (preload="none"), never
	autoplaying with sound.
-->
<section class="section" aria-labelledby="video-title">
	<div class="container">
		<h2 id="video-title" class="headline-large">{t.videoTitle}</h2>
		<div class="video-slot">
			<span class="video-icon"><Icon name="play-circle" /></span>
			<p class="title-large">{t.videoSoonTitle}</p>
			<p class="body-medium on-surface-variant">{t.videoSoonText}</p>
		</div>
	</div>
</section>

<section class="section" aria-labelledby="how-title">
	<div class="container">
		<h2 id="how-title" class="headline-large">{t.howTitle}</h2>
		<ol class="steps">
			{#each t.steps as step, i (step.title)}
				<li class="step card" data-character={stepAccents[i % stepAccents.length]}>
					<span class="step-badge" aria-hidden="true">{i + 1}</span>
					<Icon name={step.icon} class="step-icon" />
					<h3 class="title-large">{step.title}</h3>
					<p class="on-surface-variant">{step.text}</p>
				</li>
			{/each}
		</ol>
	</div>
</section>

<section class="section" aria-labelledby="features-title">
	<div class="container">
		<h2 id="features-title" class="headline-large">{t.featuresTitle}</h2>
		<p class="section-lead on-surface-variant">{t.featuresLead}</p>
		<ul class="features">
			{#each t.features as item (item.title)}
				<li class="feature">
					<Icon name={item.icon} class="feature-icon" />
					<h3 class="title-medium">{item.title}</h3>
					<p class="body-medium on-surface-variant">{item.text}</p>
				</li>
			{/each}
		</ul>
	</div>
</section>

<!--
	Screenshot slots (#41). Placeholders until the scripted screenshots from the demo packs exist; each becomes a
	<Picture> of the matching screen.
-->
<section class="section" aria-labelledby="screenshots-title">
	<div class="container">
		<h2 id="screenshots-title" class="headline-large">{t.screenshotsTitle}</h2>
		<p class="section-lead on-surface-variant">{t.screenshotsLead}</p>
		<ul class="shots">
			{#each t.screenshots as shot, i (shot.title)}
				<li>
					<figure>
						<div class="phone" data-character={shotAccents[i % shotAccents.length]}>
							<div class="phone-screen" aria-hidden="true">
								<span class="mock-bar"></span>
								<span class="mock-avatar"></span>
								<span class="mock-line wide"></span>
								<span class="mock-line"></span>
								<span class="mock-card"></span>
								<span class="mock-card short"></span>
							</div>
							<span class="phone-badge label-medium">{t.screenshotSoon}</span>
						</div>
						<figcaption>
							<span class="title-medium">{shot.title}</span>
							<span class="body-medium on-surface-variant">{shot.text}</span>
						</figcaption>
					</figure>
				</li>
			{/each}
		</ul>
	</div>
</section>

<section class="section" aria-labelledby="requirements-title">
	<div class="container">
		<h2 id="requirements-title" class="headline-large">{t.requirementsTitle}</h2>
		<p class="section-lead on-surface-variant">{t.requirementsLead}</p>
		<ul class="requirements">
			{#each t.requirements as item (item.title)}
				<li class="requirement">
					<span class="requirement-icon"><Icon name={item.icon} /></span>
					<div>
						<h3 class="title-medium">{item.title}</h3>
						<p class="body-medium on-surface-variant">{item.text}</p>
					</div>
				</li>
			{/each}
		</ul>
	</div>
</section>

<section class="section" aria-labelledby="faq-title">
	<div class="container faq-layout">
		<h2 id="faq-title" class="headline-large">{t.faqTitle}</h2>
		<div class="faq">
			{#each t.faq as item (item.q)}
				<details>
					<summary class="title-medium">{item.q}</summary>
					<p class="on-surface-variant"><Html text={item.aHtml} /></p>
				</details>
			{/each}
		</div>
	</div>
</section>

<style>
	.hero {
		padding-block: 3rem 2rem;
		overflow-x: clip;
	}

	.hero-inner {
		display: grid;
		grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
		align-items: center;
		gap: 2rem 3rem;
	}

	.eyebrow {
		display: inline-block;
		padding: 0.375rem 0.875rem;
		border-radius: 1rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
		font: 600 0.875rem/1.25rem var(--cucuto-font);
		font-variation-settings: 'ROND' var(--cucuto-rond-label);
	}

	h1 {
		margin-top: 1.25rem;
		font-size: clamp(2.75rem, 1.6rem + 4.2vw, 4.5rem);
		line-height: 1.05;
		letter-spacing: -0.02em;
	}

	.lead {
		margin-top: 1.25rem;
		max-width: 34rem;
		font-size: 1.125rem;
		line-height: 1.75rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
		margin-top: 2rem;
	}

	.fine-print {
		margin-top: 1.25rem;
		font-size: 0.875rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.hero-art {
		width: min(100%, 30rem);
		justify-self: center;
	}

	@media (max-width: 52rem) {
		.hero-inner {
			grid-template-columns: 1fr;
		}

		.hero-art {
			grid-row: 1;
			width: min(80%, 22rem);
		}
	}

	.section {
		padding-block: 3rem;
	}

	.section-lead {
		margin-top: 0.75rem;
		max-width: 40rem;
	}

	.steps {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
		gap: 1rem;
		margin-top: 2rem;
		padding: 0;
		list-style: none;
		counter-reset: none;
	}

	.step {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 2rem 1.5rem 1.75rem;
		background: var(--md-sys-color-surface-container);
		transition:
			border-radius var(--cucuto-spring-default-spatial-duration)
				var(--cucuto-spring-default-spatial),
			translate var(--cucuto-spring-default-spatial-duration) var(--cucuto-spring-default-spatial);
	}

	.step:hover {
		border-radius: 2.5rem;
		translate: 0 -4px;
	}

	.step-badge {
		position: absolute;
		top: 1.25rem;
		right: 1.5rem;
		font: 800 3rem/1 var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
		color: var(--md-sys-color-primary);
		opacity: 0.25;
	}

	.step :global(.step-icon) {
		width: 3.5rem;
		height: 3.5rem;
		padding: 0.875rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-primary-container);
		fill: var(--md-sys-color-on-primary-container);
		box-sizing: border-box;
	}

	/* Video slot (#42) */

	.video-slot {
		--r1: 7rem;
		--r2: 9rem;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		margin-top: 1.5rem;
		aspect-ratio: 21 / 9;
		padding: 2rem 1.5rem;
		border-radius: 2rem;
		text-align: center;
		/* Two soft circles peeking in from the corners, like the hero's confetti. */
		background:
			radial-gradient(
				circle at 0% 0%,
				color-mix(in srgb, var(--md-sys-color-primary-container) 45%, transparent) 0 var(--r1),
				transparent calc(var(--r1) + 1px)
			),
			radial-gradient(
				circle at 100% 100%,
				color-mix(in srgb, var(--md-sys-color-tertiary-container) 50%, transparent) 0 var(--r2),
				transparent calc(var(--r2) + 1px)
			),
			var(--md-sys-color-surface-container);
	}

	.video-slot p {
		max-width: 32rem;
	}

	.video-icon {
		display: grid;
		place-items: center;
		width: 4.5rem;
		height: 4.5rem;
		margin-bottom: 0.5rem;
		border-radius: 50%;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
	}

	.video-icon :global(.icon) {
		width: 2.5rem;
		height: 2.5rem;
	}

	@media (max-width: 40rem) {
		.video-slot {
			--r1: 3.5rem;
			--r2: 4rem;
			aspect-ratio: auto;
			min-height: 16rem;
		}
	}

	/* Features */

	.features {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
		gap: 1.5rem 2rem;
		margin-top: 2rem;
		padding: 0;
		list-style: none;
	}

	.feature {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.feature :global(.feature-icon) {
		width: 2rem;
		height: 2rem;
		margin-bottom: 0.25rem;
		fill: var(--md-sys-color-primary);
	}

	/* Screenshot slots (#41) */

	.shots {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
		gap: 1.5rem 1rem;
		margin-top: 2rem;
		padding: 0;
		list-style: none;
	}

	.phone {
		position: relative;
		aspect-ratio: 9 / 19;
		max-width: 15rem;
		margin-inline: auto;
		padding: 0.5rem;
		border-radius: 2rem;
		background: var(--md-sys-color-surface-container-highest);
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.phone-screen {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		height: 100%;
		padding: 1.25rem 0.75rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-surface-container);
	}

	.phone-screen span {
		display: block;
		border-radius: 0.5rem;
		background: color-mix(in srgb, var(--md-sys-color-primary) 22%, transparent);
	}

	.mock-bar {
		height: 0.5rem;
		width: 35%;
		margin-bottom: 0.5rem;
	}

	.phone-screen .mock-avatar {
		width: 3rem;
		height: 3rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-primary-container);
	}

	.mock-line {
		height: 0.625rem;
		width: 50%;
	}

	.mock-line.wide {
		width: 80%;
		height: 0.875rem;
	}

	.phone-screen .mock-card {
		height: 3.5rem;
		margin-top: 0.5rem;
		border-radius: 1rem;
		background: color-mix(in srgb, var(--md-sys-color-primary) 12%, transparent);
	}

	.mock-card.short {
		height: 2.5rem;
	}

	.phone-badge {
		position: absolute;
		left: 50%;
		bottom: 1.5rem;
		translate: -50% 0;
		padding: 0.25rem 0.75rem;
		border-radius: 1rem;
		background: var(--md-sys-color-surface);
		color: var(--md-sys-color-on-surface);
		font: 600 0.75rem/1rem var(--cucuto-font);
		white-space: nowrap;
	}

	figcaption {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		margin-top: 0.75rem;
		text-align: center;
	}

	.requirements {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
		gap: 1rem;
		margin-top: 1.5rem;
		padding: 0;
		list-style: none;
	}

	.requirement {
		display: flex;
		gap: 1rem;
		align-items: flex-start;
		padding: 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.requirement p {
		margin-top: 0.25rem;
	}

	.requirement-icon {
		display: grid;
		place-items: center;
		flex: none;
		width: 2.75rem;
		height: 2.75rem;
		border-radius: 50%;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	.faq-layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
		gap: 1.5rem 3rem;
		align-items: start;
	}

	@media (max-width: 52rem) {
		.faq-layout {
			grid-template-columns: 1fr;
		}
	}

	.faq {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	details {
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container-low);
		transition: border-radius var(--cucuto-spring-default-spatial-duration)
			var(--cucuto-spring-default-spatial);
	}

	details[open] {
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container);
	}

	summary {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 3.5rem;
		padding: 0.875rem 1.25rem;
		cursor: pointer;
		list-style: none;
		border-radius: inherit;
	}

	summary::-webkit-details-marker {
		display: none;
	}

	summary::after {
		content: '';
		flex: none;
		width: 0.625rem;
		height: 0.625rem;
		margin-right: 0.25rem;
		border-right: 2.5px solid currentColor;
		border-bottom: 2.5px solid currentColor;
		border-radius: 2px;
		rotate: 45deg;
		translate: 0 -0.15rem;
		transition: rotate var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial);
	}

	details[open] summary::after {
		rotate: 225deg;
		translate: 0 0.15rem;
	}

	details p {
		padding: 0 1.25rem 1.25rem;
	}
</style>
