<!--
	The fields of a condition (docs/pack-format.md#ringtones-and-variants and #special-clips), on shared/pack-spec's
	ConditionForm: for clips the moment and rarity, then the time of day, seasons, weekdays and dates. Used by the clip
	condition dialog and the ringtone editor (with `clip` false: ringtones have no moments and no rarity).
-->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import type { ConditionFormJson } from '#lib/pack-spec/index.js';

	interface Props {
		form: ConditionFormJson;
		clip: boolean;
		/** Unique within the page, for element IDs. */
		prefix: string;
	}

	let { form = $bindable(), clip, prefix }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create.conditions);
	const seasonNames = $derived(messages(locale).create.summary.seasons);
	const id = (part: string) => `${prefix}-${part}`;

	const SEASONS = ['spring', 'summer', 'autumn', 'winter'];
	const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
	const weekdayName = (day: string, style: 'short' | 'long') =>
		new Intl.DateTimeFormat(locale, { weekday: style }).format(
			new Date(2024, 0, 1 + WEEKDAYS.indexOf(day))
		);
	const DEFAULT_ONE_IN = 25;

	/** A typed number as the form's whole number; an emptied field is 0, which the form reports. */
	const whole = (text: string) => Math.round(Number(text) || 0);

	function toggle(list: string[], value: string, order: string[]): string[] {
		const next = list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
		return order.filter((v) => next.includes(v));
	}
</script>

<div class="conditions">
	{#if clip}
		<fieldset class="field">
			<legend class="label-large">{t.moment}</legend>
			<label class="radio">
				<input type="radio" name={id('moment')} value="ANY" bind:group={form.moment} />
				<span>{t.momentAny}</span>
			</label>
			<label class="radio">
				<input type="radio" name={id('moment')} value="FIRST_OF_DAY" bind:group={form.moment} />
				<span>{t.momentFirst}</span>
			</label>
			<label class="radio">
				<input type="radio" name={id('moment')} value="BURST" bind:group={form.moment} />
				<span>{t.momentBurst}</span>
			</label>
			{#if form.moment === 'BURST'}
				<label class="inline indent">
					<span class="body-medium">{t.burst}</span>
					<input
						type="number"
						min="2"
						max="50"
						step="1"
						value={form.burst}
						oninput={(e) => (form.burst = whole(e.currentTarget.value))}
					/>
				</label>
			{/if}
		</fieldset>

		<div class="field">
			<label class="check-row">
				<input
					type="checkbox"
					checked={form.oneIn !== null}
					onchange={(e) => (form.oneIn = e.currentTarget.checked ? DEFAULT_ONE_IN : null)}
				/>
				<span>{t.rare}</span>
			</label>
			{#if form.oneIn !== null}
				<label class="inline indent">
					<span class="body-medium">{t.oneIn}</span>
					<input
						type="number"
						min="2"
						max="10000"
						step="1"
						value={form.oneIn}
						oninput={(e) => (form.oneIn = whole(e.currentTarget.value))}
					/>
				</label>
			{/if}
		</div>
	{/if}

	<fieldset class="field">
		<legend class="label-large">{t.time}</legend>
		<div class="options">
			{#each [['ANY', t.timeAny], ['DAY', t.timeDay], ['NIGHT', t.timeNight], ['HOURS', t.timeHours]] as [value, label] (value)}
				<label class="radio">
					<input type="radio" name={id('time')} {value} bind:group={form.time} />
					<span>{label}</span>
				</label>
			{/each}
		</div>
		{#if form.time === 'DAY' || form.time === 'NIGHT'}
			<p class="body-small on-surface-variant">{t.dayHint}</p>
		{:else if form.time === 'HOURS'}
			<div class="hours">
				<label class="inline">
					<span class="body-medium">{t.from}</span>
					<input type="time" bind:value={form.from} />
				</label>
				<label class="inline">
					<span class="body-medium">{t.to}</span>
					<input type="time" bind:value={form.to} />
				</label>
			</div>
			<p class="body-small on-surface-variant">{t.hoursHint}</p>
		{/if}
	</fieldset>

	<fieldset class="field">
		<legend class="label-large">{t.seasons}</legend>
		<div class="chips">
			{#each SEASONS as season (season)}
				<button
					type="button"
					class="chip"
					aria-pressed={form.seasons.includes(season)}
					onclick={() => (form.seasons = toggle(form.seasons, season, SEASONS))}
				>
					{#if form.seasons.includes(season)}<Icon name="check-circle" />{/if}
					{seasonNames[season]}
				</button>
			{/each}
		</div>
		<p class="body-small on-surface-variant">{t.seasonsHint}</p>
	</fieldset>

	<fieldset class="field">
		<legend class="label-large">{t.weekdays}</legend>
		<div class="chips">
			{#each WEEKDAYS as day (day)}
				<button
					type="button"
					class="chip"
					aria-pressed={form.weekdays.includes(day)}
					onclick={() => (form.weekdays = toggle(form.weekdays, day, WEEKDAYS))}
				>
					{#if form.weekdays.includes(day)}<Icon name="check-circle" />{/if}
					<span aria-hidden="true">{weekdayName(day, 'short')}</span>
					<span class="visually-hidden">{weekdayName(day, 'long')}</span>
				</button>
			{/each}
		</div>
		<p class="body-small on-surface-variant">{t.weekdaysHint}</p>
	</fieldset>

	<fieldset class="field">
		<legend class="label-large">{t.dates}</legend>
		<p class="body-small on-surface-variant">{t.datesHint}</p>
		{#if form.dates.length > 0}
			<ol class="dates">
				{#each form.dates as date, i (i)}
					{@const label = fill(t.date, { n: i + 1 })}
					<li>
						<label class="inline">
							<span class="body-small">{t.dateFrom}</span>
							<input
								type="text"
								inputmode="numeric"
								maxlength="5"
								placeholder="12-25"
								autocomplete="off"
								aria-label="{label}: {t.dateFrom}"
								bind:value={date.from}
							/>
						</label>
						<label class="inline">
							<span class="body-small">{t.dateTo}</span>
							<input
								type="text"
								inputmode="numeric"
								maxlength="5"
								placeholder="01-06"
								autocomplete="off"
								aria-label="{label}: {t.dateTo}"
								value={date.to ?? ''}
								oninput={(e) => (date.to = e.currentTarget.value.trim() || null)}
							/>
						</label>
						<button
							type="button"
							class="icon-button"
							aria-label={fill(t.removeDate, { n: i + 1 })}
							onclick={() => (form.dates = form.dates.filter((_, j) => j !== i))}
						>
							<Icon name="delete" />
						</button>
					</li>
				{/each}
			</ol>
		{/if}
		{#if form.dates.length < 50}
			<button
				type="button"
				class="button tonal small add-date"
				onclick={() => (form.dates = [...form.dates, { from: '', to: null }])}
			>
				<Icon name="add" />
				{t.addDate}
			</button>
		{/if}
	</fieldset>
</div>

<style>
	.conditions {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.radio,
	.check-row {
		display: flex;
		align-items: center;
		gap: 0.625rem;
		min-height: 2.5rem;
	}

	.radio input,
	.check-row input {
		width: 1.25rem;
		height: 1.25rem;
		margin: 0;
		flex: none;
		accent-color: var(--md-sys-color-primary);
	}

	.options {
		display: flex;
		flex-wrap: wrap;
		gap: 0 1.25rem;
	}

	.inline {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
	}

	.indent {
		margin-inline-start: 1.875rem;
	}

	.inline input[type='number'] {
		width: 6rem;
	}

	.hours {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem 1.25rem;
	}

	input[type='time'] {
		min-height: 2.5rem;
		padding: 0.375rem 0.625rem;
		border: 1px solid var(--md-sys-color-outline);
		border-radius: 0.75rem;
		background: var(--md-sys-color-surface);
		color: var(--md-sys-color-on-surface);
		font: 400 1rem/1.5rem var(--cucuto-font);
	}

	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.chip {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		min-height: 2.5rem;
		padding: 0 1rem;
		border: 1px solid var(--md-sys-color-outline);
		border-radius: 0.75rem;
		background: transparent;
		color: var(--md-sys-color-on-surface-variant);
		font: 500 0.875rem/1.25rem var(--cucuto-font);
		cursor: pointer;
	}

	.chip:hover {
		background: color-mix(in srgb, var(--md-sys-color-on-surface) 8%, transparent);
	}

	.chip[aria-pressed='true'] {
		border-color: transparent;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	.chip :global(.icon) {
		width: 1.125rem;
		height: 1.125rem;
	}

	.dates {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.dates li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 0.75rem;
	}

	.dates input {
		width: 5.5rem;
		min-height: 2.5rem;
		padding: 0.375rem 0.625rem;
	}

	.add-date {
		align-self: flex-start;
	}
</style>
