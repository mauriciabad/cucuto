<script lang="ts">
	// Target of the web app manifest's `file_handlers` entry: when the site is installed as an app, opening a
	// .zip file (packs are <name>.soundpack.zip; the type comes from the contents) lands here, and PackCheck validates
	// it with shared/pack-spec. "Edit in the pack creator" hands a file to the Create page through IndexedDB (#191).
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackCheck from '#lib/components/PackCheck.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';
	import { canSaveDrafts, putPendingFile } from '#lib/creator/drafts.js';

	interface LaunchParams {
		files: ReadonlyArray<FileSystemFileHandle>;
	}

	interface LaunchQueue {
		setConsumer(consumer: (params: LaunchParams) => void): void;
	}

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).open);

	let files = $state.raw<File[]>([]);
	let editProblem = $state('');

	async function edit(file: File) {
		editProblem = '';
		try {
			const key = await putPendingFile(file);
			location.assign(`${pageHref('/create/', locale)}#open=${key}`);
		} catch (error) {
			editProblem = fill(t.editFailed, { error: String(error) });
		}
	}

	// Subscribe once: the browser delivers the launched files through this callback.
	onMount(() => {
		const queue = (window as Window & { launchQueue?: LaunchQueue }).launchQueue;
		queue?.setConsumer(async (params) => {
			files = await Promise.all(params.files.map((handle) => handle.getFile()));
		});
	});
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large"><Html text={t.leadHtml} /></p>
	</header>

	{#if files.length > 0}
		<p>{t.opened}</p>
		<PackCheck {files} picker={false} />
		{#if canSaveDrafts()}
			<div class="edit">
				{#each files as file, i (i)}
					<button type="button" class="button tonal" onclick={() => edit(file)}>
						<Icon name="edit" />
						{t.edit}{files.length > 1 ? `: ${file.name}` : ''}
					</button>
				{/each}
			</div>
			<div aria-live="polite">
				{#if editProblem}<p>{editProblem}</p>{/if}
			</div>
		{/if}
	{/if}

	<p><a href={pageHref('/packs/', locale)}>{t.browse}</a></p>
</div>

<style>
	.edit {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
	}
</style>
