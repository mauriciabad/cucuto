<script lang="ts">
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import markUrl from '#lib/assets/cucuto-mark.svg';
	import Seo from '#lib/components/Seo.svelte';

	const notFound = $derived(page.status === 404);
</script>

<Seo title={notFound ? 'Page not found' : 'Something went wrong'} />

<div class="prose error">
	<img src={markUrl} alt="" width="96" height="96" />
	<h1 class="display-small emphasized">
		{notFound ? 'Nobody’s home at this address' : 'Something went wrong'}
	</h1>
	<p class="body-large on-surface-variant">
		{notFound
			? 'This page doesn’t exist. Check the address or start from the home page.'
			: `The page couldn’t load (${page.status}). Try again in a moment.`}
	</p>
	<p><a class="button" href={resolve('/')}>Go to the home page</a></p>
</div>

<style>
	.error {
		padding-block: 4rem 2rem;
		text-align: center;
	}

	.error img {
		margin-inline: auto;
	}

	.error h1 {
		margin-top: 1.5rem;
	}
</style>
