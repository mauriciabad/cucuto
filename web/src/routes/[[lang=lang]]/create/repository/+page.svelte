<script lang="ts">
	// "Host your own repository" (#191): `packer index` in the browser, offline with the rest of the site. Docs:
	// docs/decisions/0058-create-page-tools.md.
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import RepositoryTool from '#lib/components/creator/RepositoryTool.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).repository);
	const tc = $derived(messages(locale).create);
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<p class="body-medium"><a href={pageHref('/create/', locale)}>← {t.back}</a></p>
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large"><Html text={t.leadHtml} /></p>
		<ul class="promises body-medium">
			<li><Icon name="lock" />{tc.privacy}</li>
			<li><Icon name="wifi-off" />{tc.offline}</li>
		</ul>
	</header>

	<RepositoryTool />
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
