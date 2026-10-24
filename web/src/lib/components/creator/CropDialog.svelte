<!--
	Frames a picture as a square sprite, like the app's cropper (SpriteCrop): drag or arrow keys to move, slider, wheel
	or +/− to zoom. The cookie outline shows how the app will present the character (brand/README.md, Shape).
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		clampCrop,
		cropRegion,
		MAX_ZOOM,
		MIN_ZOOM,
		type Crop,
		type Picture
	} from '#lib/creator/image.js';
	import { cookiePath } from './cookie.js';

	interface Props {
		picture: Picture;
		onuse: (crop: Crop) => void;
		oncancel: () => void;
	}

	let { picture, onuse, oncancel }: Props = $props();

	const t = $derived(messages(localeFromPath(page.url.pathname)).create.crop);

	let dialog: HTMLDialogElement;
	let canvas: HTMLCanvasElement;
	let crop = $state<Crop>({ zoom: 1, panX: 0, panY: 0 });
	const aspect = $derived(picture.width / Math.max(1, picture.height));
	const SIDE = 320;

	onMount(() => {
		dialog.showModal();
	});

	function update(next: Crop) {
		crop = clampCrop(next, aspect);
	}

	$effect(() => {
		const ctx = canvas.getContext('2d');
		if (!ctx) return;
		const scale = window.devicePixelRatio || 1;
		canvas.width = Math.round(SIDE * scale);
		canvas.height = Math.round(SIDE * scale);
		const r = cropRegion(crop, picture.width, picture.height);
		ctx.imageSmoothingQuality = 'high';
		ctx.clearRect(0, 0, canvas.width, canvas.height);
		ctx.drawImage(picture.bitmap, r.left, r.top, r.size, r.size, 0, 0, canvas.width, canvas.height);
	});

	let drag: { id: number; x: number; y: number } | null = null;

	function pointerdown(event: PointerEvent) {
		drag = { id: event.pointerId, x: event.clientX, y: event.clientY };
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
	}

	function pointermove(event: PointerEvent) {
		if (!drag || drag.id !== event.pointerId) return;
		const side = (event.currentTarget as HTMLElement).clientWidth || SIDE;
		update({
			...crop,
			panX: crop.panX + (event.clientX - drag.x) / side,
			panY: crop.panY + (event.clientY - drag.y) / side
		});
		drag = { ...drag, x: event.clientX, y: event.clientY };
	}

	function pointerup() {
		drag = null;
	}

	function wheel(event: WheelEvent) {
		event.preventDefault();
		update({ ...crop, zoom: crop.zoom * Math.exp(-event.deltaY / 500) });
	}

	function keydown(event: KeyboardEvent) {
		const step = event.shiftKey ? 0.2 : 0.05;
		const moves: Record<string, Partial<Crop>> = {
			ArrowLeft: { panX: crop.panX + step },
			ArrowRight: { panX: crop.panX - step },
			ArrowUp: { panY: crop.panY + step },
			ArrowDown: { panY: crop.panY - step },
			'+': { zoom: crop.zoom * 1.1 },
			'=': { zoom: crop.zoom * 1.1 },
			'-': { zoom: crop.zoom / 1.1 }
		};
		const move = moves[event.key];
		if (!move) return;
		event.preventDefault();
		update({ ...crop, ...move });
	}
</script>

<dialog
	bind:this={dialog}
	class="dialog"
	aria-labelledby="crop-title"
	oncancel={(event) => {
		event.preventDefault();
		oncancel();
	}}
>
	<h2 id="crop-title" class="headline-small">{t.title}</h2>
	<p id="crop-help" class="body-medium on-surface-variant">{t.help}</p>
	<!-- A 2D framing area with its own keys (arrows, + and −), described by #crop-help: ARIA's `application` role.
	     The zoom slider below offers the same control with a native input. -->
	<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
	<div
		class="viewport"
		role="application"
		tabindex="0"
		aria-label={t.area}
		aria-describedby="crop-help"
		onpointerdown={pointerdown}
		onpointermove={pointermove}
		onpointerup={pointerup}
		onpointercancel={pointerup}
		onwheel={wheel}
		onkeydown={keydown}
	>
		<canvas bind:this={canvas} width={SIDE} height={SIDE}></canvas>
		<svg viewBox="0 0 100 100" aria-hidden="true" focusable="false">
			<path class="shade" fill-rule="evenodd" d="M0 0H100V100H0Z {cookiePath(50, 50, 50)}" />
			<path class="outline" d={cookiePath(50, 50, 50)} />
		</svg>
	</div>
	<label class="zoom">
		<Icon name="zoom-in" />
		<span>{t.zoom}</span>
		<input
			type="range"
			min={MIN_ZOOM}
			max={MAX_ZOOM}
			step="0.01"
			value={crop.zoom}
			aria-valuetext="{crop.zoom.toFixed(1)}×"
			oninput={(event) => update({ ...crop, zoom: Number(event.currentTarget.value) })}
		/>
	</label>
	<div class="actions">
		<button type="button" class="button outlined" onclick={oncancel}>{t.cancel}</button>
		<button type="button" class="button" onclick={() => onuse(crop)}>{t.use}</button>
	</div>
</dialog>

<style>
	.viewport {
		position: relative;
		width: min(20rem, 100%);
		aspect-ratio: 1;
		margin-inline: auto;
		border-radius: 1.5rem;
		overflow: hidden;
		touch-action: none;
		cursor: grab;
		background: repeating-conic-gradient(
				var(--md-sys-color-surface-container-high) 0 25%,
				var(--md-sys-color-surface-container) 0 50%
			)
			0 0 / 1.5rem 1.5rem;
	}

	.viewport:active {
		cursor: grabbing;
	}

	.viewport canvas,
	.viewport svg {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
	}

	.shade {
		fill: color-mix(in srgb, var(--md-sys-color-scrim, #000) 45%, transparent);
	}

	.outline {
		fill: none;
		stroke: var(--md-sys-color-primary-container);
		stroke-width: 0.8;
	}

	.zoom {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.zoom input {
		flex: 1;
		accent-color: var(--md-sys-color-primary);
	}
</style>
