<!--
	The "Download APK" button: the APK asset of the latest app release (version and size from the GitHub Releases
	API at build time), or "coming soon" with a link to the releases page before the first release.
-->
<script lang="ts">
	import Icon from '#lib/components/Icon.svelte';
	import { formatSize } from '#lib/format.js';
	import { RELEASES_URL, type AppRelease } from '#lib/site.js';

	let { release }: { release: AppRelease | null } = $props();
</script>

{#if release}
	<a class="button" href={release.apk.url} rel="external">
		<Icon name="download" />
		Download APK
	</a>
{:else}
	<a class="button" href={RELEASES_URL} rel="external">
		<Icon name="download" />
		APK coming soon
	</a>
{/if}

<p class="meta body-medium">
	{#if release}
		Version {release.version} · {formatSize(release.apk.size)} ·
		<a href={release.releaseUrl} rel="external">release notes</a>
	{:else}
		The first release is on its way. Follow the
		<a href={RELEASES_URL} rel="external">releases page</a>.
	{/if}
</p>

<style>
	.meta {
		flex-basis: 100%;
		order: 1;
		color: var(--md-sys-color-on-surface-variant);
	}
</style>
