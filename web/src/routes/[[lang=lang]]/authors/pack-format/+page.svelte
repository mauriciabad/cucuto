<script lang="ts">
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).packFormat);
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<p class="back">
			<a href={pageHref('/authors/', locale)}>
				<Icon name="arrow-forward" class="back-icon" />
				{t.back}
			</a>
		</p>
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large" lang="en">{data.doc.title}</p>
		<p class="body-medium"><Html text={t.sourceHtml} /></p>
		{#if t.englishOnly}
			<p class="body-medium">{t.englishOnly}</p>
		{/if}
	</header>

	<nav class="toc" aria-labelledby="toc-title">
		<h2 id="toc-title" class="title-medium">{t.toc}</h2>
		<ol lang="en">
			{#each data.doc.toc as entry (entry.id)}
				<li><a href="#{entry.id}">{entry.title}</a></li>
			{/each}
		</ol>
	</nav>

	<article class="spec" lang="en">
		<!-- eslint-disable-next-line svelte/no-at-html-tags -- docs/pack-format.md from this repository, rendered at build time -->
		{@html data.doc.html}
	</article>
</div>

<style>
	.back a {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		font-weight: 600;
	}

	.back :global(.back-icon) {
		width: 1.25rem;
		height: 1.25rem;
		rotate: 180deg;
	}

	.page-header .back {
		margin-top: 0;
		margin-bottom: 1rem;
	}

	.toc {
		padding: 1.25rem 1.5rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.toc ol {
		columns: 2 12rem;
		column-gap: 2rem;
		margin-top: 0.5rem;
		padding-left: 1.25rem;
	}

	.toc li {
		padding-block: 0.125rem;
		break-inside: avoid;
	}

	/* The rendered document: plain markdown elements, styled here. */

	.spec {
		margin-top: 2rem;
	}

	.spec :global(:is(h2, h3, h4)) {
		scroll-margin-top: 5rem;
	}

	.spec :global(h2) {
		margin-top: 3rem;
		font: 600 1.5rem/2rem var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
	}

	.spec :global(h3) {
		margin-top: 2rem;
		font: 600 1.25rem/1.75rem var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
	}

	.spec :global(:is(p, ul, ol, pre, .scroll)) {
		margin-top: 1rem;
	}

	.spec :global(:is(ul, ol)) {
		padding-left: 1.5rem;
	}

	.spec :global(li + li) {
		margin-top: 0.375rem;
	}

	.spec :global(li > :is(ul, ol)) {
		margin-top: 0.375rem;
	}

	.spec :global(.scroll) {
		overflow-x: auto;
		border-radius: 1.25rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.spec :global(table) {
		width: 100%;
		border-collapse: collapse;
		font-size: 0.875rem;
		line-height: 1.25rem;
	}

	.spec :global(:is(th, td)) {
		padding: 0.625rem 0.875rem;
		text-align: left;
		vertical-align: top;
		border-bottom: 1px solid var(--md-sys-color-outline-variant);
	}

	.spec :global(th) {
		background: var(--md-sys-color-surface-container);
		font-weight: 600;
	}

	.spec :global(tr:last-child td) {
		border-bottom: 0;
	}

	.spec :global(td code) {
		white-space: nowrap;
	}
</style>
