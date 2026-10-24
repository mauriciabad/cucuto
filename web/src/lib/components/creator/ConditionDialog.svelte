<!--
	"When it plays": a clip's condition and rarity (docs/pack-format.md#special-clips), read and written with
	shared/pack-spec's ConditionForm, the same Kotlin as the app's creator.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import { localeFromPath, messages } from '#lib/i18n/index.js';
	import { problemText } from '#lib/creator/conditions.js';
	import type { Authoring, ConditionFormJson } from '#lib/pack-spec/index.js';
	import ConditionFields from './ConditionFields.svelte';

	interface Props {
		authoring: Authoring;
		/** The clip's label, for the dialog's description. */
		name: string;
		when: Record<string, unknown> | null;
		oneIn: number | null;
		onsave: (result: { when: Record<string, unknown> | null; oneIn: number | null }) => void;
		oncancel: () => void;
	}

	let { authoring, name, when, oneIn, onsave, oncancel }: Props = $props();

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create.conditions);
	const prefix = `condition-${Math.random().toString(36).slice(2, 8)}`;

	// Seeded once from the clip; the dialog then edits its own copy.
	// svelte-ignore state_referenced_locally
	let form = $state<ConditionFormJson>(authoring.conditionForm(when, oneIn ?? null));
	const result = $derived(
		authoring.writeCondition($state.snapshot(form) as ConditionFormJson, true)
	);
	let dialog: HTMLDialogElement;

	onMount(() => dialog.showModal());
</script>

<dialog
	bind:this={dialog}
	class="dialog wide"
	aria-labelledby="{prefix}-title"
	aria-describedby="{prefix}-intro"
	oncancel={(event) => {
		event.preventDefault();
		oncancel();
	}}
>
	<h2 id="{prefix}-title" class="headline-small">{t.clipTitle}: {name}</h2>
	<p id="{prefix}-intro" class="body-medium on-surface-variant">{t.intro}</p>

	<ConditionFields bind:form clip={true} {prefix} />

	<div aria-live="polite">
		{#each result.problems as problem, i (i)}
			<p class="problem body-medium"><Icon name="error" />{problemText(problem, t.problems)}</p>
		{/each}
	</div>

	<div class="actions">
		<button type="button" class="button outlined" onclick={oncancel}>{t.cancel}</button>
		<button
			type="button"
			class="button"
			disabled={result.problems.length > 0}
			onclick={() => onsave({ when: result.when, oneIn: result.oneIn })}
		>
			<Icon name="schedule" />
			{t.save}
		</button>
	</div>
</dialog>

<style>
	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
	}
</style>
