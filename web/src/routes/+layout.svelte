<script lang="ts">
	import '@fontsource-variable/google-sans-flex/rond.css';
	import '@fontsource-variable/google-sans-code';
	import '#lib/styles/colors.css';
	import '#lib/styles/typography.css';
	import '#lib/styles/motion.css';
	import '#lib/styles/app.css';

	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import logoLight from '#lib/assets/cucuto-logo-light.svg';
	import logoDark from '#lib/assets/cucuto-logo-dark.svg';
	import markUrl from '#lib/assets/cucuto-mark.svg';
	import Icon from '#lib/components/Icon.svelte';
	import { LICENSE_URL, REPO_URL } from '#lib/site.js';
	import type { LayoutProps } from './$types';

	let { children }: LayoutProps = $props();

	const nav = [
		{ href: resolve('packs/'), label: 'Packs' },
		{ href: resolve('install/'), label: 'Install' },
		{ href: resolve('authors/'), label: 'Pack authors' },
		{ href: resolve('privacy/'), label: 'Privacy' }
	];
</script>

<a class="skip-link" href="#main">Skip to content</a>

<header class="top-bar">
	<div class="container top-bar-inner">
		<a class="brand" href={resolve('/')} aria-label="Cucuto home">
			<picture>
				<source srcset={logoDark} media="(prefers-color-scheme: dark)" />
				<img src={logoLight} alt="" width="137" height="32" />
			</picture>
		</a>
		<nav aria-label="Main">
			<ul>
				{#each nav as item (item.href)}
					<li>
						<a
							href={item.href}
							aria-current={page.url.pathname.startsWith(item.href) ? 'page' : undefined}
						>
							{item.label}
						</a>
					</li>
				{/each}
				<li>
					<a class="github" href={REPO_URL} rel="external">
						<Icon name="code" />
						<span>GitHub</span>
					</a>
				</li>
			</ul>
		</nav>
	</div>
</header>

<main id="main" tabindex="-1">
	{@render children()}
</main>

<footer class="footer">
	<div class="container footer-inner">
		<div class="footer-brand">
			<img src={markUrl} alt="" width="40" height="40" />
			<p>
				<strong>Cucuto</strong> is free software under the
				<a href={LICENSE_URL} rel="external">GPL-3.0-or-later</a> license.
				<br />
				No third-party content ships with the app. Not affiliated with WhatsApp or Meta.
			</p>
		</div>
		<ul class="footer-links">
			<li><a href={REPO_URL} rel="external">Source on GitHub</a></li>
			<li><a href={resolve('install/')}>Install guide</a></li>
			<li><a href={resolve('privacy/')}>Privacy</a></li>
		</ul>
	</div>
</footer>

<style>
	.skip-link {
		position: absolute;
		left: 1rem;
		top: -4rem;
		z-index: 10;
		padding: 0.75rem 1.25rem;
		border-radius: 1rem;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
		font-weight: 600;
		text-decoration: none;
	}

	.skip-link:focus {
		top: 1rem;
	}

	.top-bar {
		position: sticky;
		top: 0;
		z-index: 5;
		background: color-mix(in srgb, var(--md-sys-color-surface) 88%, transparent);
		backdrop-filter: blur(12px);
		border-bottom: 1px solid
			color-mix(in srgb, var(--md-sys-color-outline-variant) 40%, transparent);
	}

	.top-bar-inner {
		display: flex;
		align-items: center;
		justify-content: space-between;
		flex-wrap: wrap;
		gap: 0.25rem 1.5rem;
		padding-block: 0.75rem;
	}

	.brand {
		display: block;
		border-radius: 0.5rem;
		transition: scale var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial);
	}

	.brand:hover {
		scale: 1.04;
	}

	.brand img {
		height: 2rem;
		width: auto;
	}

	nav ul {
		display: flex;
		gap: 0.25rem;
		margin: 0;
		padding: 0;
		list-style: none;
		overflow-x: auto;
		scrollbar-width: none;
	}

	nav a {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		min-height: 2.5rem;
		padding: 0 0.875rem;
		border-radius: 1.25rem;
		color: var(--md-sys-color-on-surface-variant);
		font: 600 0.875rem/1.25rem var(--cucuto-font);
		font-variation-settings: 'ROND' var(--cucuto-rond-label);
		letter-spacing: 0.007em;
		text-decoration: none;
		white-space: nowrap;
		transition:
			background-color var(--cucuto-spring-fast-effects-duration) var(--cucuto-spring-fast-effects),
			border-radius var(--cucuto-spring-fast-spatial-duration) var(--cucuto-spring-fast-spatial);
	}

	nav a:hover {
		background: var(--md-sys-color-surface-container-high);
		color: var(--md-sys-color-on-surface);
	}

	nav a:active {
		border-radius: 0.75rem;
	}

	nav a[aria-current='page'] {
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	nav .github :global(.icon) {
		width: 1.25rem;
		height: 1.25rem;
	}

	@media (max-width: 40rem) {
		.top-bar-inner {
			padding-block: 0.5rem 0.25rem;
		}

		nav {
			width: calc(100% + 2rem);
			margin-inline: -1rem;
		}

		nav ul {
			padding: 0.25rem 1rem 0.5rem;
		}

		/* Icon only on phones; the label stays for screen readers. */
		.github span {
			position: absolute;
			width: 1px;
			height: 1px;
			overflow: hidden;
			clip-path: inset(50%);
			white-space: nowrap;
		}
	}

	main {
		flex: 1;
		outline: none;
	}

	.footer {
		margin-top: 5rem;
		padding-block: 2.5rem;
		background: var(--md-sys-color-surface-container);
		color: var(--md-sys-color-on-surface-variant);
		font-size: 0.875rem;
		line-height: 1.25rem;
	}

	.footer-inner {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		gap: 1.5rem 3rem;
	}

	.footer-brand {
		display: flex;
		gap: 1rem;
		align-items: flex-start;
		max-width: 36rem;
	}

	.footer-brand img {
		flex: none;
	}

	.footer strong {
		color: var(--md-sys-color-on-surface);
	}

	.footer-links {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem 1.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.footer a {
		color: var(--md-sys-color-on-surface);
	}
</style>
