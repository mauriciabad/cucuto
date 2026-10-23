<script lang="ts">
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { DEFAULT_REPOSITORY_PATH } from '#lib/repository.js';
	import { PACK_FORMAT_URL } from '#lib/site.js';
</script>

<Seo
	title="For pack authors"
	description="Make sound packs for cucuto: the open .soundpack format, the packer tool and hosting your own pack repository."
/>

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">For pack authors</h1>
		<p class="body-large">
			Anyone can make a pack: animals, game sounds, a friend’s voice, a song. The format is open, so
			a pack made anywhere imports everywhere.
		</p>
	</header>

	<h2 class="headline-small">What’s in a pack</h2>
	<p>
		A <code>.soundpack</code> file is a zip with a <code>pack.json</code> manifest and one folder per
		character. Each character has a picture (JPEG XL recommended, PNG, WebP, AVIF and GIF work too), a
		pool of short notification clips, an optional vibration pattern and optional ringtones. One file can
		also hold a bundle of several packs.
	</p>
	<p>
		<a class="button tonal" href={PACK_FORMAT_URL} rel="external">
			Read the pack format
			<Icon name="arrow-outward" />
		</a>
	</p>

	<h2 class="headline-small">Build it</h2>
	<p>
		Put your clips and pictures in folders, describe them in a <code>pack.yaml</code>, and the
		<code>packer</code> command-line tool builds the <code>.soundpack</code>, checks it with the
		same rules as the app and can combine several packs into a bundle. A visual pack creator, in the
		app and on this website, is planned too: no tools needed.
	</p>

	<h2 class="headline-small">Share it</h2>
	<p>
		A pack repository is any HTTPS location that serves a <code>repository.json</code> index and the
		files it lists: GitHub Pages, Cloudflare Pages, a bucket or your own server. People add its URL
		in the app, browse your packs, preview the clips and download what they like. This site is the
		default repository, at <code>{DEFAULT_REPOSITORY_PATH}</code>.
	</p>

	<h2 class="headline-small">Only share what you may share</h2>
	<p>
		Publish only clips and pictures you made or have the rights to. The official repository hosts
		CC0 packs only. Personal packs with copyrighted content can stay private: import them straight
		from a file on your phone.
	</p>
</div>
