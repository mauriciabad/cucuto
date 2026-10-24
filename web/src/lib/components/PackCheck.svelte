<!--
	Checks pack files in the browser with shared/pack-spec, the same Kotlin rules as packer and the app (ADR 0022).
	Files never leave the device. Used on the authors page (file picker) and on /open/ (files the installed PWA was
	launched with). The validator loads on first use.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import { formatSize } from '#lib/format.js';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
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

	function verdict(result: PackSpecResult): string {
		if (!result.valid) return t.invalid;
		return result.warningCodes.length > 0 ? t.validWithWarnings : t.valid;
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

		<div aria-live="polite">
			{#if busy}
				<p class="body-medium">{t.checking}</p>
			{:else}
				{#each checked as item, index (index)}
					<section
						class="report"
						class:valid={item.result?.valid}
						class:invalid={item.failure || item.result?.valid === false}
					>
						<h3 class="title-medium">
							<code>{item.name}</code>
							<span class="body-small on-surface-variant">{formatSize(item.size, locale)}</span>
						</h3>
						{#if item.failure}
							<p class="verdict"><Icon name="error" />{fill(t.failed, { error: item.failure })}</p>
						{:else if item.result}
							{@const result = item.result}
							<p class="verdict">
								<Icon name={result.valid ? 'check-circle' : 'error'} />
								{verdict(result)}
							</p>
							{#if result.kind === 'bundle'}
								<p class="body-medium">{fill(t.bundle, { count: result.packs.length })}</p>
							{/if}
							{#if result.packs.length > 0}
								<ul class="packs body-medium">
									{#each result.packs as pack (pack.root)}
										<li>
											<strong>{pack.name}</strong>
											{fill(t.pack, {
												version: pack.version,
												author: pack.author,
												count: pack.characters
											})}
										</li>
									{/each}
								</ul>
							{/if}
							{#if result.issues.length > 0}
								<ul class="issues">
									{#each result.issues as issue, i (i)}
										<li class:error={issue.severity === 'error'}>
											<Icon name={issue.severity === 'error' ? 'error' : 'warning'} />
											<div>
												<code>{issue.code}</code>
												<span class="visually-hidden">
													({issue.severity === 'error' ? t.error : t.warning})
												</span>
												{#if issue.file}<span class="body-small on-surface-variant"
														>{issue.file}</span
													>{/if}
												{#if issue.pointer}<code class="on-surface-variant">{issue.pointer}</code
													>{/if}
												<p class="body-small">{issue.message}</p>
											</div>
										</li>
									{/each}
								</ul>
							{/if}
						{/if}
					</section>
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

	.report {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.report + .report,
	.report + p {
		margin-top: 0.75rem;
	}

	.report h3 {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0.25rem 0.75rem;
		overflow-wrap: anywhere;
	}

	.verdict {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 600;
	}

	.valid .verdict {
		color: var(--md-sys-color-primary);
	}

	.invalid .verdict {
		color: var(--md-sys-color-error);
	}

	.packs,
	.issues {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		padding: 0;
		list-style: none;
	}

	.issues li {
		display: flex;
		gap: 0.5rem;
		overflow-wrap: anywhere;
	}

	.issues li > :global(svg) {
		color: var(--md-sys-color-tertiary);
	}

	.issues li.error > :global(svg) {
		color: var(--md-sys-color-error);
	}

	.issues div {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 0.5rem;
	}

	.issues p {
		flex-basis: 100%;
	}
</style>
