<script lang="ts">
	import { page } from '$app/state';
	import DownloadApk from '#lib/components/DownloadApk.svelte';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Picture from '#lib/components/Picture.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import appIcon from '#lib/assets/images/cucuto-icon.png?w=192&picture';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		OBTAINIUM_APK_FILTER,
		OBTAINIUM_CONFIG,
		OBTAINIUM_LINK,
		OBTAINIUM_TITLE_FILTER,
		OBTAINIUM_URL,
		RELEASE_CERT_COLONS
	} from '#lib/obtainium.js';
	import { RELEASE_CERT_SHA256, RELEASES_URL, REPO_URL } from '#lib/site.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const t = $derived(messages(localeFromPath(page.url.pathname)).install);
	const releaseUrl = $derived(data.appRelease?.releaseUrl ?? RELEASES_URL);
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large">{t.lead}</p>
	</header>

	{#if !data.appRelease}
		<p class="callout">
			<Icon name="info" />
			<span><Html text={t.preReleaseHtml} /></span>
		</p>
	{/if}

	<h2 class="headline-small">{t.beforeTitle}</h2>
	<ul class="checklist">
		{#each t.before as item (item.title)}
			<li>
				<span class="check-icon"><Icon name={item.icon} /></span>
				<div>
					<h3 class="title-medium">{item.title}</h3>
					<p class="body-medium on-surface-variant">{item.text}</p>
				</div>
			</li>
		{/each}
	</ul>

	<h2 class="headline-small">{t.stepsTitle}</h2>
	<ol class="install-steps">
		{#each t.steps as step, i (step.title)}
			<li>
				<h3 class="title-large">{step.title}</h3>
				{#if i === 0}
					<div class="with-icon">
						<p><Html text={step.textHtml} values={{ releaseUrl }} /></p>
						<Picture src={appIcon} alt={t.appIconAlt} class="app-icon" width="96" height="96" />
					</div>
				{:else}
					<p><Html text={step.textHtml} values={{ releaseUrl }} /></p>
				{/if}
			</li>
		{/each}
	</ol>

	<h2 class="headline-small">{t.testTitle}</h2>
	<p><Html text={t.testHtml} /></p>

	<h2 class="headline-small" id="updates">{t.updatesTitle}</h2>
	<p><Html text={t.updatesHtml} values={{ obtainium: OBTAINIUM_URL }} /></p>
	<p id="certificate">
		<Html text={t.certificateHtml} values={{ cert: `${RELEASE_CERT_SHA256.slice(0, 8)}…` }} />
	</p>
	<div class="obtainium">
		<a class="button tonal" href={OBTAINIUM_LINK}>
			<Icon name="download" />
			{t.obtainiumButton}
		</a>
		<p class="body-medium on-surface-variant">{t.obtainiumHint}</p>
	</div>
	<details class="obtainium-manual">
		<summary class="title-medium">{t.obtainiumManualTitle}</summary>
		<p><Html text={t.obtainiumManualHtml} /></p>
		<dl>
			<dt>{t.obtainiumFields.source}</dt>
			<dd><code>{REPO_URL}</code></dd>
			<dt>{t.obtainiumFields.apkFilter}</dt>
			<dd><code>{OBTAINIUM_APK_FILTER}</code></dd>
			<dt>{t.obtainiumFields.titleFilter}</dt>
			<dd><code>{OBTAINIUM_TITLE_FILTER}</code></dd>
			<dt>{t.obtainiumFields.cert}</dt>
			<dd><code class="cert">{RELEASE_CERT_COLONS}</code></dd>
		</dl>
		<p>{t.obtainiumJson}</p>
		<pre><code>{JSON.stringify(OBTAINIUM_CONFIG, null, 2)}</code></pre>
	</details>

	<h2 class="headline-small">{t.troubleTitle}</h2>
	<dl class="trouble">
		{#each t.trouble as item (item.q)}
			<div>
				<dt class="title-medium">{item.q}</dt>
				<dd class="on-surface-variant"><Html text={item.aHtml} /></dd>
			</div>
		{/each}
	</dl>

	<h2 class="headline-small">{t.removeTitle}</h2>
	<p><Html text={t.removeHtml} /></p>

	<div class="back">
		<DownloadApk release={data.appRelease} />
	</div>
</div>

<style>
	.callout {
		display: flex;
		gap: 0.75rem;
		align-items: flex-start;
		padding: 1rem 1.25rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
	}

	.callout :global(a) {
		color: inherit;
	}

	.checklist {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
		gap: 0.75rem;
		padding: 0;
		list-style: none;
	}

	.prose > .checklist {
		padding-left: 0;
	}

	.checklist li {
		display: flex;
		gap: 1rem;
		align-items: flex-start;
		margin: 0;
		padding: 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.checklist p {
		margin-top: 0.25rem;
	}

	.check-icon {
		display: grid;
		place-items: center;
		flex: none;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	.install-steps {
		counter-reset: step;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.prose > .install-steps {
		padding: 0;
	}

	.prose > .install-steps > li + li {
		margin-top: 0;
	}

	.install-steps li {
		counter-increment: step;
		position: relative;
		padding: 1.5rem 1.5rem 1.5rem 5rem;
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.install-steps li::before {
		content: counter(step);
		position: absolute;
		left: 1.25rem;
		top: 1.25rem;
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
		font: 800 1.125rem/1 var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
	}

	@media (max-width: 32rem) {
		.install-steps li {
			padding: 4.5rem 1.25rem 1.25rem;
		}
	}

	.install-steps p {
		margin-top: 0.5rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.with-icon {
		display: flex;
		align-items: flex-start;
		gap: 1rem 1.5rem;
	}

	.with-icon :global(.app-icon) {
		flex: none;
		width: 6rem;
		height: auto;
		margin-top: 0.5rem;
	}

	@media (max-width: 32rem) {
		.with-icon {
			flex-direction: column;
		}
	}

	.obtainium {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 1rem;
		margin-top: 1rem;
	}

	.obtainium p {
		flex: 1 1 16rem;
		margin: 0;
	}

	.obtainium-manual {
		margin-top: 1rem;
		padding: 1rem 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.obtainium-manual summary {
		cursor: pointer;
	}

	.obtainium-manual dt {
		margin-top: 0.75rem;
		font-weight: 600;
	}

	.obtainium-manual dd {
		margin: 0.25rem 0 0;
		overflow-wrap: anywhere;
	}

	.obtainium-manual pre {
		overflow-x: auto;
		max-width: 100%;
		padding: 1rem;
		border-radius: 1rem;
		background: var(--md-sys-color-surface-container);
		font-size: 0.8125rem;
	}

	.trouble {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.trouble dd {
		margin: 0.25rem 0 0;
	}

	.back {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
		margin-top: 2.5rem;
	}
</style>
