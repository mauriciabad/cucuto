<script lang="ts">
	import { page } from '$app/state';
	import {
		DEFAULT_LOCALE,
		LOCALES,
		LOCALE_INFO,
		localeFromPath,
		localizePath,
		messages
	} from '#lib/i18n/index.js';
	import { SITE_NAME, SITE_URL } from '#lib/site.js';

	interface Props {
		/** Page title without the site name; omit on the home page. */
		title?: string;
		description?: string;
	}

	let { title, description }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale));
	const fullTitle = $derived(title ? `${title} · ${SITE_NAME}` : t.meta.homeTitle);
	const summary = $derived(description ?? t.meta.siteDescription);
	const canonical = $derived(new URL(page.url.pathname, SITE_URL).href);
	// The same page in every language, for search engines (hreflang) and the language switcher.
	const alternates = $derived(
		LOCALES.map((l) => ({
			hreflang: LOCALE_INFO[l].htmlLang,
			href: new URL(localizePath(page.url.pathname, l), SITE_URL).href
		}))
	);
	const image = `${SITE_URL}/og-image.png`;
</script>

<svelte:head>
	<title>{fullTitle}</title>
	<meta name="description" content={summary} />
	<link rel="canonical" href={canonical} />
	{#each alternates as alternate (alternate.hreflang)}
		<link rel="alternate" hreflang={alternate.hreflang} href={alternate.href} />
	{/each}
	<link
		rel="alternate"
		hreflang="x-default"
		href={new URL(localizePath(page.url.pathname, DEFAULT_LOCALE), SITE_URL).href}
	/>
	<meta property="og:type" content="website" />
	<meta property="og:site_name" content={SITE_NAME} />
	<meta property="og:locale" content={LOCALE_INFO[locale].ogLocale} />
	{#each LOCALES.filter((l) => l !== locale) as other (other)}
		<meta property="og:locale:alternate" content={LOCALE_INFO[other].ogLocale} />
	{/each}
	<meta property="og:title" content={fullTitle} />
	<meta property="og:description" content={summary} />
	<meta property="og:url" content={canonical} />
	<meta property="og:image" content={image} />
	<meta property="og:image:type" content="image/png" />
	<meta property="og:image:width" content="1200" />
	<meta property="og:image:height" content="630" />
	<meta property="og:image:alt" content={t.meta.ogImageAlt} />
	<meta name="twitter:card" content="summary_large_image" />
</svelte:head>
