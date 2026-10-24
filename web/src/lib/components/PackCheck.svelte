<!--
	Checks pack files in the browser with shared/pack-spec, the same Kotlin rules as packer and the app (ADR 0022).
	Files never leave the device. Used on the authors page (file picker) and on /open/ (files the installed PWA was
	launched with). The validator loads on first use.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackReport from '#lib/components/PackReport.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { loadPackSpec, packSpecAvailable, type PackSpecResult } from '#lib/pack-spec/index.js';

	interface Props {
		/** Files to check right away (the PWA's launch queue). */
		files?: readonly File[];
		/** Show the file picker. */
		picker?: boolean;
	}

	let { files = [], picker = true }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).packCheck);

	interface Checked {
		name: string;
		size: number;
		result?: PackSpecResult;
		failure?: string;
	}

	let checked = $state<Checked[]>([]);
	let busy = $state(false);

	async function check(list: readonly File[]) {
		const validator = loadPackSpec();
		if (!validator || list.length === 0) return;
		busy = true;
		try {
			const spec = await validator;
			const results: Checked[] = [];
			for (const file of list) {
				try {
					const bytes = new Uint8Array(await file.arrayBuffer());
					results.push({ name: file.name, size: file.size, result: spec.validateArchive(bytes) });
				} catch (error) {
					results.push({ name: file.name, size: file.size, failure: String(error) });
				}
			}
			checked = results;
		} catch (error) {
			checked = list.map((file) => ({ name: file.name, size: file.size, failure: String(error) }));
		} finally {
			busy = false;
		}
	}

	$effect(() => {
		void check(files);
	});

	function onchange(event: Event & { currentTarget: HTMLInputElement }) {
		void check([...(event.currentTarget.files ?? [])]);
	}
</script>

<div class="pack-check">
	{#if !packSpecAvailable}
		<p class="body-medium on-surface-variant">{t.unavailable}</p>
	{:else}
		{#if picker}
			<label class="button tonal picker">
				<Icon name="folder-zip" />
				{t.choose}
				<input
					class="visually-hidden"
					type="file"
					accept=".zip,application/zip"
					multiple
					disabled={busy}
					{onchange}
				/>
			</label>
		{/if}

		<div class="results" aria-live="polite">
			{#if busy}
				<p class="body-medium">{t.checking}</p>
			{:else}
				{#each checked as item, index (index)}
					<PackReport {...item} />
				{/each}
				{#if checked.some((item) => item.result?.issues.length)}
					<p class="body-small on-surface-variant"><Html text={t.codesHtml} /></p>
				{/if}
			{/if}
		</div>
	{/if}
</div>

<style>
	.pack-check {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.picker {
		align-self: flex-start;
	}

	.picker:focus-within {
		outline: 3px solid var(--md-sys-color-primary);
		outline-offset: 2px;
	}

	.results {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}
</style>
