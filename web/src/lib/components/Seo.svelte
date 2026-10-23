<script lang="ts">
	import { page } from '$app/state';
	import { DESCRIPTION, PITCH, SITE_NAME, SITE_URL } from '#lib/site.js';

	interface Props {
		/** Page title without the site name; omit on the home page. */
		title?: string;
		description?: string;
	}

	let { title, description = DESCRIPTION }: Props = $props();

	const fullTitle = $derived(
		title ? `${title} · ${SITE_NAME}` : `${SITE_NAME}: ${PITCH.toLowerCase()}`
	);
	const canonical = $derived(new URL(page.url.pathname, SITE_URL).href);
	const image = `${SITE_URL}/og-image.png`;
</script>

<svelte:head>
	<title>{fullTitle}</title>
	<meta name="description" content={description} />
	<link rel="canonical" href={canonical} />
	<meta property="og:type" content="website" />
	<meta property="og:site_name" content={SITE_NAME} />
	<meta property="og:locale" content="en" />
	<meta property="og:title" content={fullTitle} />
	<meta property="og:description" content={description} />
	<meta property="og:url" content={canonical} />
	<meta property="og:image" content={image} />
	<meta property="og:image:type" content="image/png" />
	<meta property="og:image:width" content="1200" />
	<meta property="og:image:height" content="630" />
	<meta
		property="og:image:alt"
		content="The cucuto logo: a tangerine cuckoo clock house with its round door open and a bird singing."
	/>
	<meta name="twitter:card" content="summary_large_image" />
</svelte:head>
