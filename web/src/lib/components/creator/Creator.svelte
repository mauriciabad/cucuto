<!--
	The pack creator (#48): pack details, characters, clips, then a check with shared/pack-spec and a download. The
	layout, file names, clip processing and checks are the same Kotlin as the app's creator (#47), compiled to
	JavaScript (ADR 0038), so both make the same packs. Nothing is uploaded.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackReport from '#lib/components/PackReport.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { canEncodeOpus } from '#lib/creator/encode.js';
	import { buildPack, uid, type BuildOutcome, type PackState } from '#lib/creator/pack.js';
	import { loadPackSpec, packSpecAvailable, type PackSpec } from '#lib/pack-spec/index.js';
	import CharacterCard from './CharacterCard.svelte';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create);

	let spec = $state.raw<PackSpec | null>(null);
	let loadError = $state('');
	let opus = $state(true);

	let pack = $state<PackState>({
		idSuffix: '',
		name: '',
		author: '',
		license: '',
		description: '',
		characters: []
	});
	let licenseChoice = $state('');
	let customLicense = $state('');

	let outcome = $state.raw<BuildOutcome | null>(null);
	let builtFrom = $state('');
	let building = $state(false);
	let buildError = $state('');

	onMount(() => {
		const loading = loadPackSpec();
		if (!loading) return;
		loading
			.then(async (loaded) => {
				pack.idSuffix = loaded.authoring.randomIdSuffix();
				licenseChoice = loaded.authoring.personalUseLicense;
				spec = loaded;
				opus = await canEncodeOpus(loaded.authoring.targets);
				if (pack.characters.length === 0) addCharacter();
			})
			.catch((error: unknown) => {
				loadError = String(error);
			});
	});

	$effect(() => {
		pack.license = licenseChoice === 'other' ? customLicense : licenseChoice;
	});

	const packId = $derived(
		spec ? spec.authoring.suggestPackId(pack.author, pack.name, pack.idSuffix) : ''
	);

	/** A cheap fingerprint of everything that goes into the file, to tell when a check is out of date. */
	const fingerprint = $derived(
		JSON.stringify({
			n: pack.name,
			a: pack.author,
			l: pack.license,
			d: pack.description,
			c: pack.characters.map((c) => ({
				n: c.name,
				s: c.sprite?.bytes.length,
				p: c.sprite?.preview.length,
				k: c.seedColor,
				x: c.clips.map((clip) => [clip.uid, clip.label, clip.weight, clip.encoded.bytes.length])
			}))
		})
	);
	const stale = $derived(outcome !== null && builtFrom !== fingerprint);

	function addCharacter() {
		pack.characters.push({ uid: uid('char'), name: '', sprite: null, seedColor: null, clips: [] });
	}

	function removeCharacter(index: number) {
		pack.characters.splice(index, 1);
	}

	async function check() {
		if (!spec) return;
		building = true;
		buildError = '';
		try {
			await new Promise((resolve) => setTimeout(resolve, 0));
			outcome = buildPack(spec, pack);
			builtFrom = fingerprint;
		} catch (error) {
			outcome = null;
			buildError = fill(t.build.failed, { error: String(error) });
		} finally {
			building = false;
		}
	}

	function problemText(kind: string, character: number | null): string {
		const c = character === null ? undefined : pack.characters[character];
		const name = c?.name.trim() || fill(t.characters.heading, { n: (character ?? 0) + 1 });
		return fill(t.build.problems[kind] ?? kind, { n: (character ?? 0) + 1, name });
	}

	const built = $derived(outcome?.kind === 'built' ? outcome.pack : null);
	const file = $derived(
		built ? new File([built.bytes as BlobPart], built.fileName, { type: 'application/zip' }) : null
	);
	const canShare = $derived(
		file !== null && typeof navigator !== 'undefined' && !!navigator.canShare?.({ files: [file] })
	);

	function download() {
		if (!file) return;
		const url = URL.createObjectURL(file);
		const a = document.createElement('a');
		a.href = url;
		a.download = file.name;
		document.body.append(a);
		a.click();
		a.remove();
		setTimeout(() => URL.revokeObjectURL(url), 10_000);
	}

	async function share() {
		if (!file) return;
		try {
			await navigator.share({ files: [file], title: pack.name });
		} catch {
			// Cancelled by the person, or the target refused the file: nothing to do.
		}
	}
</script>

{#if !packSpecAvailable}
	<p class="notice">{t.unavailable}</p>
{:else if loadError}
	<p class="notice error" role="alert">{fill(t.loadFailed, { error: loadError })}</p>
{:else if !spec}
	<p class="notice" role="status">{t.loading}</p>
{:else}
	{@const authoring = spec.authoring}
	<div class="creator">
		{#if !opus}
			<p class="notice" role="note"><Icon name="info" />{t.wavNotice}</p>
		{/if}

		<section class="step" aria-labelledby="details">
			<h2 id="details" class="headline-small">{t.details.title}</h2>
			<div class="card form">
				<label class="field">
					<span class="label-large">{t.details.name}</span>
					<input type="text" maxlength="80" required bind:value={pack.name} autocomplete="off" />
				</label>
				<label class="field">
					<span class="label-large">{t.details.author}</span>
					<input
						type="text"
						maxlength="120"
						required
						bind:value={pack.author}
						autocomplete="nickname"
						aria-describedby="author-hint"
					/>
					<span id="author-hint" class="body-small on-surface-variant">{t.details.authorHint}</span>
				</label>
				<fieldset class="field licenses">
					<legend class="label-large">{t.details.license}</legend>
					{#each [authoring.personalUseLicense, ...authoring.licensePresets, 'other'] as choice (choice)}
						<label class="radio">
							<input type="radio" name="license" value={choice} bind:group={licenseChoice} />
							<span>{t.details.licenses[choice] ?? choice}</span>
						</label>
					{/each}
					{#if licenseChoice === 'other'}
						<label class="field">
							<span class="label-large">{t.details.licenseOther}</span>
							<input
								type="text"
								required
								bind:value={customLicense}
								aria-describedby="license-hint"
								autocomplete="off"
							/>
							<span id="license-hint" class="body-small on-surface-variant">
								<Html text={t.details.licenseOtherHintHtml} />
							</span>
						</label>
					{/if}
				</fieldset>
				<label class="field">
					<span class="label-large">{t.details.descriptionLabel}</span>
					<textarea rows="3" maxlength="2000" bind:value={pack.description}></textarea>
				</label>
				<p class="body-small on-surface-variant pack-id">
					{t.details.packId}: <code>{packId}</code>
					<br />{t.details.packIdHint}
				</p>
			</div>
		</section>

		<section class="step" aria-labelledby="characters">
			<h2 id="characters" class="headline-small">{t.characters.title}</h2>
			<p class="on-surface-variant">{t.characters.intro}</p>
			{#if pack.characters.length === 0}
				<p class="body-medium">{t.characters.empty}</p>
			{/if}
			{#each pack.characters as character, i (character.uid)}
				<CharacterCard
					bind:character={pack.characters[i]}
					index={i}
					{authoring}
					onremove={() => removeCharacter(i)}
				/>
			{/each}
			<button type="button" class="button tonal add" onclick={addCharacter}>
				<Icon name="person-add" />
				{t.characters.add}
			</button>
		</section>

		<section class="step" aria-labelledby="haptics">
			<h2 id="haptics" class="headline-small">{t.haptics.title}</h2>
			<p class="with-icon"><Icon name="mobile-vibrate" />{t.haptics.text}</p>
		</section>

		<section class="step" aria-labelledby="build">
			<h2 id="build" class="headline-small">{t.build.title}</h2>
			<p class="on-surface-variant">{t.build.intro}</p>
			<button type="button" class="button check" disabled={building} onclick={check}>
				<Icon name="check-circle" />
				{building ? t.build.checking : t.build.check}
			</button>

			<div class="results" aria-live="polite">
				{#if buildError}
					<p class="notice error">{buildError}</p>
				{:else if outcome?.kind === 'incomplete'}
					<div class="card incomplete">
						<p class="title-medium">{t.build.incomplete}</p>
						<ul>
							{#each outcome.problems as p, i (i)}
								<li>{problemText(p.kind, p.character)}</li>
							{/each}
						</ul>
					</div>
				{:else if built}
					{#if stale}
						<p class="notice"><Icon name="warning" />{t.build.stale}</p>
					{/if}
					<PackReport name={built.fileName} size={built.bytes.length} result={built.result} />
					{#if built.result.valid}
						<div class="downloads">
							<button type="button" class="button" disabled={stale} onclick={download}>
								<Icon name="download" />
								{t.build.download}
							</button>
							{#if canShare}
								<button
									type="button"
									class="button tonal"
									disabled={stale}
									onclick={share}
									aria-describedby="share-hint"
								>
									<Icon name="share" />
									{t.build.share}
								</button>
								<span id="share-hint" class="body-small on-surface-variant"
									>{t.build.shareHint}</span
								>
							{/if}
						</div>
						<p class="body-medium"><Html text={t.build.installHtml} /></p>
					{/if}
				{/if}
			</div>
		</section>
	</div>
{/if}

<style>
	.creator {
		display: flex;
		flex-direction: column;
		gap: 3rem;
	}

	.step {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.form {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.licenses {
		gap: 0.5rem;
	}

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
		accent-color: var(--md-sys-color-primary);
	}

	.pack-id code {
		overflow-wrap: anywhere;
	}

	.add,
	.check {
		align-self: flex-start;
	}

	.with-icon {
		display: flex;
		gap: 0.75rem;
	}

	.with-icon :global(.icon) {
		color: var(--md-sys-color-primary);
	}

	.notice {
		display: flex;
		gap: 0.5rem;
		align-items: flex-start;
		padding: 1rem 1.25rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-tertiary-container);
		color: var(--md-sys-color-on-tertiary-container);
	}

	.notice.error {
		background: var(--md-sys-color-error-container);
		color: var(--md-sys-color-on-error-container);
	}

	.results {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.incomplete ul {
		margin-top: 0.5rem;
		padding-left: 1.25rem;
	}

	.downloads {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.75rem;
	}
</style>
