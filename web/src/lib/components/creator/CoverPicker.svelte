<!--
	A pack or bundle cover (#191): a picture framed square like sprites and written at the sprite size
	(`Authoring.Image`), as the app's creator does. Optional: without one, the app shows the first character.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		encodeSprite,
		loadPicture,
		renderCrop,
		type Crop,
		type EncodedImage,
		type Picture
	} from '#lib/creator/image.js';
	import CropDialog from './CropDialog.svelte';

	interface Props {
		cover: EncodedImage | null;
		/** Side of the written picture, in pixels. */
		size: number;
		/** The pack's or bundle's name, for the picture's alternative text. */
		name: string;
	}

	let { cover = $bindable(), size, name }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create);

	let picture = $state.raw<Picture | null>(null);
	let problem = $state('');

	async function pick(event: Event & { currentTarget: HTMLInputElement }) {
		const file = event.currentTarget.files?.[0];
		event.currentTarget.value = '';
		if (!file) return;
		problem = '';
		try {
			picture = await loadPicture(file);
		} catch (error) {
			problem = fill(t.picture.failed, { error: String(error) });
		}
	}

	async function use(crop: Crop) {
		const source = picture;
		picture = null;
		if (!source) return;
		try {
			cover = await encodeSprite(renderCrop(source, crop, size));
		} catch (error) {
			problem = fill(t.picture.failed, { error: String(error) });
		} finally {
			source.bitmap.close();
		}
	}
</script>

<div class="field cover-picker">
	<span class="label-large">{t.cover.title}</span>
	<div class="row">
		{#if cover}
			<img
				src={cover.preview}
				alt={fill(t.cover.alt, { name: name.trim() || t.drafts.untitled })}
				width="96"
				height="96"
			/>
		{:else}
			<div class="placeholder">
				<Icon name="add-photo" /><span class="visually-hidden">{t.cover.none}</span>
			</div>
		{/if}
		<div class="buttons">
			<label class="button tonal small file">
				<Icon name="add-photo" />
				{cover ? t.cover.change : t.cover.choose}
				<input
					class="visually-hidden"
					type="file"
					accept="image/*,.jxl,.svg,.avif,.webp"
					onchange={pick}
				/>
			</label>
			{#if cover}
				<button type="button" class="button outlined small" onclick={() => (cover = null)}>
					<Icon name="delete" />
					{t.cover.remove}
				</button>
			{/if}
		</div>
	</div>
	<span class="body-small on-surface-variant">{t.cover.hint}</span>
	<div aria-live="polite">
		{#if problem}<p class="problem body-medium"><Icon name="error" />{problem}</p>{/if}
	</div>
</div>

{#if picture}
	<CropDialog {picture} onuse={use} oncancel={() => (picture = null)} />
{/if}

<style>
	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 1rem;
	}

	img,
	.placeholder {
		width: 6rem;
		height: 6rem;
		border-radius: 1.25rem;
		object-fit: cover;
		background: var(--md-sys-color-surface-container-high);
	}

	.placeholder {
		display: grid;
		place-items: center;
		color: var(--md-sys-color-on-surface-variant);
	}

	.buttons {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
	}
</style>
