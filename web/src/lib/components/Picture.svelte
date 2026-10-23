<!--
	A raster image as <picture>: JPEG XL first, then AVIF, then the PNG <img>, all generated at build time from one
	source by `?picture` imports (scripts/picture.ts). `width` and `height` default to the encoded size.
-->
<script lang="ts">
	import type { PictureData } from '#lib/picture.js';

	interface Props {
		src: PictureData;
		alt: string;
		class?: string;
		/** Rendered size; defaults to the encoded size. */
		width?: number | string;
		height?: number | string;
		loading?: 'lazy' | 'eager';
	}

	// No rest props on purpose: spreading onto <img> makes Svelte add inline onload/onerror handlers, which the CSP
	// blocks.
	let { src, alt, class: className, width, height, loading = 'lazy' }: Props = $props();
</script>

<picture>
	{#each src.sources as source (source.type)}
		<source srcset={source.srcset} type={source.type} />
	{/each}
	<img
		src={src.fallback}
		{alt}
		class={className}
		width={width ?? src.width}
		height={height ?? src.height}
		{loading}
		decoding="async"
	/>
</picture>
