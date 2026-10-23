<script lang="ts">
	import { page } from '$app/state';
	import markUrl from '#lib/assets/cucuto-mark.svg';
	import Seo from '#lib/components/Seo.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';

	// 404.html is the SPA fallback, rendered in the browser: the language comes from the address.
	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).error);
	const notFound = $derived(page.status === 404);
</script>

<Seo title={notFound ? t.notFoundTitle : t.otherTitle} />

<div class="prose error">
	<img src={markUrl} alt="" width="96" height="96" />
	<h1 class="display-small emphasized">
		{notFound ? t.notFoundHeading : t.otherTitle}
	</h1>
	<p class="body-large on-surface-variant">
		{notFound ? t.notFoundText : fill(t.otherText, { status: page.status })}
	</p>
	<p><a class="button" href={pageHref('/', locale)}>{t.home}</a></p>
</div>

<style>
	.error {
		padding-block: 4rem 2rem;
		text-align: center;
	}

	.error img {
		margin-inline: auto;
	}

	.error h1 {
		margin-top: 1.5rem;
	}
</style>
