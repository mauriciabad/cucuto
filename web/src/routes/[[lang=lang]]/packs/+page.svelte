<script lang="ts">
	import { page } from '$app/state';
	import HapticPreview from '#lib/components/HapticPreview.svelte';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { formatSize } from '#lib/format.js';
	import { LOCALE_INFO, fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { ADD_TO_APP_URL, DEFAULT_REPOSITORY_PATH } from '#lib/repository.js';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).packs);
	const placeholderAccents = ['leaf', 'sky', 'sunny'];

	// One shared player: starting a sample stops the previous one.
	let audio: HTMLAudioElement | undefined;
	let playing = $state<string | null>(null);

	function toggleSample(key: string, url: string) {
		audio?.pause();
		if (playing === key) {
			playing = null;
			return;
		}
		audio = new Audio(url);
		audio.addEventListener('ended', () => (playing = null), { once: true });
		playing = key;
		audio.play().catch(() => (playing = null));
	}

	$effect(() => () => audio?.pause());
</script>

<Seo title={t.title} description={t.description} />

<div class="container">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large">{t.lead}</p>
	</header>

	{#if data.packs.length > 0}
		<p class="repo-note on-surface-variant">
			<Html text={t.repoNoteHtml} values={{ path: DEFAULT_REPOSITORY_PATH }} />
		</p>
		<ul class="gallery">
			{#each data.packs as pack (pack.id)}
				<li class="pack card">
					{#if pack.cover}
						<img class="cover" src={pack.cover} alt="" width="400" height="300" loading="lazy" />
					{/if}
					<div class="pack-body">
						<h2 class="title-large">{pack.name}</h2>
						<p class="body-medium on-surface-variant">
							{[
								pack.type === 'bundle' ? t.bundle : undefined,
								pack.author ?? t.unknownAuthor,
								pack.license ?? t.noLicense,
								`${formatSize(pack.download.size, LOCALE_INFO[locale].htmlLang)} .soundpack.zip`
							]
								.filter(Boolean)
								.join(' · ')}
						</p>
						{#if pack.description}
							<!-- Pack descriptions come from the repository index and stay in their own language. -->
							<p class="body-medium" lang="en">{pack.description}</p>
						{/if}

						{#if pack.characters.length > 0}
							<ul class="characters" aria-label={t.characters}>
								{#each pack.characters as character (character.id)}
									{@const key = `${pack.id}/${character.id}`}
									<li class="character">
										{#if character.sprite}
											<img src={character.sprite} alt="" width="48" height="48" loading="lazy" />
										{/if}
										<div class="character-text">
											<span class="body-medium">{character.name}</span>
											<HapticPreview haptic={character['x-haptic']} />
										</div>
										{#if character.sample}
											{@const sample = character.sample}
											<button
												type="button"
												class="play"
												aria-label={fill(playing === key ? t.stop : t.play, {
													name: character.name
												})}
												aria-pressed={playing === key}
												onclick={() => toggleSample(key, sample)}
											>
												<Icon name={playing === key ? 'stop' : 'play-arrow'} />
											</button>
										{/if}
									</li>
								{/each}
							</ul>
						{/if}

						{#if pack.packs.length > 0}
							<p class="body-medium on-surface-variant">
								{fill(t.contains, { names: pack.packs.map((p) => p.name).join(', ') })}
							</p>
						{/if}
					</div>

					<div class="pack-actions">
						<a class="button small" href={pack.download.url} rel="external">
							<Icon name="download" />
							{t.download}<span class="visually-hidden">
								{fill(t.downloadHidden, { name: pack.name })}</span
							>
						</a>
						<a class="button small tonal" href={ADD_TO_APP_URL} rel="external">
							<Icon name="library-add" />
							{t.addToApp}
						</a>
					</div>
				</li>
			{/each}
		</ul>
	{:else}
		<section class="empty" aria-labelledby="soon-title">
			<ul class="gallery" aria-hidden="true">
				{#each t.placeholders as title, i (title)}
					<li class="pack placeholder" data-character={placeholderAccents[i]}>
						<div class="cover"></div>
						<div class="line wide"></div>
						<div class="line"></div>
					</li>
				{/each}
			</ul>
			<div class="soon card">
				<h2 id="soon-title" class="title-large">{t.soonTitle}</h2>
				<p class="on-surface-variant">
					<Html text={t.soonTextHtml} values={{ path: DEFAULT_REPOSITORY_PATH }} />
				</p>
				<p class="on-surface-variant"><Html text={t.soonAuthorsHtml} /></p>
			</div>
		</section>
	{/if}
</div>

<style>
	.repo-note {
		max-width: 44rem;
		margin-bottom: 1.5rem;
	}

	.gallery {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
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

	.pack-body {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		flex: 1;
	}

	img.cover {
		width: 100%;
		height: auto;
		aspect-ratio: 4 / 3;
		object-fit: cover;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container);
	}

	.characters {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		margin: 0.25rem 0 0;
		padding: 0;
		list-style: none;
	}

	.character {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		min-height: 3rem;
	}

	.character img {
		width: 2.5rem;
		height: 2.5rem;
		flex: none;
	}

	.character-text {
		display: flex;
		flex: 1;
		flex-direction: column;
		gap: 0.125rem;
		min-width: 0;
	}

	.play {
		display: grid;
		place-items: center;
		flex: none;
		width: 2.75rem;
		height: 2.75rem;
		border: 0;
		border-radius: 50%;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
		cursor: pointer;
		transition: border-radius var(--cucuto-spring-fast-spatial-duration)
			var(--cucuto-spring-fast-spatial);
	}

	.play[aria-pressed='true'] {
		border-radius: 0.875rem;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
	}

	.pack-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.placeholder {
		padding: 1rem;
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.placeholder .cover {
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
