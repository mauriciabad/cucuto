<!-- One character of the pack being created: picture, name, color, clips and their conditions, ringtones. -->
<script lang="ts" module>
	// Decoded ringtones by file ID, for previews (not saved: a 6-minute song is 69 MB of floats). A plain cache that
	// nothing renders from, so it needs no reactivity.
	// eslint-disable-next-line svelte/prefer-svelte-reactivity
	const decodedRingtones = new Map<string, Float32Array | null>();
</script>

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
	import {
		uid,
		type CharacterState,
		type ClipState,
		type KeptFile,
		type RingtoneState,
		type SavedClip
	} from '#lib/creator/pack.js';
	import { conditionSummary } from '#lib/creator/conditions.js';
	import type { Authoring } from '#lib/pack-spec/index.js';
	import ClipDialog from './ClipDialog.svelte';
	import ConditionDialog from './ConditionDialog.svelte';
	import RingtoneDialog from './RingtoneDialog.svelte';
	import HapticEditor from './HapticEditor.svelte';
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

	// Conditions and ringtones.
	const summaryText = $derived(messages(locale).create.summary);
	const tr = $derived(messages(locale).create.ringtones);
	let conditionClip = $state<ClipState | null>(null);
	let editingRingtone = $state<{ ringtone: RingtoneState; samples: Float32Array | null } | null>(
		null
	);
	let playingRingtone = $state<string | null>(null);
	/** The audio formats a pack accepts (docs/pack-format.md#audio). */
	const RINGTONE_EXTENSIONS = ['ogg', 'opus', 'oga', 'mp3', 'm4a', 'aac', 'flac', 'wav'];
	const TOLERANCE_MS = 50;

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
			// The animated picture of an opened pack would still show the old picture (#222's default in the app).
			character.spriteAnimated = null;
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
				existing.source = item.source;
			}
		} else {
			character.clips.push({
				uid: uid('clip'),
				label: item.label,
				weight: 1,
				source: item.source,
				when: null,
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
		if (!clip.samples) return;
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

	const ringtoneKey = (r: RingtoneState, i: number) => r.uid ?? `${r.file.id}-${i}`;

	function ringtoneName(r: RingtoneState, i: number): string {
		const label = typeof r.entry.label === 'string' ? r.entry.label.trim() : '';
		return label || fill(tr.untitled, { n: i + 1 });
	}

	/** The decoded song of `file` (cached), or null when this browser cannot play it. */
	async function ringtoneSamples(file: KeptFile): Promise<Float32Array | null> {
		if (decodedRingtones.has(file.id)) return decodedRingtones.get(file.id) ?? null;
		const samples = await decodeToMono(file.bytes.slice().buffer).catch(() => null);
		decodedRingtones.set(file.id, samples);
		return samples;
	}

	async function addRingtones(event: Event & { currentTarget: HTMLInputElement }) {
		const files = [...(event.currentTarget.files ?? [])];
		event.currentTarget.value = '';
		problem = '';
		busy = true;
		try {
			for (const file of files) {
				const extension = file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase();
				if (!file.name.includes('.') || !RINGTONE_EXTENSIONS.includes(extension)) {
					problem = fill(tr.unsupported, { name: file.name });
					continue;
				}
				const bytes = new Uint8Array(await file.arrayBuffer());
				let samples: Float32Array;
				try {
					samples = await decodeToMono(bytes.slice().buffer);
				} catch {
					problem = fill(tr.decodeFailed, { name: file.name });
					continue;
				}
				const length = durationMs(samples);
				if (length < targets.ringtoneMinMs - TOLERANCE_MS) {
					problem = fill(tr.tooShort, { name: file.name, s: seconds(length) });
					continue;
				}
				if (length > targets.ringtoneMaxMs + TOLERANCE_MS) {
					problem = fill(tr.tooLong, { name: file.name });
					continue;
				}
				const kept: KeptFile = {
					id: uid('file'),
					bytes,
					extension,
					durationMs: Math.round(length),
					name: file.name
				};
				decodedRingtones.set(kept.id, samples);
				character.ringtones.push({
					uid: uid('ring'),
					file: kept,
					entry: { file: file.name, label: stem(file.name) }
				});
			}
		} finally {
			busy = false;
		}
	}

	async function togglePlayRingtone(r: RingtoneState, key: string) {
		if (playingRingtone === key) {
			stop();
			playingRingtone = null;
			return;
		}
		playingRingtone = key;
		const samples = await ringtoneSamples(r.file);
		if (!samples) {
			problem = tr.noPreview;
			playingRingtone = null;
			return;
		}
		if (playingRingtone !== key) return;
		await play(samples);
		if (playingRingtone === key) playingRingtone = null;
	}

	async function editRingtone(r: RingtoneState) {
		editingRingtone = { ringtone: r, samples: await ringtoneSamples(r.file) };
	}

	async function addVersion(r: RingtoneState, i: number) {
		const label = fill(tr.versionLabel, {
			label: ringtoneName(r, i),
			n: character.ringtones.filter((other) => other.file.id === r.file.id).length + 1
		});
		const version: RingtoneState = {
			uid: uid('ring'),
			file: r.file,
			entry: { file: r.entry.file, label }
		};
		character.ringtones.splice(i + 1, 0, version);
		await editRingtone(character.ringtones[i + 1]);
	}

	function removeRingtone(i: number) {
		character.ringtones.splice(i, 1);
	}

	function setRingtoneLabel(r: RingtoneState, label: string) {
		r.entry = { ...r.entry, label };
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
			<label class="field">
				<span class="label-large">{t.characters.description}</span>
				<textarea
					rows="2"
					maxlength="2000"
					value={character.description ?? ''}
					oninput={(event) => (character.description = event.currentTarget.value || null)}
				></textarea>
			</label>
		</div>
	</div>

	<div class="clips">
		<h4 id={id('clips')} class="title-medium">{t.clips.title}</h4>
		{#if character.clips.length > 0}
			<ol>
				{#each character.clips as clip, i (clip.uid)}
					{@const summary = conditionSummary(clip.when, clip.oneIn, summaryText, locale)}
					<li>
						<div class="clip-main">
							<button
								type="button"
								class="icon-button tonal"
								disabled={!clip.samples}
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
								class:active={clip.when !== null || (clip.oneIn ?? null) !== null}
								aria-label="{t.conditions.clipTitle}: {clip.label}"
								onclick={() => (conditionClip = clip)}
							>
								<Icon name="schedule" />
							</button>
							{#if clip.source ?? clip.samples}
								{@const source = (clip.source ?? clip.samples) as Float32Array}
								<button
									type="button"
									class="icon-button"
									aria-label="{t.clips.edit}: {clip.label}"
									onclick={() =>
										(pending = [...pending, { source, name: clip.label, label: clip.label, clip }])}
								>
									<Icon name="content-cut" />
								</button>
							{/if}
							<button
								type="button"
								class="icon-button"
								aria-label="{t.clips.remove}: {clip.label}"
								onclick={() => removeClip(clip)}
							>
								<Icon name="delete" />
							</button>
						</div>
						{#if summary}
							<p class="condition body-small"><Icon name="schedule" />{summary}</p>
						{/if}
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

	<div class="ringtones">
		<h4 id={id('ringtones')} class="title-medium">{tr.title}</h4>
		<p class="body-small on-surface-variant">{tr.intro}</p>
		{#if character.ringtones.length > 0}
			<ol aria-labelledby={id('ringtones')}>
				{#each character.ringtones as ringtone, i (ringtoneKey(ringtone, i))}
					{@const key = ringtoneKey(ringtone, i)}
					{@const name = ringtoneName(ringtone, i)}
					{@const summary = conditionSummary(
						ringtone.entry.when as Record<string, unknown> | null | undefined,
						null,
						summaryText,
						locale
					)}
					<li>
						<div class="clip-main">
							<button
								type="button"
								class="icon-button tonal"
								aria-label="{playingRingtone === key ? tr.stopPlaying : tr.play}: {name}"
								onclick={() => togglePlayRingtone(ringtone, key)}
							>
								<Icon name={playingRingtone === key ? 'stop' : 'play-arrow'} />
							</button>
							<label class="clip-label">
								<span class="visually-hidden">{tr.label} {i + 1}</span>
								<input
									type="text"
									maxlength="80"
									autocomplete="off"
									value={typeof ringtone.entry.label === 'string' ? ringtone.entry.label : ''}
									oninput={(event) => setRingtoneLabel(ringtone, event.currentTarget.value)}
								/>
							</label>
							{#if ringtone.file.durationMs}
								<span class="body-medium duration">
									{fill(t.clips.seconds, { s: seconds(ringtone.file.durationMs) })}
									<span class="format label-small">{ringtone.file.extension}</span>
								</span>
							{/if}
						</div>
						<div class="clip-tools">
							<button
								type="button"
								class="icon-button"
								aria-label="{tr.edit}: {name}"
								onclick={() => editRingtone(ringtone)}
							>
								<Icon name="edit" />
							</button>
							<button
								type="button"
								class="icon-button"
								aria-label="{tr.addVersion}: {name}"
								onclick={() => addVersion(ringtone, i)}
							>
								<Icon name="library-add" />
							</button>
							<button
								type="button"
								class="icon-button"
								aria-label="{tr.remove}: {name}"
								onclick={() => removeRingtone(i)}
							>
								<Icon name="delete" />
							</button>
						</div>
						<p class="condition body-small">
							<Icon name="schedule" />{summary || summaryText.always}
						</p>
					</li>
				{/each}
			</ol>
		{/if}
		<div class="clip-actions">
			<label class="button tonal small file" aria-disabled={busy}>
				<Icon name="ring-volume" />
				{tr.add}
				<input
					class="visually-hidden"
					type="file"
					accept=".ogg,.opus,.oga,.mp3,.m4a,.aac,.flac,.wav,audio/*"
					multiple
					disabled={busy}
					aria-describedby={id('ringtone-hint')}
					onchange={addRingtones}
				/>
			</label>
			<span id={id('ringtone-hint')} class="body-small on-surface-variant">{tr.hint}</span>
		</div>
	</div>

	<HapticEditor bind:character {authoring} name={displayName} />

	<div aria-live="polite">
		{#if problem}<p class="problem body-medium"><Icon name="error" />{problem}</p>{/if}
	</div>
</article>

{#if picture}
	<CropDialog {picture} onuse={usePicture} oncancel={() => (picture = null)} />
{/if}

{#if conditionClip}
	{@const clip = conditionClip}
	<ConditionDialog
		{authoring}
		name={clip.label}
		when={clip.when}
		oneIn={clip.oneIn ?? null}
		onsave={(result) => {
			clip.when = result.when;
			clip.oneIn = result.oneIn;
			conditionClip = null;
		}}
		oncancel={() => (conditionClip = null)}
	/>
{/if}

{#if editingRingtone}
	{@const editing = editingRingtone}
	{@const index = character.ringtones.indexOf(editing.ringtone)}
	<RingtoneDialog
		{authoring}
		entry={$state.snapshot(editing.ringtone.entry) as Record<string, unknown>}
		name={ringtoneName(editing.ringtone, Math.max(0, index))}
		durationMs={editing.ringtone.file.durationMs ??
			(editing.samples ? durationMs(editing.samples) : null)}
		samples={editing.samples}
		onsave={(entry) => {
			editing.ringtone.entry = entry;
			editingRingtone = null;
		}}
		oncancel={() => (editingRingtone = null)}
	/>
{/if}

{#if pending.length > 0}
	{@const item = pending[0]}
	{#key item}
		<ClipDialog
			{authoring}
			source={item.source}
			sourceName={item.name}
			edit={item.clip && item.clip.source === item.source ? item.clip.edit : undefined}
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

	.clips,
	.ringtones {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.clips ol,
	.ringtones ol {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.clips li,
	.ringtones li {
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
		align-items: center;
		gap: 0.5rem 0.75rem;
	}

	.condition {
		display: flex;
		flex-basis: 100%;
		align-items: center;
		gap: 0.375rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.condition :global(.icon) {
		width: 1rem;
		height: 1rem;
	}

	.icon-button.active {
		color: var(--md-sys-color-primary);
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
