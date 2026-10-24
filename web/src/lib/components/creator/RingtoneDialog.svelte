<!--
	The ringtone version editor: when it rings, how it loops (docs/pack-format.md#ringtones-and-variants), its fade-in
	and how often "random per call" picks it. Read and written with shared/pack-spec's RingtoneForm, the same Kotlin as
	the app's creator, which also checks the loop against the song.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { play, stop } from '#lib/creator/audio.js';
	import { problemText } from '#lib/creator/conditions.js';
	import type { Authoring, RingtoneFormJson } from '#lib/pack-spec/index.js';
	import ConditionFields from './ConditionFields.svelte';

	interface Props {
		authoring: Authoring;
		entry: Record<string, unknown>;
		/** The version's name, for the title. */
		name: string;
		/** The song's length; null when unknown. */
		durationMs: number | null;
		/** The decoded song, for the loop preview; null when this browser cannot play it. */
		samples: Float32Array | null;
		onsave: (entry: Record<string, unknown>) => void;
		oncancel: () => void;
	}

	let { authoring, entry, name, durationMs, samples, onsave, oncancel }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create.ringtoneEditor);
	const tc = $derived(messages(locale).create.conditions);
	const prefix = `ringtone-${Math.random().toString(36).slice(2, 8)}`;
	const seconds = (ms: number) =>
		(ms / 1000).toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });

	// Seeded once from the entry; the dialog then edits its own copy.
	// svelte-ignore state_referenced_locally
	let form = $state<RingtoneFormJson>(authoring.ringtoneForm(entry));
	// svelte-ignore state_referenced_locally
	const total = Math.floor(durationMs ?? 0);
	const result = $derived(
		authoring.writeRingtone(
			$state.snapshot(form) as RingtoneFormJson,
			String(entry.file ?? 'ringtone'),
			durationMs
		)
	);
	let playing = $state(false);
	let dialog: HTMLDialogElement;

	onMount(() => {
		dialog.showModal();
		return stop;
	});

	function chooseLoop(mode: RingtoneFormJson['loop']) {
		form.loop = mode;
		if (mode === 'SECTION' && form.loopEndMs === null && total > 0) form.loopEndMs = total;
	}

	const loops = $derived<[RingtoneFormJson['loop'], string][]>([
		['WHOLE', t.loopWhole],
		['ONCE', t.loopOnce],
		['SECTION', t.loopSection]
	]);
	const loopEnd = $derived(form.loopEndMs ?? total);

	async function playLoop() {
		if (!samples) return;
		if (playing) {
			stop();
			playing = false;
			return;
		}
		playing = true;
		await play(samples, form.loopStartMs, loopEnd);
		playing = false;
	}

	function close(save: boolean) {
		stop();
		if (save) onsave(result.entry);
		else oncancel();
	}
</script>

<dialog
	bind:this={dialog}
	class="dialog wide"
	aria-labelledby="{prefix}-title"
	oncancel={(event) => {
		event.preventDefault();
		close(false);
	}}
>
	<h2 id="{prefix}-title" class="headline-small">{t.title}: {name}</h2>
	{#if durationMs !== null}
		<p class="body-medium on-surface-variant">
			{fill(t.source, {
				name:
					String(entry.file ?? name)
						.split('/')
						.pop() ?? name,
				s: seconds(durationMs)
			})}
		</p>
	{/if}

	<label class="field">
		<span class="label-large">{t.label}</span>
		<input type="text" maxlength="80" bind:value={form.label} autocomplete="off" />
	</label>

	<fieldset class="field">
		<legend class="title-medium">{tc.ringtoneTitle}</legend>
		<p class="body-small on-surface-variant">{tc.ringtoneIntro}</p>
		<ConditionFields bind:form={form.condition} clip={false} {prefix} />
	</fieldset>

	<fieldset class="field">
		<legend class="title-medium">{t.loop}</legend>
		{#each loops as [mode, label] (mode)}
			<label class="radio">
				<input
					type="radio"
					name="{prefix}-loop"
					value={mode}
					checked={form.loop === mode}
					onchange={() => chooseLoop(mode)}
				/>
				<span>{label}</span>
			</label>
		{/each}
		{#if form.loop === 'SECTION' && total > 0}
			<div class="ranges">
				<label>
					<span class="label-large">{t.loopStart}</span>
					<input
						type="range"
						min="0"
						max={total}
						step="10"
						value={form.loopStartMs}
						aria-valuetext="{seconds(form.loopStartMs)} s"
						oninput={(e) => (form.loopStartMs = Number(e.currentTarget.value))}
					/>
					<span class="body-medium value">{seconds(form.loopStartMs)} s</span>
				</label>
				<label>
					<span class="label-large">{t.loopEnd}</span>
					<input
						type="range"
						min="0"
						max={total}
						step="10"
						value={loopEnd}
						aria-valuetext="{seconds(loopEnd)} s"
						oninput={(e) => (form.loopEndMs = Number(e.currentTarget.value))}
					/>
					<span class="body-medium value">{seconds(loopEnd)} s</span>
				</label>
			</div>
			{#if samples}
				<button type="button" class="button tonal small play" onclick={playLoop}>
					<Icon name={playing ? 'stop' : 'play-arrow'} />
					{t.playLoop}
				</button>
			{/if}
		{/if}
	</fieldset>

	<label class="field">
		<span class="label-large">{t.fadeIn}</span>
		<span class="slider">
			<input
				type="range"
				min="0"
				max="10000"
				step="100"
				value={form.fadeInMs}
				aria-valuetext={fill(t.fadeInValue, { s: seconds(form.fadeInMs) })}
				oninput={(e) => (form.fadeInMs = Number(e.currentTarget.value))}
			/>
			<span class="body-medium value">{fill(t.fadeInValue, { s: seconds(form.fadeInMs) })}</span>
		</span>
	</label>

	<label class="field">
		<span class="label-large">{t.weight}</span>
		<input
			class="weight"
			type="number"
			min="0.1"
			max="100"
			step="0.1"
			value={form.weight}
			aria-describedby="{prefix}-weight"
			oninput={(e) => (form.weight = Number(e.currentTarget.value) || 0)}
		/>
		<span id="{prefix}-weight" class="body-small on-surface-variant">{t.weightHint}</span>
	</label>

	<div aria-live="polite">
		{#each result.problems as problem, i (i)}
			<p class="problem body-medium"><Icon name="error" />{problemText(problem, tc.problems)}</p>
		{/each}
	</div>

	<div class="actions">
		<button type="button" class="button outlined" onclick={() => close(false)}>{t.cancel}</button>
		<button
			type="button"
			class="button"
			disabled={result.problems.length > 0}
			onclick={() => close(true)}
		>
			<Icon name="ring-volume" />
			{t.save}
		</button>
	</div>
</dialog>

<style>
	.radio {
		display: flex;
		align-items: center;
		gap: 0.625rem;
		min-height: 2.5rem;
	}

	.radio input {
		width: 1.25rem;
		height: 1.25rem;
		margin: 0;
		flex: none;
		accent-color: var(--md-sys-color-primary);
	}

	.ranges {
		display: grid;
		gap: 0.5rem;
	}

	.ranges label {
		display: grid;
		grid-template-columns: 7rem 1fr 4.5rem;
		align-items: center;
		gap: 0.75rem;
	}

	.ranges input,
	.slider input {
		width: 100%;
		accent-color: var(--md-sys-color-primary);
	}

	.slider {
		display: grid;
		grid-template-columns: 1fr 4.5rem;
		align-items: center;
		gap: 0.75rem;
	}

	.value {
		text-align: end;
		font-variant-numeric: tabular-nums;
	}

	.play {
		align-self: flex-start;
	}

	.weight {
		max-width: 8rem;
	}

	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
	}
</style>
