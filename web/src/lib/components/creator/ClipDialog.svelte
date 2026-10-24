<!--
	The clip editor: pick the part of a recording or file to keep, then the shared pipeline (shared/pack-spec's ClipDsp,
	the same Kotlin as the app's creator) trims silence, evens out loudness and fades the edges, and the clip is
	encoded on the device.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { durationMs, peaks, play, stop } from '#lib/creator/audio.js';
	import { encodeClip } from '#lib/creator/encode.js';
	import type { ClipEdit, SavedClip } from '#lib/creator/pack.js';
	import type { Authoring } from '#lib/pack-spec/index.js';

	interface Props {
		authoring: Authoring;
		source: Float32Array;
		sourceName: string;
		edit?: ClipEdit;
		onsave: (clip: SavedClip) => void;
		oncancel: () => void;
	}

	let { authoring, source, sourceName, edit, onsave, oncancel }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create.clipEditor);
	const targets = $derived(authoring.targets);
	const total = $derived(durationMs(source));
	const seconds = (ms: number) =>
		(ms / 1000).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

	function initial(): ClipEdit {
		if (edit) return { ...edit };
		const length = durationMs(source);
		if (length <= authoring.targets.clipMaxMs) {
			return { startMs: 0, endMs: Math.floor(length), trimSilence: true, normalize: true };
		}
		const [startMs, endMs] = authoring.findHook(source, 48_000, authoring.targets.hookLengthMs);
		return { startMs, endMs, trimSilence: true, normalize: true };
	}

	let sel = $state<ClipEdit>(initial());
	let busy = $state(false);
	let problem = $state('');
	let playing = $state(false);
	let dialog: HTMLDialogElement;
	let canvas: HTMLCanvasElement;
	const MIN_GAP = 100;

	onMount(() => {
		dialog.showModal();
		return stop;
	});

	const selected = $derived(sel.endMs - sel.startMs);

	$effect(() => {
		const ctx = canvas.getContext('2d');
		if (!ctx) return;
		const scale = window.devicePixelRatio || 1;
		const width = canvas.clientWidth * scale || 600;
		const height = canvas.clientHeight * scale || 120;
		canvas.width = width;
		canvas.height = height;
		const style = getComputedStyle(canvas);
		const bars = Math.max(32, Math.floor(width / (3 * scale)));
		const data = peaks(source, bars);
		// Scale quiet recordings up for display (at most 8×), so the shape is visible before normalizing.
		const loudest = data.reduce((a, b) => Math.max(a, b), 0);
		const zoom = loudest > 0 ? Math.min(8, 1 / loudest) : 1;
		for (let i = 0; i < data.length; i++) data[i] = Math.min(1, data[i] * zoom);
		const from = (sel.startMs / total) * width;
		const to = (sel.endMs / total) * width;
		ctx.clearRect(0, 0, width, height);
		ctx.fillStyle = style.getPropertyValue('--selection');
		ctx.fillRect(from, 0, to - from, height);
		const barWidth = width / bars;
		for (let i = 0; i < bars; i++) {
			const x = i * barWidth;
			const h = Math.max(2 * scale, data[i] * height * 0.9);
			const inside = x + barWidth / 2 >= from && x + barWidth / 2 <= to;
			ctx.fillStyle = style.getPropertyValue(inside ? '--bar-selected' : '--bar');
			ctx.fillRect(x + barWidth * 0.15, (height - h) / 2, barWidth * 0.7, h);
		}
	});

	function setStart(value: number) {
		sel.startMs = Math.min(value, sel.endMs - MIN_GAP);
		problem = '';
	}

	function setEnd(value: number) {
		sel.endMs = Math.max(value, sel.startMs + MIN_GAP);
		problem = '';
	}

	function hook() {
		const [startMs, endMs] = authoring.findHook(source, 48_000, targets.hookLengthMs);
		sel.startMs = startMs;
		sel.endMs = endMs;
	}

	async function preview() {
		if (playing) {
			stop();
			return;
		}
		playing = true;
		await play(source, sel.startMs, sel.endMs);
		playing = false;
	}

	async function save() {
		problem = '';
		if (selected > targets.clipMaxMs) {
			problem = t.tooLong;
			return;
		}
		busy = true;
		try {
			// Let the "Processing…" state paint before the synchronous pipeline runs.
			await new Promise((resolve) => setTimeout(resolve, 0));
			const clip = authoring.processClip(source, sel.startMs, sel.endMs, sel);
			if (clip.silent) {
				problem = t.silent;
				return;
			}
			if (clip.durationMs < targets.clipMinMs) {
				problem = fill(t.tooShort, { s: seconds(clip.durationMs) });
				return;
			}
			const encoded = await encodeClip(clip.samples, targets);
			stop();
			onsave({ encoded, samples: clip.samples, durationMs: clip.durationMs, edit: { ...sel } });
		} catch (error) {
			problem = fill(t.failed, { error: String(error) });
		} finally {
			busy = false;
		}
	}
</script>

<dialog
	bind:this={dialog}
	class="dialog wide"
	aria-labelledby="clip-title"
	aria-describedby="clip-source"
	oncancel={(event) => {
		event.preventDefault();
		stop();
		oncancel();
	}}
>
	<h2 id="clip-title" class="headline-small">{t.title}</h2>
	<p id="clip-source" class="body-medium on-surface-variant">
		{fill(t.source, { name: sourceName, s: seconds(total) })}
	</p>

	<canvas class="waveform" bind:this={canvas} aria-hidden="true"></canvas>

	<div class="ranges">
		<label>
			<span class="label-large">{t.start}</span>
			<input
				type="range"
				min="0"
				max={Math.floor(total)}
				step="10"
				value={sel.startMs}
				aria-valuetext="{seconds(sel.startMs)} s"
				oninput={(event) => setStart(Number(event.currentTarget.value))}
			/>
			<span class="body-medium value">{seconds(sel.startMs)} s</span>
		</label>
		<label>
			<span class="label-large">{t.end}</span>
			<input
				type="range"
				min="0"
				max={Math.floor(total)}
				step="10"
				value={sel.endMs}
				aria-valuetext="{seconds(sel.endMs)} s"
				oninput={(event) => setEnd(Number(event.currentTarget.value))}
			/>
			<span class="body-medium value">{seconds(sel.endMs)} s</span>
		</label>
	</div>

	<div class="row">
		<p class="body-medium" aria-live="polite">
			{fill(t.selection, { s: seconds(selected) })}
		</p>
		<button type="button" class="button tonal small" onclick={preview}>
			<Icon name={playing ? 'stop' : 'play-arrow'} />
			{t.play}
		</button>
		{#if total > targets.hookLengthMs * 2}
			<button
				type="button"
				class="button outlined small"
				onclick={hook}
				title={fill(t.hookHint, { s: seconds(targets.hookLengthMs) })}
			>
				<Icon name="music-note" />
				{t.hook}
			</button>
		{/if}
	</div>

	<fieldset class="options">
		<label><input type="checkbox" bind:checked={sel.trimSilence} /> {t.trimSilence}</label>
		<label><input type="checkbox" bind:checked={sel.normalize} /> {t.normalize}</label>
	</fieldset>

	<div aria-live="assertive">
		{#if problem}<p class="problem body-medium"><Icon name="error" />{problem}</p>{/if}
	</div>

	<div class="actions">
		<button
			type="button"
			class="button outlined"
			onclick={() => {
				stop();
				oncancel();
			}}>{t.cancel}</button
		>
		<button type="button" class="button" disabled={busy} onclick={save}>
			<Icon name="content-cut" />
			{busy ? t.processing : t.save}
		</button>
	</div>
</dialog>

<style>
	.waveform {
		display: block;
		width: 100%;
		min-width: 0;
		height: 7.5rem;
		border-radius: 1rem;
		background: var(--md-sys-color-surface-container);
		--bar: var(--md-sys-color-outline-variant);
		--bar-selected: var(--md-sys-color-primary);
		--selection: color-mix(in srgb, var(--md-sys-color-primary-container) 35%, transparent);
	}

	.ranges {
		display: grid;
		gap: 0.5rem;
	}

	.ranges label {
		display: grid;
		grid-template-columns: 4rem 1fr 4.5rem;
		align-items: center;
		gap: 0.75rem;
	}

	.ranges input {
		width: 100%;
		accent-color: var(--md-sys-color-primary);
	}

	.value {
		text-align: end;
		font-variant-numeric: tabular-nums;
	}

	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 0.75rem;
	}

	.row p {
		flex: 1;
		min-width: 10rem;
		font-variant-numeric: tabular-nums;
	}

	.options {
		display: grid;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		border: 0;
	}

	.options label {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.options input {
		width: 1.125rem;
		height: 1.125rem;
		accent-color: var(--md-sys-color-primary);
	}

	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
	}
</style>
