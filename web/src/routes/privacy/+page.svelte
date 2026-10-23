<script lang="ts">
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { REPO_URL } from '#lib/site.js';

	const promises: Array<{ icon: IconName; title: string; text: string }> = [
		{ icon: 'block', title: 'No analytics', text: 'No counters, pixels or session recordings.' },
		{ icon: 'cookie', title: 'No cookies', text: 'Nothing is stored to recognize you.' },
		{
			icon: 'no-accounts',
			title: 'No tracking',
			text: 'No ads, no profiles, no third-party scripts.'
		}
	];
</script>

<Seo
	title="Privacy"
	description="The Cucuto website has no analytics, no cookies and no tracking. The app has no accounts and no analytics."
/>

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">Privacy</h1>
		<p class="body-large">Short version: we don’t collect anything.</p>
	</header>

	<ul class="promises">
		{#each promises as item (item.title)}
			<li>
				<Icon name={item.icon} />
				<div>
					<h2 class="title-medium">{item.title}</h2>
					<p class="body-medium on-surface-variant">{item.text}</p>
				</div>
			</li>
		{/each}
	</ul>

	<h2 class="headline-small">This website</h2>
	<p>
		Every page, font and image is served from this site; it makes no requests to other services. To
		work offline and load faster, your browser keeps a copy of the site in its cache (a service
		worker). That copy stays on your device, and you can clear it in your browser settings.
	</p>
	<p>
		The site is hosted on GitHub Pages. Like any web host, GitHub receives technical data such as
		your IP address when it serves a page; see the
		<a
			href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"
			rel="external">GitHub General Privacy Statement</a
		>.
	</p>

	<h2 class="headline-small">The app</h2>
	<p>
		Cucuto has no accounts, no ads and no analytics. Your contacts, assignments and packs stay on
		your phone. The app only goes online to reach the pack repositories in your list (this site’s is
		there by default): to show their packs, check for updates and download the packs you choose.
	</p>

	<h2 class="headline-small">Check for yourself</h2>
	<p>
		The website and the app are open source. Read the code on
		<a href={REPO_URL} rel="external">GitHub</a>.
	</p>
</div>

<style>
	.promises {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
		gap: 0.75rem;
		padding: 0;
		list-style: none;
	}

	.promises li {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 1.25rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
	}

	.promises p {
		color: inherit;
		opacity: 0.9;
	}
</style>
