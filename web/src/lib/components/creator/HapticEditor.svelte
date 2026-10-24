<!--
	A character's vibration (#191): the format's automatic one, none, or a custom timeline of taps and swells
	(docs/pack-format.md#haptics). The pattern is checked with shared/pack-spec (HapticPatternText, the importer's
	rules), and "Copy for AI" / "Paste pattern" use the same text format as the app's haptic editor (#217, ADR 0053).
	Browsers cannot play rich haptics, so the page draws the timeline and says to preview it in the app.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		endOf,
		loudnessRows,
		newSwell,
		newTap,
		sorted,
		timelinePaths
	} from '#lib/creator/haptics.js';
	import type { CharacterState } from '#lib/creator/pack.js';
	import type { Authoring, HapticEventJson, HapticProblem } from '#lib/pack-spec/index.js';

	interface Props {
		character: CharacterState;
		authoring: Authoring;
		/** The character's name as shown, for the prompt. */
		name: string;
	}

	let { character = $bindable(), authoring, name }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create.hapticEditor);
	const id = (part: string) => `${character.uid}-haptic-${part}`;
	const targets = $derived(authoring.targets);
	const MODES = ['auto', 'none', 'custom'] as const;

	let pasteText = $state('');
	let pasteProblem = $state('');
	let pasteDone = $state(false);
	let previous = $state.raw<HapticEventJson[] | null>(null);
	let copyStatus = $state<'idle' | 'copied' | 'failed'>('idle');
	let prompt = $state('');

	const events = $derived(character.haptic.events);
	const check = $derived(
		events.length === 0
			? null
			: authoring.parseHapticPattern(JSON.stringify({ mode: 'custom', events }))
	);
	const total = $derived(endOf(events));
	const span = $derived(Math.max(1000, Math.ceil((total + 100) / 500) * 500));
	const paths = $derived(timelinePaths(events, span, 600, 80));

	function problemText(problem: HapticProblem): string {
		const where =
			problem.point !== undefined
				? fill(t.where.point, { event: problem.event ?? 0, point: problem.point })
				: problem.event !== undefined
					? fill(t.where.event, { event: problem.event })
					: t.where.pattern;
		const values: Record<string, string | number> = { where };
		for (const [key, value] of Object.entries(problem)) {
			if (key !== 'kind' && value !== undefined) values[key] = value as string | number;
		}
		return fill(t.problems[problem.kind] ?? problem.kind, values);
	}

	function setEvents(next: HapticEventJson[]) {
		character.haptic.events = next;
	}

	function add(event: HapticEventJson) {
		setEvents([...$state.snapshot(events), event]);
	}

	function remove(index: number) {
		setEvents(events.filter((_, i) => i !== index));
	}

	/** Keeps events in start order once a start time is committed (the format requires it). */
	function resort() {
		setEvents(sorted($state.snapshot(events)));
	}

	function addPoint(event: HapticEventJson) {
		if (!event.envelope || event.envelope.length >= targets.hapticMaxPoints) return;
		// A new point goes before the closing one, so the swell still ends at zero.
		const closing = event.envelope.length - 1;
		event.envelope.splice(closing, 0, { intensity: 0.6, sharpness: 0.5, ms: 60 });
	}

	function removePoint(event: HapticEventJson, index: number) {
		if (!event.envelope || event.envelope.length <= 1) return;
		event.envelope.splice(index, 1);
	}

	function promptClips() {
		const rate = targets.sampleRate;
		return character.clips.map((clip, i) => ({
			path: `clips/${clip.label.trim() || `clip-${i + 1}`}.${clip.encoded.extension}`,
			durationMs: Math.round(clip.durationMs),
			loudness: clip.samples ? loudnessRows(clip.samples, rate) : null
		}));
	}

	async function copyForAi() {
		prompt = authoring.hapticPrompt(
			name,
			promptClips(),
			check?.valid ? $state.snapshot(events) : null
		);
		try {
			await navigator.clipboard.writeText(prompt);
			copyStatus = 'copied';
		} catch {
			copyStatus = 'failed';
		}
	}

	function usePattern() {
		pasteDone = false;
		const parsed = authoring.parseHapticPattern(pasteText);
		if (!parsed.valid) {
			pasteProblem = problemText(parsed.problem);
			return;
		}
		pasteProblem = '';
		previous = $state.snapshot(events);
		setEvents(parsed.events);
		pasteText = '';
		pasteDone = true;
	}

	function undo() {
		if (!previous) return;
		setEvents(previous);
		previous = null;
		pasteDone = false;
	}

	const ms = (value: number) => Math.round(value).toLocaleString(locale);
</script>

<fieldset class="field haptic">
	<legend class="title-medium">{t.title}</legend>
	<div class="modes">
		{#each MODES as mode (mode)}
			<label class="radio">
				<input type="radio" name={id('mode')} value={mode} bind:group={character.haptic.mode} />
				<span>{t[mode]}</span>
			</label>
		{/each}
	</div>

	{#if character.haptic.mode === 'custom'}
		<p class="body-small on-surface-variant">{t.intro}</p>

		<svg
			class="timeline"
			viewBox="0 0 600 80"
			preserveAspectRatio="none"
			role="img"
			aria-label={fill(t.timeline, { n: events.length, ms: ms(total) })}
		>
			<line x1="0" y1="80" x2="600" y2="80" class="axis" />
			{#each paths.envelopes as points, i (i)}
				<polyline {points} class="envelope" />
			{/each}
			{#each paths.presets as points, i (i)}
				<polyline {points} class="preset" />
			{/each}
		</svg>
		<div class="scale body-small on-surface-variant" aria-hidden="true">
			<span>0 ms</span><span>{ms(span)} ms</span>
		</div>

		{#if events.length === 0}
			<p class="body-medium">{t.empty}</p>
			<p class="body-small on-surface-variant">{t.emptyNote}</p>
		{:else}
			<ol class="events">
				{#each character.haptic.events as event, i (i)}
					{@const kind = event.envelope ? t.swell : t.tap}
					<li>
						<div class="event-head">
							<span class="label-large">{fill(t.event, { n: i + 1, kind })}</span>
							<label class="inline">
								<span class="body-small">{t.start}</span>
								<input
									type="number"
									min="0"
									max={targets.hapticMaxMs}
									step="10"
									bind:value={event.at}
									onchange={resort}
								/>
							</label>
							<button
								type="button"
								class="icon-button"
								aria-label={fill(t.removeEvent, { n: i + 1 })}
								onclick={() => remove(i)}
							>
								<Icon name="delete" />
							</button>
						</div>
						{#if event.envelope}
							<table class="points">
								<thead>
									<tr>
										<th scope="col"><span class="visually-hidden">#</span></th>
										<th scope="col" class="body-small">{t.intensity}</th>
										<th scope="col" class="body-small">{t.sharpness}</th>
										<th scope="col" class="body-small">{t.duration}</th>
									</tr>
								</thead>
								<tbody>
									{#each event.envelope as point, p (p)}
										{@const label = fill(t.point, { n: p + 1 })}
										<tr>
											<th scope="row" class="body-small">{label}</th>
											<td>
												<input
													type="range"
													min="0"
													max="1"
													step="0.05"
													bind:value={point.intensity}
													aria-label="{t.intensity}, {label}"
												/>
											</td>
											<td>
												<input
													type="range"
													min="0"
													max="1"
													step="0.05"
													bind:value={point.sharpness}
													aria-label="{t.sharpness}, {label}"
												/>
											</td>
											<td>
												<input
													type="number"
													min="1"
													max={targets.hapticMaxMs}
													step="10"
													bind:value={point.ms}
													aria-label="{t.duration}, {label}"
												/>
												{#if event.envelope.length > 1}
													<button
														type="button"
														class="icon-button small"
														aria-label={fill(t.removePoint, { n: p + 1 })}
														onclick={() => removePoint(event, p)}
													>
														<Icon name="close" />
													</button>
												{/if}
											</td>
										</tr>
									{/each}
								</tbody>
							</table>
							{#if event.envelope.length < targets.hapticMaxPoints}
								<button type="button" class="button tonal small" onclick={() => addPoint(event)}>
									<Icon name="add" />
									{t.addPoint}
								</button>
							{/if}
						{:else}
							<div class="preset-fields">
								<label class="inline">
									<span class="body-small">{t.preset}</span>
									<select bind:value={event.preset}>
										{#each ['CLICK', 'TICK', 'LOW_TICK'] as preset (preset)}
											<option value={preset}>{t.presets[preset]}</option>
										{/each}
									</select>
								</label>
								<label class="inline">
									<span class="body-small">{t.strength}</span>
									<input
										type="range"
										min="0"
										max="1"
										step="0.05"
										value={event.scale ?? 1}
										oninput={(e) => (event.scale = Number(e.currentTarget.value))}
									/>
								</label>
							</div>
						{/if}
					</li>
				{/each}
			</ol>
		{/if}

		<div class="actions">
			{#if events.length < targets.hapticMaxEvents}
				<button type="button" class="button tonal small" onclick={() => add(newTap(events))}>
					<Icon name="add" />
					{t.addTap}
				</button>
				<button type="button" class="button tonal small" onclick={() => add(newSwell(events))}>
					<Icon name="add" />
					{t.addSwell}
				</button>
			{/if}
		</div>

		<div aria-live="polite" class="check">
			{#if check?.valid}
				<p class="body-medium ok">
					<Icon name="check-circle" />{fill(t.valid, { n: events.length, ms: ms(total) })}
				</p>
			{:else if check && !check.valid}
				<p class="body-medium problem"><Icon name="error" />{problemText(check.problem)}</p>
			{/if}
		</div>

		<div class="ai">
			<div class="ai-copy">
				<button
					type="button"
					class="button small"
					onclick={copyForAi}
					aria-describedby={id('ai-hint')}
				>
					<Icon name="content-copy" />
					{t.copyAi}
				</button>
				<p id={id('ai-hint')} class="body-small on-surface-variant">{t.copyAiHint}</p>
				<div aria-live="polite">
					{#if copyStatus === 'copied'}
						<p class="body-small">{t.copied}</p>
					{:else if copyStatus === 'failed'}
						<p class="body-small">{t.copyFailed}</p>
						<textarea class="prompt" rows="6" readonly aria-label={t.copyAi}>{prompt}</textarea>
					{/if}
				</div>
			</div>
			<label class="field">
				<span class="label-large">{t.paste}</span>
				<textarea
					rows="3"
					bind:value={pasteText}
					aria-describedby={id('paste-hint')}
					spellcheck="false"></textarea>
				<span id={id('paste-hint')} class="body-small on-surface-variant">{t.pasteHint}</span>
			</label>
			<div class="actions">
				<button
					type="button"
					class="button tonal small"
					disabled={pasteText.trim() === ''}
					onclick={usePattern}
				>
					<Icon name="content-paste" />
					{t.usePattern}
				</button>
				{#if previous}
					<button type="button" class="button outlined small" onclick={undo}>
						<Icon name="undo" />
						{t.undo}
					</button>
				{/if}
			</div>
			<div aria-live="polite">
				{#if pasteProblem}
					<p class="body-medium problem"><Icon name="error" />{pasteProblem}</p>
				{:else if pasteDone}
					<p class="body-medium">{t.pasted}</p>
				{/if}
			</div>
		</div>
	{/if}
</fieldset>

<style>
	.haptic {
		gap: 0.75rem;
	}

	.modes {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1.25rem;
	}

	.radio {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-height: 2.5rem;
	}

	.radio input {
		width: 1.25rem;
		height: 1.25rem;
		margin: 0;
		accent-color: var(--md-sys-color-primary);
	}

	.timeline {
		width: 100%;
		height: 5rem;
		border-radius: 1rem;
		background: var(--md-sys-color-surface-container);
	}

	.axis {
		stroke: var(--md-sys-color-outline-variant);
		stroke-width: 2;
	}

	.envelope {
		fill: color-mix(in srgb, var(--md-sys-color-primary) 25%, transparent);
		stroke: var(--md-sys-color-primary);
		stroke-width: 2;
		vector-effect: non-scaling-stroke;
	}

	.preset {
		stroke: var(--md-sys-color-tertiary);
		stroke-width: 4;
		stroke-linecap: round;
		vector-effect: non-scaling-stroke;
	}

	.scale {
		display: flex;
		justify-content: space-between;
		margin-top: -0.5rem;
		font-variant-numeric: tabular-nums;
	}

	.events {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.events li {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container);
	}

	.event-head,
	.preset-fields {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 1rem;
	}

	.event-head .label-large {
		flex: 1 1 8rem;
	}

	.inline {
		display: flex;
		align-items: center;
		gap: 0.375rem;
	}

	.inline input[type='number'] {
		width: 5.5rem;
	}

	.points {
		width: 100%;
		border-collapse: collapse;
	}

	.points th {
		text-align: start;
		font-weight: 400;
		white-space: nowrap;
	}

	.points td:last-child {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}

	.points td,
	.points th {
		padding: 0.125rem 0.25rem;
	}

	.points input[type='range'] {
		width: 100%;
		min-width: 4rem;
		accent-color: var(--md-sys-color-primary);
	}

	.points input[type='number'] {
		width: 5rem;
	}

	input[type='range'] {
		accent-color: var(--md-sys-color-primary);
	}

	.icon-button.small {
		width: 2.5rem;
		height: 2.5rem;
	}

	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.check p,
	.problem,
	.ok {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.problem {
		color: var(--md-sys-color-error);
	}

	.ok :global(.icon) {
		color: var(--md-sys-color-primary);
	}

	.ai {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		padding-top: 0.5rem;
		border-top: 1px solid var(--md-sys-color-outline-variant);
	}

	.ai-copy {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 0.375rem;
	}

	.prompt {
		width: 100%;
		font: 400 0.75rem/1.125rem var(--cucuto-mono, monospace);
	}
</style>
