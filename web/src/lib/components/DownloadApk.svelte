<!--
	The "Download APK" button: the APK asset of the latest app release (version and size from the GitHub Releases
	API at build time), or "coming soon" with a link to the releases page before the first release.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import { formatSize } from '#lib/format.js';
	import { LOCALE_INFO, localeFromPath, messages } from '#lib/i18n/index.js';
	import { RELEASES_URL, type AppRelease } from '#lib/site.js';

	let { release }: { release: AppRelease | null } = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).download);
</script>

{#if release}
	<a class="button" href={release.apk.url} rel="external">
		<Icon name="download" />
		{t.button}
	</a>
{:else}
	<a class="button" href={RELEASES_URL} rel="external">
		<Icon name="download" />
		{t.comingSoon}
	</a>
{/if}

<p class="meta body-medium">
	{#if release}
		<Html
			text={t.metaHtml}
			values={{
				version: release.version,
				size: formatSize(release.apk.size, LOCALE_INFO[locale].htmlLang),
				notes: release.releaseUrl
			}}
		/>
	{:else}
		<Html text={t.soonHtml} />
	{/if}
</p>

<style>
	.meta {
		flex-basis: 100%;
		order: 1;
		color: var(--md-sys-color-on-surface-variant);
	}
</style>
