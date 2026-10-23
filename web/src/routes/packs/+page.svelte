<script lang="ts">
	import { resolve } from '$app/paths';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { DEFAULT_REPOSITORY_PATH } from '#lib/repository.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const placeholders = [
		{ character: 'leaf', title: 'Animals' },
		{ character: 'sky', title: 'Chiptune' },
		{ character: 'sunny', title: 'Little bells' }
	];

	function formatSize(bytes: number) {
		return new Intl.NumberFormat('en', {
			style: 'unit',
			unit: 'megabyte',
			maximumFractionDigits: 1
		}).format(bytes / 1_000_000);
	}
</script>

<Seo
	title="Packs"
	description="Free sound packs for Cucuto: characters with clips and vibrations, ready to import."
/>

<div class="container">
	<header class="page-header">
		<h1 class="display-small emphasized">Packs</h1>
		<p class="body-large">
			A pack is a cast of characters, each with a picture, short clips and a vibration. Free packs
			listed here are CC0, so you can use, change and share them.
		</p>
	</header>

	{#if data.packs.length > 0}
		<ul class="gallery">
			{#each data.packs as pack (pack.id)}
				<li class="pack card">
					<h2 class="title-large">{pack.name}</h2>
					<p class="body-medium on-surface-variant">
						{pack.author} · {pack.license} · {formatSize(pack.download.size)}
					</p>
					<a class="button small" href={pack.download.url} rel="external">
						<Icon name="download" />
						Download .soundpack
					</a>
				</li>
			{/each}
		</ul>
	{:else}
		<section class="empty" aria-labelledby="soon-title">
			<ul class="gallery" aria-hidden="true">
				{#each placeholders as item (item.title)}
					<li class="pack placeholder" data-character={item.character}>
						<div class="cover"></div>
						<div class="line wide"></div>
						<div class="line"></div>
					</li>
				{/each}
			</ul>
			<div class="soon card">
				<h2 id="soon-title" class="title-large">The first free packs are on their way</h2>
				<p class="on-surface-variant">
					The demo packs are being made right now. Once they are published, this gallery lists every
					pack with its characters, sample clips and a download button. The same list is available
					to the app at <code>{DEFAULT_REPOSITORY_PATH}</code>, the default pack repository.
				</p>
				<p class="on-surface-variant">
					Want to make one? Read the <a href={resolve('authors/')}>guide for pack authors</a>.
				</p>
			</div>
		</section>
	{/if}
</div>

<style>
	.gallery {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
		gap: 1rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.pack {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.placeholder {
		padding: 1rem;
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.cover {
		aspect-ratio: 4 / 3;
		border-radius: 1.25rem;
		background:
			radial-gradient(
				circle at 30% 40%,
				var(--md-sys-color-primary-container) 0 18%,
				transparent 19%
			),
			radial-gradient(circle at 70% 60%, var(--md-sys-color-primary) 0 11%, transparent 12%),
			var(--md-sys-color-surface-container);
		opacity: 0.8;
	}

	.line {
		height: 0.75rem;
		width: 45%;
		border-radius: 0.375rem;
		background: var(--md-sys-color-surface-container-high);
	}

	.line.wide {
		width: 70%;
		height: 1rem;
	}

	.soon {
		margin-top: 1.5rem;
		max-width: 44rem;
		background: var(--md-sys-color-surface-container);
	}

	.soon p {
		margin-top: 0.75rem;
	}
</style>
