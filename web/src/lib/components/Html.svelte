<!--
	Renders a `…Html` string from the dictionaries (src/lib/i18n): inline markup such as <code>, <strong> and links,
	with `{placeholder}`s filled from the site's links in the current language (src/lib/i18n/links.ts) and `values`.
	Only for copy written in this repository; never pass user or fetched content.
-->
<script lang="ts">
	import { page } from '$app/state';
	import { fill, localeFromPath } from '#lib/i18n/index.js';
	import { linkValues } from '#lib/i18n/links.js';

	interface Props {
		text: string;
		values?: Record<string, string | number>;
	}

	let { text, values = {} }: Props = $props();

	const html = $derived(
		fill(text, { ...linkValues(localeFromPath(page.url.pathname)), ...values })
	);
</script>

<!-- eslint-disable-next-line svelte/no-at-html-tags -- trusted copy from src/lib/i18n, fixed at build time -->
{@html html}
