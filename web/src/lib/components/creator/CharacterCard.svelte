<!-- One character of the pack being created: picture, name, color and clips. -->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		canRecord,
		decodeToMono,
		durationMs,
		play,
		record,
		stop,
		type Recording
	} from '#lib/creator/audio.js';
	import { encodeClip } from '#lib/creator/encode.js';
	import {
		encodeSprite,
		loadPicture,
		renderCrop,
		suggestSeedColor,
		type Crop,
		type Picture
	} from '#lib/creator/image.js';
	import { uid, type CharacterState, type ClipState, type SavedClip } from '#lib/creator/pack.js';
	import type { Authoring } from '#lib/pack-spec/index.js';
	import ClipDialog from './ClipDialog.svelte';
	import CropDialog from './CropDialog.svelte';
	import { cookiePolygon } from './cookie.js';

	interface Props {
		character: CharacterState;
		index: number;
		authoring: Authoring;
		onremove: () => void;
	}

	let { character = $bindable(), index, authoring, onremove }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create);
	const targets = $derived(authoring.targets);
	const seconds = (ms: number) =>
		(ms / 1000).toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
	const id = (part: string) => `${character.uid}-${part}`;
	const displayName = $derived(
		character.name.trim() || fill(t.characters.heading, { n: index + 1 })
	);
	const cookie = cookiePolygon();

	let picture = $state.raw<Picture | null>(null);
	let problem = $state('');
	let busy = $state(false);

	// Clip editor queue: recordings and files waiting to be cut, or a saved clip being edited again.
	interface Pending {
		source: Float32Array;
		name: string;
		label: string;
		clip?: ClipState;
	}
	let pending = $state.raw<Pending[]>([]);

	let recording = $state.raw<Recording | null>(null);
	let elapsed = $state(0);
	let level = $state(0);
	let recordings = 0;
	let playingClip = $state<string | null>(null);

	async function pickPicture(event: Event & { currentTarget: HTMLInputElement }) {
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

	async function usePicture(crop: Crop) {
		const source = picture;
		picture = null;
		if (!source) return;
		try {
			const canvas = renderCrop(source, crop, targets.spriteSize);
			character.sprite = await encodeSprite(canvas);
			character.seedColor = suggestSeedColor(canvas) ?? character.seedColor;
		} catch (error) {
			problem = fill(t.picture.failed, { error: String(error) });
		} finally {
			source.bitmap.close();
		}
	}

	const stem = (name: string) => name.replace(/\.[^.]+$/, '');

	async function addFiles(event: Event & { currentTarget: HTMLInputElement }) {
		const files = [...(event.currentTarget.files ?? [])];
		event.currentTarget.value = '';
		problem = '';
		busy = true;
		const queue: Pending[] = [];
		try {
			for (const file of files) {
				let source: Float32Array;
				try {
					source = await decodeToMono(await file.arrayBuffer());
				} catch {
					problem = fill(t.clips.decodeFailed, { name: file.name });
					continue;
				}
				if (durationMs(source) > targets.maxSourceMs) {
					problem = fill(t.clips.tooLong, { name: file.name });
					continue;
				}
				const item = { source, name: file.name, label: stem(file.name) };
				// Several short files go straight in, trimmed and evened out; one file or a long one opens the editor.
				if (files.length > 1 && durationMs(source) <= targets.clipMaxMs) {
					const added = await quickAdd(item);
					if (!added) queue.push(item);
				} else {
					queue.push(item);
				}
			}
		} finally {
			busy = false;
		}
		pending = [...pending, ...queue];
	}

	async function quickAdd(item: Pending): Promise<boolean> {
		const edit = {
			startMs: 0,
			endMs: Math.floor(durationMs(item.source)),
			trimSilence: true,
			normalize: true
		};
		const clip = authoring.processClip(item.source, edit.startMs, edit.endMs, edit);
		if (clip.silent || clip.durationMs < targets.clipMinMs) return false;
		const encoded = await encodeClip(clip.samples, targets);
		saveClip(item, { encoded, samples: clip.samples, durationMs: clip.durationMs, edit });
		return true;
	}

	function saveClip(item: Pending, saved: SavedClip) {
		if (item.clip) {
			const existing = character.clips.find((c) => c.uid === item.clip?.uid);
			if (existing) {
				existing.encoded = saved.encoded;
				existing.samples = saved.samples;
				existing.durationMs = saved.durationMs;
				existing.edit = saved.edit;
			}
		} else {
			character.clips.push({
				uid: uid('clip'),
				label: item.label,
				weight: 1,
				source: item.source,
				...saved
			});
		}
	}

	async function toggleRecording() {
		problem = '';
		if (recording) {
			const r = recording;
			recording = null;
			try {
				const source = await r.stop();
				recordings += 1;
				const label = fill(t.clips.recordingLabel, { n: recordings });
				pending = [...pending, { source, name: label, label }];
			} catch (error) {
				problem = fill(t.clips.micFailed, { error: String(error) });
			}
			return;
		}
		try {
			const r = await record();
			recording = r;
			const started = performance.now();
			const tick = () => {
				if (recording !== r) return;
				elapsed = performance.now() - started;
				level = r.level();
				if (elapsed >= targets.clipMaxMs) void toggleRecording();
				else requestAnimationFrame(tick);
			};
			elapsed = 0;
			requestAnimationFrame(tick);
		} catch (error) {
			problem = fill(t.clips.micFailed, {
				error: error instanceof Error ? error.message : String(error)
			});
		}
	}

	async function togglePlay(clip: ClipState) {
		if (playingClip === clip.uid) {
			stop();
			playingClip = null;
			return;
		}
		playingClip = clip.uid;
		await play(clip.samples);
		if (playingClip === clip.uid) playingClip = null;
	}

	function removeClip(clip: ClipState) {
		character.clips = character.clips.filter((c) => c.uid !== clip.uid);
	}
</script>

<article class="character card" aria-labelledby={id('heading')}>
	<header>
		<h3 id={id('heading')} class="title-large">{displayName}</h3>
		<button
			type="button"
			class="icon-button"
			onclick={onremove}
			aria-label="{t.characters.remove}: {displayName}"
		>
			<Icon name="delete" />
		</button>
	</header>

	<div class="identity">
		<div
			class="sprite"
			style:--seed={character.seedColor ?? 'var(--md-sys-color-primary-container)'}
		>
			{#if character.sprite}
				<img
					src={character.sprite.preview}
					alt={fill(t.picture.alt, { name: displayName })}
					width="160"
					height="160"
					style:clip-path={cookie}
				/>
			{:else}
				<div class="placeholder" style:clip-path={cookie}>
					<Icon name="add-photo" />
					<span class="visually-hidden">{t.picture.none}</span>
				</div>
			{/if}
			<label class="button tonal small file">
				<Icon name="add-photo" />
				{character.sprite ? t.picture.change : t.picture.choose}
				<input
					class="visually-hidden"
					type="file"
					accept="image/*,.jxl,.svg,.avif,.webp"
					onchange={pickPicture}
				/>
			</label>
		</div>

		<div class="fields">
			<label class="field">
				<span class="label-large">{t.characters.name}</span>
				<input type="text" maxlength="80" required bind:value={character.name} autocomplete="off" />
			</label>
			<div class="field">
				<label class="label-large" for={id('color')}>{t.characters.color}</label>
				<div class="color">
					<input
						id={id('color')}
						type="color"
						value={character.seedColor ?? '#FF6B2C'}
						aria-describedby={id('color-hint')}
						oninput={(event) => (character.seedColor = event.currentTarget.value.toUpperCase())}
					/>
					<code>{character.seedColor ?? '—'}</code>
				</div>
				<p id={id('color-hint')} class="body-small on-surface-variant">{t.characters.colorHint}</p>
			</div>
		</div>
	</div>

	<div class="clips">
		<h4 id={id('clips')} class="title-medium">{t.clips.title}</h4>
		{#if character.clips.length > 0}
			<ol>
				{#each character.clips as clip, i (clip.uid)}
					<li>
						<div class="clip-main">
							<button
								type="button"
								class="icon-button tonal"
								aria-label="{playingClip === clip.uid
									? t.clips.stopPlaying
									: t.clips.play}: {clip.label}"
								onclick={() => togglePlay(clip)}
							>
								<Icon name={playingClip === clip.uid ? 'stop' : 'play-arrow'} />
							</button>
							<label class="clip-label">
								<span class="visually-hidden">{t.clips.label} {i + 1}</span>
								<input type="text" maxlength="80" bind:value={clip.label} autocomplete="off" />
							</label>
							<span class="body-medium duration">
								{fill(t.clips.seconds, { s: seconds(clip.durationMs) })}
								<span class="format label-small">{clip.encoded.extension}</span>
							</span>
						</div>
						<div class="clip-tools">
							<label class="weight">
								<span class="body-small">{t.clips.weight}</span>
								<input
									type="number"
									min="0.1"
									max="100"
									step="0.1"
									bind:value={clip.weight}
									title={t.clips.weightHint}
								/>
							</label>
							<button
								type="button"
								class="icon-button"
								aria-label="{t.clips.edit}: {clip.label}"
								onclick={() =>
									(pending = [
										...pending,
										{ source: clip.source, name: clip.label, label: clip.label, clip }
									])}
							>
								<Icon name="content-cut" />
							</button>
							<button
								type="button"
								class="icon-button"
								aria-label="{t.clips.remove}: {clip.label}"
								onclick={() => removeClip(clip)}
							>
								<Icon name="delete" />
							</button>
						</div>
						{#if clip.durationMs > targets.clipHintMs}
							<p class="hint body-small"><Icon name="info" />{t.clips.longHint}</p>
						{/if}
					</li>
				{/each}
			</ol>
		{/if}

		<div class="clip-actions">
			{#if canRecord()}
				<button
					type="button"
					class="button small"
					class:recording={recording !== null}
					aria-pressed={recording !== null}
					onclick={toggleRecording}
				>
					<Icon name={recording ? 'stop' : 'mic'} />
					{recording ? t.clips.stop : t.clips.record}
				</button>
			{/if}
			<label class="button tonal small file" aria-disabled={busy}>
				<Icon name="audio-file" />
				{t.clips.upload}
				<input
					class="visually-hidden"
					type="file"
					accept="audio/*,.ogg,.opus,.oga,.mp3,.m4a,.aac,.flac,.wav"
					multiple
					disabled={busy}
					onchange={addFiles}
				/>
			</label>
		</div>
		{#if recording}
			<div class="meter" role="status">
				<span class="level" style:--level={level}></span>
				<span class="body-medium">
					{fill(t.clips.recording, { time: seconds(elapsed), max: seconds(targets.clipMaxMs) })}
				</span>
			</div>
		{:else if !canRecord()}
			<p class="body-small on-surface-variant">{t.clips.noMic}</p>
		{/if}
	</div>

	<div aria-live="polite">
		{#if problem}<p class="problem body-medium"><Icon name="error" />{problem}</p>{/if}
	</div>
</article>

{#if picture}
	<CropDialog {picture} onuse={usePicture} oncancel={() => (picture = null)} />
{/if}

{#if pending.length > 0}
	{@const item = pending[0]}
	{#key item}
		<ClipDialog
			{authoring}
			source={item.source}
			sourceName={item.name}
			edit={item.clip?.edit}
			onsave={(saved) => {
				saveClip(item, saved);
				pending = pending.slice(1);
			}}
			oncancel={() => (pending = pending.slice(1))}
		/>
	{/key}
{/if}

<style>
	.character {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
	}

	header h3 {
		overflow-wrap: anywhere;
	}

	.identity {
		display: flex;
		flex-wrap: wrap;
		gap: 1.5rem;
	}

	.sprite {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.75rem;
	}

	.sprite img,
	.placeholder {
		width: 10rem;
		height: 10rem;
		object-fit: cover;
		background: color-mix(in srgb, var(--seed) 30%, var(--md-sys-color-surface-container-high));
	}

	.placeholder {
		display: grid;
		place-items: center;
		color: var(--md-sys-color-on-surface-variant);
	}

	.placeholder :global(.icon) {
		width: 3rem;
		height: 3rem;
	}

	.fields {
		display: flex;
		flex: 1;
		flex-direction: column;
		gap: 1rem;
		min-width: min(16rem, 100%);
	}

	.color {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.color input {
		width: 3rem;
		height: 3rem;
		padding: 0.25rem;
		border: 1px solid var(--md-sys-color-outline);
		border-radius: 1rem;
		background: none;
		cursor: pointer;
	}

	.clips {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.clips ol {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.clips li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 0.75rem;
		padding: 0.5rem 0.75rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container);
	}

	.clip-main {
		display: flex;
		flex: 1 1 18rem;
		align-items: center;
		gap: 0.75rem;
		min-width: 0;
	}

	.clip-label {
		flex: 1;
		min-width: 0;
	}

	.clip-label input {
		width: 100%;
	}

	.duration {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}

	.format {
		padding: 0.125rem 0.375rem;
		border-radius: 0.5rem;
		background: var(--md-sys-color-surface-container-highest);
		text-transform: uppercase;
	}

	.clip-tools {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}

	.weight {
		display: flex;
		align-items: center;
		gap: 0.375rem;
		margin-inline-end: 0.25rem;
	}

	.weight input {
		width: 4.5rem;
	}

	.hint {
		display: flex;
		flex-basis: 100%;
		gap: 0.375rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.hint :global(.icon) {
		width: 1rem;
		height: 1rem;
	}

	.clip-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.button.recording {
		--button-bg: var(--md-sys-color-error);
		--button-fg: var(--md-sys-color-on-error);
	}

	.meter {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		font-variant-numeric: tabular-nums;
	}

	.level {
		width: 6rem;
		height: 0.5rem;
		border-radius: 0.25rem;
		background:
			linear-gradient(
				to right,
				var(--md-sys-color-primary) calc(var(--level) * 100%),
				transparent 0
			),
			var(--md-sys-color-surface-container-highest);
	}

	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
	}
</style>
