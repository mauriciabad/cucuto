<script lang="ts">
	// The Create page (#48): the pack creator, installable and offline with the rest of the site (service worker,
	// web app manifest). Docs: docs/decisions/0038-web-pack-creator.md and, for drafts, opening packs, covers, custom
	// haptics, bundles and the repository helper (#191), docs/decisions/0058-create-page-tools.md.
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import Html from '#lib/components/Html.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import Creator from '#lib/components/creator/Creator.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';

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

	<!-- Outside the creator, so the prerendered page links the tools (and the build prerenders them). -->
	<section class="tools-section" aria-labelledby="tools">
		<h2 id="tools" class="headline-small">{t.tools.title}</h2>
		<ul class="tools">
			<li>
				<a class="card tool" href={pageHref('/create/bundle/', locale)}>
					<Icon name="library-add" />
					<span>
						<span class="title-medium">{t.tools.bundle}</span>
						<span class="body-medium on-surface-variant">{t.tools.bundleText}</span>
					</span>
				</a>
			</li>
			<li>
				<a class="card tool" href={pageHref('/create/repository/', locale)}>
					<Icon name="public" />
					<span>
						<span class="title-medium">{t.tools.repository}</span>
						<span class="body-medium on-surface-variant">{t.tools.repositoryText}</span>
					</span>
				</a>
			</li>
		</ul>
	</section>
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

	.tools-section {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		margin-top: 3rem;
	}

	.tools {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(16rem, 100%), 1fr));
		gap: 1rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.tool {
		display: flex;
		gap: 1rem;
		height: 100%;
		color: inherit;
		text-decoration: none;
	}

	.tool > span {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.tool :global(.icon) {
		color: var(--md-sys-color-primary);
	}

	.tool:hover {
		background: var(--md-sys-color-surface-container);
	}
</style>
