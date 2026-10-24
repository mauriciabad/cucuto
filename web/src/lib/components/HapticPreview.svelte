<!--
	A repository character's vibration, drawn (#301, ADR 0069): browsers can't play rich haptics, so the Packs page
	shows the `x-haptic` from the index instead. A custom pattern is drawn like the creator's timeline (presets as
	spikes as tall as their strength, envelopes as their intensity curve); `auto` has no stored pattern, so it says
	"Follows the voice"; `none` says "No vibration"; no `x-haptic` shows nothing.
-->
<script lang="ts">
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { hapticPreview } from '#lib/creator/haptics.js';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import type { HapticJson } from '#lib/pack-spec/core.js';

	interface Props {
		haptic?: HapticJson;
	}

	let { haptic }: Props = $props();

	const WIDTH = 96;
	const HEIGHT = 24;
	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).packs);
	const preview = $derived(hapticPreview(haptic, WIDTH, HEIGHT));
	const seconds = (ms: number) =>
		(ms / 1000).toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
</script>

{#if preview?.mode === 'custom'}
	<svg
		class="haptic-preview"
		viewBox="0 0 {WIDTH} {HEIGHT}"
		role="img"
		aria-label={fill(t.hapticCustom, { seconds: seconds(preview.durationMs) })}
	>
		<line x1="0" y1={HEIGHT - 4} x2={WIDTH} y2={HEIGHT - 4} class="axis" />
		{#each preview.envelopes as points, i (i)}
			<polyline {points} class="envelope" />
		{/each}
		{#each preview.presets as points, i (i)}
			<polyline {points} class="preset" />
		{/each}
	</svg>
{:else if preview?.mode === 'auto'}
	<span
		class="haptic-label body-small on-surface-variant"
		role="img"
		aria-label={t.hapticAutoSpoken}
	>
		<Icon name="graphic-eq" />{t.hapticAuto}
	</span>
{:else if preview?.mode === 'none'}
	<span class="haptic-label body-small on-surface-variant">{t.hapticNone}</span>
{/if}

<style>
	.haptic-preview {
		display: block;
		width: 6rem;
		height: 1.5rem;
	}

	.axis {
		stroke: var(--md-sys-color-outline-variant);
		stroke-width: 1;
	}

	.envelope {
		fill: color-mix(in srgb, var(--md-sys-color-primary) 25%, transparent);
		stroke: var(--md-sys-color-primary);
		stroke-width: 1.5;
		stroke-linejoin: round;
	}

	.preset {
		stroke: var(--md-sys-color-tertiary);
		stroke-width: 3;
		stroke-linecap: round;
	}

	.haptic-label {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
	}

	.haptic-label :global(.icon) {
		width: 1rem;
		height: 1rem;
		color: var(--md-sys-color-primary);
	}
</style>
