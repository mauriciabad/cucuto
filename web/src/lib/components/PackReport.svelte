<!--
	The validator's verdict on one pack file (shared/pack-spec, ADR 0022): verdict, packs found and every issue with
	its stable code. Used by PackCheck (files people pick or open) and by the Create page (the pack it built).
-->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { formatSize } from '#lib/format.js';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import type { PackSpecResult } from '#lib/pack-spec/index.js';

	interface Props {
		name: string;
		size: number;
		result?: PackSpecResult;
		failure?: string;
	}

	let { name, size, result, failure }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).packCheck);

	function verdict(r: PackSpecResult): string {
		if (!r.valid) return t.invalid;
		return r.warningCodes.length > 0 ? t.validWithWarnings : t.valid;
	}
</script>

<section
	class="report"
	class:valid={result?.valid}
	class:invalid={failure || result?.valid === false}
>
	<h3 class="title-medium">
		<code>{name}</code>
		<span class="body-small on-surface-variant">{formatSize(size, locale)}</span>
	</h3>
	{#if failure}
		<p class="verdict"><Icon name="error" />{fill(t.failed, { error: failure })}</p>
	{:else if result}
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
						{fill(t.pack, { version: pack.version, author: pack.author, count: pack.characters })}
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
							{#if issue.file}<span class="body-small on-surface-variant">{issue.file}</span>{/if}
							{#if issue.pointer}<code class="on-surface-variant">{issue.pointer}</code>{/if}
							<p class="body-small">{issue.message}</p>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</section>

<style>
	.report {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
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
