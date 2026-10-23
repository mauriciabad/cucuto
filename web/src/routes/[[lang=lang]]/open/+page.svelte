<script lang="ts">
	// Target of the web app manifest's `file_handlers` entry: when the site is installed as an app, opening a
	// .zip file (packs are <name>.soundpack.zip; the type comes from the contents) lands here. Previewing and editing packs arrives with the pack creator (#48).
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';

	interface LaunchParams {
		files: ReadonlyArray<{ name: string }>;
	}

	interface LaunchQueue {
		setConsumer(consumer: (params: LaunchParams) => void): void;
	}

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).open);

	let names = $state<string[]>([]);

	// Subscribe once: the browser delivers the launched files through this callback.
	onMount(() => {
		const queue = (window as Window & { launchQueue?: LaunchQueue }).launchQueue;
		queue?.setConsumer((params) => {
			names = params.files.map((file) => file.name);
		});
	});
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large"><Html text={t.leadHtml} /></p>
	</header>

	{#if names.length > 0}
		<p>{t.opened}</p>
		<ul>
			{#each names as name (name)}
				<li><code>{name}</code></li>
			{/each}
		</ul>
	{/if}

	<p><a href={pageHref('/packs/', locale)}>{t.browse}</a></p>
</div>
