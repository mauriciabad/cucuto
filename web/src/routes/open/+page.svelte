<script lang="ts">
	// Target of the web app manifest's `file_handlers` entry: when the site is installed as an app, opening a
	// .zip file (packs are <name>.soundpack.zip; the type comes from the contents) lands here. Previewing and editing packs arrives with the pack creator (#48).
	import { onMount } from 'svelte';
	import { resolve } from '$app/paths';
	import Seo from '#lib/components/Seo.svelte';

	interface LaunchParams {
		files: ReadonlyArray<{ name: string }>;
	}

	interface LaunchQueue {
		setConsumer(consumer: (params: LaunchParams) => void): void;
	}

	let names = $state<string[]>([]);

	// Subscribe once: the browser delivers the launched files through this callback.
	onMount(() => {
		const queue = (window as Window & { launchQueue?: LaunchQueue }).launchQueue;
		queue?.setConsumer((params) => {
			names = params.files.map((file) => file.name);
		});
	});
</script>

<Seo title="Open a pack" description="Open .soundpack.zip files with the Cucuto web app." />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">Open a pack</h1>
		<p class="body-large">
			Opening pack files (<code>.soundpack.zip</code>, or any pack <code>.zip</code>) here comes
			with the pack creator. For now, import packs in the Cucuto app on your phone.
		</p>
	</header>

	{#if names.length > 0}
		<p>You opened:</p>
		<ul>
			{#each names as name (name)}
				<li><code>{name}</code></li>
			{/each}
		</ul>
	{/if}

	<p><a href={resolve('packs/')}>Browse free packs</a></p>
</div>
