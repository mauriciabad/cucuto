<script lang="ts">
	// Commands and examples follow tools/packer/README.md and docs/pack-format.md; keep them in sync.
	import { page } from '$app/state';
	import Code from '#lib/components/Code.svelte';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackCheck from '#lib/components/PackCheck.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { pageHref } from '#lib/i18n/links.js';
	import { DEFAULT_REPOSITORY_PATH } from '#lib/repository.js';
	import { REPO_URL } from '#lib/site.js';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).authors);

	const sections = $derived([
		{ id: 'whats-in-a-pack', title: t.whatTitle },
		{ id: 'make', title: t.makeTitle },
		{ id: 'packer', title: t.packerTitle },
		{ id: 'moments', title: t.momentsTitle },
		{ id: 'in-the-app', title: t.showTitle },
		{ id: 'check', title: t.checkTitle },
		{ id: 'host', title: t.hostTitle },
		{ id: 'rights', title: t.rightsTitle },
		{ id: 'spec', title: t.specTitle }
	]);

	const tree = `my-cats.soundpack.zip
├── pack.json
├── cover.jxl
├── LICENSE
└── characters/
    └── tabby/
        ├── sprite.jxl
        └── clips/
            ├── meow.ogg
            └── purr.ogg`;

	const install = `git clone ${REPO_URL}.git
cd cucuto/tools/packer
./gradlew installDist
# The command is now cli/build/install/packer/bin/packer`;

	const yaml = `id: io.github.me.my-cats        # reverse domain, unique
name: My cats
version: 1.0.0
author: Me
license: CC-BY-4.0              # an SPDX license expression
cover: art/cover.png
characters:
  - name: Tabby
    sprite: tabby/tabby.png
    seedColor: "#F4A7C0"
    clips: tabby/clips/          # every audio file in the folder
    haptic: auto                 # vibration follows the clip
  - name: Mittens
    sprite: mittens.svg
    clips:
      - mittens/meow-*.ogg`;

	const moments = `characters:
  - name: Tweet
    sprite: tweet.png
    clips:
      - tweet/chirp.ogg                # the usual rotation
      - file: tweet/hoot.ogg
        when: night                    # only at night
      - file: tweet/dawn-chorus.ogg
        when: { firstOfDay: true, time: { from: "05:00", to: "12:00" } }
      - file: tweet/squawk.ogg
        when: { burst: 3 }             # the third message in a row
      - file: tweet/cuckoo.ogg
        oneIn: 25                      # about one message in 25
      - file: tweet/scream.ogg
        oneIn: 13
        when: { date: "10-31" }        # a rare Halloween scream
    ringtones:
      - file: tweet/song-live.ogg      # first: rings when nothing else matches
        label: Live
      - file: tweet/song-radio.ogg
        label: Radio
        tags: [night]
        when: night`;

	const commands = `packer build my-cats -o dist/ --opus --normalize --trim-silence --jxl
packer validate dist/*.soundpack.zip
packer bundle --id io.github.me.all --name "All my packs" --version 1.0.0 \\
  -o dist/ my-cats my-dogs`;

	const index = `packer index -o site/repo --name "My packs" dist/

site/repo/
├── repository.json
├── previews/      (covers, sprites, one sample clip per character)
└── packs/         (the .soundpack.zip files)`;

	const addLink = `<a href="cucuto://repo/add?url=https://example.com/packs/repository.json">
  Add to app
</a>`;
</script>

<Seo title={t.title} description={t.description} />

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">{t.title}</h1>
		<p class="body-large">{t.lead}</p>
	</header>

	<nav class="toc" aria-labelledby="toc-title">
		<h2 id="toc-title" class="title-medium">{t.toc}</h2>
		<ul>
			{#each sections as section (section.id)}
				<li><a href="#{section.id}">{section.title}</a></li>
			{/each}
		</ul>
	</nav>

	<h2 id="whats-in-a-pack" class="headline-small">{t.whatTitle}</h2>
	<p><Html text={t.whatHtml} /></p>
	<Code code={tree} />

	<h3 class="title-large">{t.mediaTitle}</h3>
	<ul class="media">
		{#each t.media as item (item.title)}
			<li>
				<span class="media-icon"><Icon name={item.icon} /></span>
				<div>
					<h4 class="title-medium">{item.title}</h4>
					<p class="body-medium on-surface-variant">{item.text}</p>
				</div>
			</li>
		{/each}
	</ul>
	<p><Html text={t.mediaNoteHtml} /></p>

	<h2 id="make" class="headline-small">{t.makeTitle}</h2>
	<ol class="ways">
		{#each t.make as way (way.title)}
			<li>
				<h3 class="title-medium">{way.title}</h3>
				<p class="body-medium on-surface-variant"><Html text={way.textHtml} /></p>
			</li>
		{/each}
	</ol>

	<h2 id="packer" class="headline-small">{t.packerTitle}</h2>
	<p><Html text={t.packerIntroHtml} /></p>
	<Code code={install} />
	<p><Html text={t.packerYamlHtml} /></p>
	<Code code={yaml} />
	<p><Html text={t.packerCommandsHtml} /></p>
	<Code code={commands} />
	<p><Html text={t.packerMoreHtml} /></p>

	<h2 id="moments" class="headline-small">{t.momentsTitle}</h2>
	<p><Html text={t.momentsIntroHtml} /></p>
	<ul>
		{#each t.moments as item (item)}
			<li><Html text={item} /></li>
		{/each}
	</ul>
	<p><Html text={t.momentsYamlHtml} /></p>
	<Code code={moments} />
	<p><Html text={t.momentsNoteHtml} /></p>

	<h2 id="in-the-app" class="headline-small">{t.showTitle}</h2>
	<ul>
		{#each t.show as item (item)}
			<li><Html text={item} /></li>
		{/each}
	</ul>

	<h2 id="check" class="headline-small">{t.checkTitle}</h2>
	<p><Html text={t.checkHtml} /></p>
	<PackCheck />

	<h2 id="host" class="headline-small">{t.hostTitle}</h2>
	<p><Html text={t.hostIntroHtml} /></p>
	<Code code={index} />
	<p><Html text={t.hostShareHtml} /></p>
	<Code code={addLink} />
	<p><Html text={t.hostPrivateHtml} values={{ path: DEFAULT_REPOSITORY_PATH }} /></p>

	<h2 id="rights" class="headline-small">{t.rightsTitle}</h2>
	<p><Html text={t.rightsHtml} /></p>

	<h2 id="spec" class="headline-small">{t.specTitle}</h2>
	<p><Html text={t.specHtml} /></p>
	<p>
		<a class="button tonal" href={pageHref('/authors/pack-format/', locale)}>
			<Icon name="menu-book" />
			{t.specButton}
		</a>
	</p>
</div>

<style>
	.toc {
		padding: 1.25rem 1.5rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.toc ul {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1.25rem;
		margin-top: 0.5rem;
		padding: 0;
		list-style: none;
	}

	.toc a {
		display: inline-block;
		padding-block: 0.25rem;
	}

	.media {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
		gap: 0.75rem;
		list-style: none;
	}

	.prose > .media {
		padding: 0;
	}

	.prose > .media > li + li {
		margin-top: 0;
	}

	.media li {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding: 1.25rem;
		border-radius: 1.5rem;
		box-shadow: inset 0 0 0 1px var(--md-sys-color-outline-variant);
	}

	.media p {
		margin-top: 0.25rem;
	}

	.media-icon {
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	.ways {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
		gap: 0.75rem;
		list-style: none;
		counter-reset: way;
	}

	.prose > .ways {
		padding: 0;
	}

	.prose > .ways > li + li {
		margin-top: 0;
	}

	.ways li {
		counter-increment: way;
		padding: 1.25rem;
		border-radius: 1.5rem;
		background: var(--md-sys-color-surface-container);
	}

	.ways li::before {
		content: counter(way);
		display: block;
		margin-bottom: 0.25rem;
		font: 800 2rem/1 var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
		color: var(--md-sys-color-primary);
	}

	.ways p {
		margin-top: 0.25rem;
	}

	.prose > h3 {
		margin-top: 1.75rem;
	}
</style>
