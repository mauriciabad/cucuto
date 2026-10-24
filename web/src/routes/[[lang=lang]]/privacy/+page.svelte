<script lang="ts">
	// Keep this page true to the code: android/app/src/main/AndroidManifest.xml (permissions, backup rules),
	// android/core/repo (the only module with network access), android/xposed (what the module reads) and ADR 0009
	// (history sizes). Bump PRIVACY_UPDATED in src/lib/site.ts when the substance changes.
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { formatDate } from '#lib/format.js';
	import { LOCALE_INFO, fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { PRIVACY_UPDATED } from '#lib/site.js';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).privacy);
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large">{t.lead}</p>
		<p class="body-medium">
			<time datetime={PRIVACY_UPDATED}>
				{fill(t.updated, { date: formatDate(PRIVACY_UPDATED, LOCALE_INFO[locale].htmlLang) })}
			</time>
		</p>
	</header>

	<ul class="promises">
		{#each t.promises as item (item.title)}
			<li>
				<Icon name={item.icon} />
				<div>
					<h2 class="title-medium">{item.title}</h2>
					<p class="body-medium">{item.text}</p>
				</div>
			</li>
		{/each}
	</ul>

	<h2 class="headline-small">{t.siteTitle}</h2>
	{#each t.site as paragraph (paragraph)}
		<p><Html text={paragraph} /></p>
	{/each}

	<h2 class="headline-small">{t.appTitle}</h2>
	<p><Html text={t.appLeadHtml} /></p>

	<h3 class="title-large">{t.storedTitle}</h3>
	<ul>
		{#each t.stored as item (item)}
			<li><Html text={item} /></li>
		{/each}
	</ul>
	<p><Html text={t.storedNoteHtml} /></p>

	<h3 class="title-large">{t.moduleTitle}</h3>
	<p><Html text={t.moduleHtml} /></p>

	<h3 class="title-large">{t.networkTitle}</h3>
	<p><Html text={t.networkHtml} /></p>

	<h3 class="title-large">{t.backupTitle}</h3>
	{#each t.backup as paragraph (paragraph)}
		<p><Html text={paragraph} /></p>
	{/each}

	<h3 class="title-large">{t.permissionsTitle}</h3>
	<ul>
		{#each t.permissions as item (item)}
			<li><Html text={item} /></li>
		{/each}
	</ul>
	<p><Html text={t.permissionsNoteHtml} /></p>

	<h2 class="headline-small">{t.checkTitle}</h2>
	<p><Html text={t.checkHtml} /></p>
</div>

<style>
	.promises {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
		gap: 0.75rem;
		list-style: none;
	}

	.prose > .promises {
		padding: 0;
	}

	.prose > .promises > li + li {
		margin-top: 0;
	}

	.promises li {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 1.25rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
	}

	.promises p {
		margin-top: 0.25rem;
		color: inherit;
		opacity: 0.9;
	}

	.prose > h3 {
		margin-top: 1.75rem;
	}
</style>
