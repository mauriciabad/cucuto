<script lang="ts">
	// The Create page (#48): the pack creator, installable and offline with the rest of the site (service worker,
	// web app manifest). Docs: docs/decisions/0038-web-pack-creator.md.
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import Html from '#lib/components/Html.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import Creator from '#lib/components/creator/Creator.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create);
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large"><Html text={t.leadHtml} /></p>
		<ul class="promises body-medium">
			<li><Icon name="lock" />{t.privacy}</li>
			<li><Icon name="wifi-off" />{t.offline}</li>
		</ul>
	</header>

	<Creator />
</div>

<style>
	.promises {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin-top: 1.25rem;
		padding: 0;
		list-style: none;
		color: var(--md-sys-color-on-surface-variant);
	}

	.promises li {
		display: flex;
		gap: 0.625rem;
		align-items: flex-start;
	}

	.promises :global(.icon) {
		width: 1.25rem;
		height: 1.25rem;
		color: var(--md-sys-color-primary);
	}
</style>
