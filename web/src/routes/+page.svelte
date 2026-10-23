<script lang="ts">
	import { resolve } from '$app/paths';
	import DownloadApk from '#lib/components/DownloadApk.svelte';
	import HeroClock from '#lib/components/HeroClock.svelte';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { PITCH, REPO_URL, REQUIREMENTS } from '#lib/site.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const steps: Array<{ icon: IconName; title: string; text: string; character: string }> = [
		{
			icon: 'library-music',
			title: 'Import a sound pack',
			text: 'Open a .soundpack.zip file or download one from a pack repository. A pack is a cast of characters, each with a picture, a few short clips and its own vibration.',
			character: 'leaf'
		},
		{
			icon: 'person-pin',
			title: 'Give a contact a character',
			text: 'Pick a chat, tap a character to hear it, tap again to assign. It takes a few seconds per contact.',
			character: 'sky'
		},
		{
			icon: 'notifications-active',
			title: 'Hear who it is',
			text: 'When they message you, WhatsApp’s notification plays their character’s voice and vibration. Phone in your pocket, you already know who wrote.',
			character: 'berry'
		}
	];

	const requirements: Array<{ icon: IconName; title: string; text: string }> = [
		{
			icon: 'android',
			title: REQUIREMENTS.android,
			text: 'Cucuto is built for Android 17 (API 37) only. Older versions are not supported.'
		},
		{
			icon: 'lock',
			title: 'Root access',
			text: 'Root lets the module change WhatsApp’s notification from inside Android itself.'
		},
		{
			icon: 'extension',
			title: `LSPosed with libxposed API ${REQUIREMENTS.libxposedApi}`,
			text: 'The module runs in the System Framework scope. It never hooks the WhatsApp app.'
		},
		{
			icon: 'notifications-active',
			title: 'WhatsApp or WhatsApp Business',
			text: 'Leave WhatsApp’s own tone and vibration on: Cucuto swaps them per contact.'
		}
	];

	const faq: Array<{ q: string; a: string }> = [
		{
			q: 'Does Cucuto come with sounds from shows or games?',
			a: 'No. The app ships empty and no copyrighted content ships with it. You import sound packs yourself; the official repository only hosts free demo packs (CC0). You are responsible for the packs you import.'
		},
		{
			q: 'Why does it need root?',
			a: 'On Android 17 a normal app can’t reliably play sounds from the background, and copying WhatsApp’s notifications would mean duplicates and clutter. With root, Cucuto swaps the sound and vibration on WhatsApp’s own notification, so mute, Do Not Disturb and ringer mode keep working exactly as usual.'
		},
		{
			q: 'Does it modify WhatsApp?',
			a: 'No. The module only runs in the Android system (System Framework scope) and never touches the WhatsApp process, so it coexists with other WhatsApp modules.'
		},
		{
			q: 'What happens if something goes wrong?',
			a: 'You hear WhatsApp’s normal tone. If the module is off or anything fails, Android plays the default sound instead of staying silent.'
		},
		{
			q: 'Can I make my own packs?',
			a: 'Yes. The pack format is open and documented, and anyone can host a pack repository on a static website. A pack creator for the app and this website is planned.'
		},
		{
			q: 'Is it free? Does it collect data?',
			a: 'Cucuto is free and open source under GPL-3.0-or-later. It has no accounts, no ads and no analytics. Neither does this website.'
		}
	];
</script>

<Seo />

<section class="hero">
	<div class="container hero-inner">
		<div class="hero-copy">
			<p class="eyebrow">An LSPosed module for Android 17</p>
			<h1 class="display-large emphasized">{PITCH}</h1>
			<p class="lead body-large">
				Cucuto plays a different character’s sound and vibration for each WhatsApp contact, like a
				cuckoo clock announcing who’s there. A cat for your sister, a trumpet for your boss.
			</p>
			<div class="actions">
				<DownloadApk release={data.appRelease} />
				<a class="button tonal" href={resolve('install/')}>
					How to install
					<Icon name="arrow-forward" />
				</a>
			</div>
			<p class="fine-print">
				Free and <a href={REPO_URL} rel="external">open source</a> · Needs root · No tracking
			</p>
		</div>
		<div class="hero-art">
			<HeroClock />
		</div>
	</div>
</section>

<section class="section" aria-labelledby="how-title">
	<div class="container">
		<h2 id="how-title" class="headline-large">How it works</h2>
		<ol class="steps">
			{#each steps as step, i (step.title)}
				<li class="step card" data-character={step.character}>
					<span class="step-badge" aria-hidden="true">{i + 1}</span>
					<Icon name={step.icon} class="step-icon" />
					<h3 class="title-large">{step.title}</h3>
					<p class="on-surface-variant">{step.text}</p>
				</li>
			{/each}
		</ol>
	</div>
</section>

<section class="section" aria-labelledby="requirements-title">
	<div class="container">
		<h2 id="requirements-title" class="headline-large">Requirements</h2>
		<p class="section-lead on-surface-variant">
			Cucuto is a power-user tool. Check that your phone has all of these before you install it.
		</p>
		<ul class="requirements">
			{#each requirements as item (item.title)}
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
		<h2 id="faq-title" class="headline-large">Questions</h2>
		<div class="faq">
			{#each faq as item (item.q)}
				<details>
					<summary class="title-medium">{item.q}</summary>
					<p class="on-surface-variant">{item.a}</p>
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
