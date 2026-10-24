<!--
	The pack creator (#48, #191): pack details and cover, characters with clips and vibrations, then a check with
	shared/pack-spec and a download. The layout, file names, clip processing, haptic text and checks are the same Kotlin
	as the app's creator (#47), compiled to JavaScript (ADR 0038), so both make the same packs. Drafts are saved in
	IndexedDB after every change, and a .soundpack.zip opens as a draft with the app's "Duplicate and edit" rules
	(ADR 0058). Nothing is uploaded.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackReport from '#lib/components/PackReport.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import { canEncodeOpus } from '#lib/creator/encode.js';
	import {
		canSaveDrafts,
		deleteDraft,
		hasContent,
		listDrafts,
		loadDraft,
		saveDraft,
		takePendingFile,
		type DraftSummary
	} from '#lib/creator/drafts.js';
	import { openPackArchive } from '#lib/creator/open.js';
	import {
		buildPack,
		downloadFile,
		emptyCharacter,
		emptyPack,
		FIRST_VERSION,
		uid,
		type BuildOutcome,
		type PackState
	} from '#lib/creator/pack.js';
	import {
		loadPackSpec,
		packSpecAvailable,
		type PackSpec,
		type PackSpecResult,
		type PackSummary
	} from '#lib/pack-spec/index.js';
	import CharacterCard from './CharacterCard.svelte';
	import CoverPicker from './CoverPicker.svelte';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).create);

	let spec = $state.raw<PackSpec | null>(null);
	let loadError = $state('');
	let opus = $state(true);

	let draftId = $state(uid('draft'));
	let pack = $state<PackState>(emptyPack('', ''));
	let licenseChoice = $state('');
	let customLicense = $state('');
	let tagsText = $state('');

	let outcome = $state.raw<BuildOutcome | null>(null);
	let builtFrom = $state('');
	let building = $state(false);
	let buildError = $state('');

	// Drafts (IndexedDB).
	const draftsAvailable = typeof window !== 'undefined' && canSaveDrafts();
	let drafts = $state.raw<DraftSummary[]>([]);
	let saveState = $state<'idle' | 'saving' | 'saved' | 'failed'>('idle');
	let saveError = $state('');
	let draftError = $state('');
	let confirming = $state<string | null>(null);

	// Opening a pack file.
	interface Opening {
		name: string;
		bytes: Uint8Array;
		status: 'busy' | 'invalid' | 'bundle' | 'failed';
		result?: PackSpecResult;
		packs?: PackSummary[];
		error?: string;
	}
	let opening = $state.raw<Opening | null>(null);
	let kept = $state.raw<{ ringtones: number; animations: number; undecodable: number } | null>(
		null
	);

	onMount(() => {
		const loading = loadPackSpec();
		if (!loading) return;
		loading
			.then(async (loaded) => {
				spec = loaded;
				opus = await canEncodeOpus(loaded.authoring.targets);
				await start(loaded);
			})
			.catch((error: unknown) => {
				loadError = String(error);
			});
	});

	/** Opens what the address asks for (`#draft=<id>`, `#open=<key>` from /open/), else a new pack. */
	async function start(loaded: PackSpec) {
		const hash = new URLSearchParams(location.hash.slice(1));
		newPack(loaded);
		if (!draftsAvailable) return;
		await refreshDrafts();
		const draft = hash.get('draft');
		const pending = hash.get('open');
		if (draft) await resume(draft);
		else if (pending) {
			const file = await takePendingFile(pending).catch(() => null);
			if (file) await openBytes(file.name, file.bytes);
		}
	}

	async function refreshDrafts() {
		try {
			drafts = await listDrafts();
		} catch {
			drafts = [];
		}
	}

	function setPack(next: PackState, id: string) {
		pack = next;
		draftId = id;
		outcome = null;
		buildError = '';
		confirming = null;
		const presets = spec
			? [spec.authoring.personalUseLicense, ...spec.authoring.licensePresets]
			: [];
		if (presets.includes(next.license)) {
			licenseChoice = next.license;
			customLicense = '';
		} else {
			licenseChoice = 'other';
			customLicense = next.license;
		}
		tagsText = next.tags.join(', ');
		history.replaceState(history.state, '', `#draft=${id}`);
	}

	function newPack(loaded = spec) {
		if (!loaded) return;
		const fresh = emptyPack(loaded.authoring.randomIdSuffix(), loaded.authoring.personalUseLicense);
		fresh.characters.push(emptyCharacter());
		setPack(fresh, uid('draft'));
		kept = null;
		opening = null;
	}

	async function resume(id: string) {
		draftError = '';
		try {
			const loaded = await loadDraft(id);
			if (!loaded) return;
			setPack(loaded, id);
			kept = null;
			opening = null;
		} catch (error) {
			draftError = fill(t.drafts.loadFailed, { error: String(error) });
		}
	}

	async function removeDraft(id: string) {
		confirming = null;
		try {
			await deleteDraft(id);
		} catch (error) {
			draftError = String(error);
		}
		if (id === draftId) newPack();
		await refreshDrafts();
	}

	// Autosave: a cheap fingerprint of everything that goes into the pack; saved a moment after it stops changing.
	const fingerprint = $derived(
		JSON.stringify({
			i: pack.id,
			v: pack.version,
			n: pack.name,
			a: pack.author,
			l: pack.license,
			d: pack.description,
			hp: pack.homepage,
			tg: pack.tags,
			o: pack.cover?.bytes.length,
			c: pack.characters.map((c) => ({
				n: c.name,
				s: c.sprite?.bytes.length,
				p: c.sprite?.preview.length,
				k: c.seedColor,
				h: c.haptic,
				m: c.spriteAnimated?.id,
				de: c.description,
				x: c.clips.map((clip) => [
					clip.uid,
					clip.label,
					clip.weight,
					clip.encoded.bytes.length,
					clip.when,
					clip.oneIn
				]),
				r: c.ringtones.map((r) => [r.file.id, r.file.bytes.length, r.entry])
			}))
		})
	);

	let saveTimer: ReturnType<typeof setTimeout> | undefined;
	$effect(() => {
		void fingerprint;
		const id = draftId;
		if (!draftsAvailable || !spec || !hasContent(pack)) return;
		clearTimeout(saveTimer);
		saveTimer = setTimeout(() => void save(id), 600);
		return () => clearTimeout(saveTimer);
	});

	async function save(id: string) {
		if (id !== draftId) return;
		saveState = 'saving';
		try {
			await saveDraft(id, $state.snapshot(pack) as PackState);
			saveState = 'saved';
			saveError = '';
			await refreshDrafts();
		} catch (error) {
			saveState = 'failed';
			saveError = String(error);
		}
	}

	$effect(() => {
		pack.license = licenseChoice === 'other' ? customLicense : licenseChoice;
	});

	const packId = $derived(
		pack.id ?? (spec ? spec.authoring.suggestPackId(pack.author, pack.name, pack.idSuffix) : '')
	);
	const stale = $derived(outcome !== null && builtFrom !== fingerprint);
	const homepageInvalid = $derived(
		pack.homepage !== null && spec !== null && !spec.authoring.isHomepage(pack.homepage)
	);

	// Opening pack files.
	async function pickPackFile(event: Event & { currentTarget: HTMLInputElement }) {
		const file = event.currentTarget.files?.[0];
		event.currentTarget.value = '';
		if (!file) return;
		await openBytes(file.name, new Uint8Array(await file.arrayBuffer()));
	}

	async function openBytes(name: string, bytes: Uint8Array, root?: string) {
		if (!spec) return;
		opening = { name, bytes, status: 'busy' };
		try {
			await new Promise((resolve) => setTimeout(resolve, 0));
			const result = await openPackArchive(spec, bytes, root);
			if (result.kind === 'invalid') {
				opening = { name, bytes, status: 'invalid', result: result.result };
			} else if (result.kind === 'bundle') {
				opening = { name, bytes, status: 'bundle', packs: result.packs };
			} else {
				opening = null;
				setPack(result.pack, uid('draft'));
				kept = {
					ringtones: result.keptRingtones,
					animations: result.keptAnimations,
					undecodable: result.undecodable
				};
			}
		} catch (error) {
			opening = { name, bytes, status: 'failed', error: String(error) };
		}
	}

	const saveAsCopy = $derived(pack.origin !== null && pack.id === null);

	function chooseCopy(copy: boolean) {
		if (!spec || !pack.origin) return;
		pack.id = copy ? null : pack.origin.id;
		pack.version = copy
			? FIRST_VERSION
			: spec.authoring.versionAfter(pack.origin.version, pack.origin.version);
	}

	function addCharacter() {
		pack.characters.push(emptyCharacter());
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
		if (file) downloadFile(file);
	}

	async function share() {
		if (!file) return;
		try {
			await navigator.share({ files: [file], title: pack.name });
		} catch {
			// Cancelled by the person, or the target refused the file: nothing to do.
		}
	}

	const dateFormat = $derived(
		new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })
	);
	const otherDrafts = $derived(drafts.filter((d) => d.id !== draftId));
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

		<section class="step drafts" aria-labelledby="drafts">
			<h2 id="drafts" class="headline-small">{t.drafts.title}</h2>
			{#if draftsAvailable}
				<p class="on-surface-variant">{t.drafts.intro}</p>
			{:else}
				<p class="notice" role="note"><Icon name="info" />{t.drafts.unavailable}</p>
			{/if}
			<div class="draft-actions">
				<button type="button" class="button tonal small" onclick={() => newPack()}>
					<Icon name="note-add" />
					{t.drafts.new}
				</button>
				<label class="button tonal small file">
					<Icon name="folder-open" />
					{t.drafts.open}
					<input
						class="visually-hidden"
						type="file"
						accept=".zip,application/zip"
						aria-describedby="open-hint"
						onchange={pickPackFile}
					/>
				</label>
				<span id="open-hint" class="body-small on-surface-variant">{t.drafts.openHint}</span>
			</div>
			{#if otherDrafts.length > 0}
				<ul class="draft-list">
					{#each otherDrafts as draft (draft.id)}
						{@const name = draft.name.trim() || t.drafts.untitled}
						<li>
							{#if draft.thumbnail}
								<img src={draft.thumbnail} alt="" width="48" height="48" />
							{:else}
								<span class="thumb"><Icon name="library-music" /></span>
							{/if}
							<div class="draft-text">
								<span class="title-small">{name}</span>
								<span class="body-small on-surface-variant">
									{fill(t.drafts.summary, {
										characters: draft.characters,
										clips: draft.clips,
										date: dateFormat.format(draft.updatedAt)
									})}
								</span>
							</div>
							{#if confirming === draft.id}
								<div
									class="confirm"
									role="group"
									aria-label={fill(t.drafts.confirmDelete, { name })}
								>
									<span class="body-medium">{fill(t.drafts.confirmDelete, { name })}</span>
									<button
										type="button"
										class="button small danger"
										onclick={() => removeDraft(draft.id)}
									>
										{t.drafts.confirm}
									</button>
									<button
										type="button"
										class="button outlined small"
										onclick={() => (confirming = null)}
									>
										{t.drafts.cancel}
									</button>
								</div>
							{:else}
								<div class="draft-buttons">
									<button
										type="button"
										class="button tonal small"
										aria-label="{t.drafts.resume}: {name}"
										onclick={() => resume(draft.id)}
									>
										{t.drafts.resume}
									</button>
									<button
										type="button"
										class="icon-button"
										aria-label="{t.drafts.delete}: {name}"
										onclick={() => (confirming = draft.id)}
									>
										<Icon name="delete" />
									</button>
								</div>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
			<div aria-live="polite" class="save-state body-small on-surface-variant">
				{#if draftError}
					<p class="error-text">{draftError}</p>
				{:else if saveState === 'saving'}
					<p>{t.drafts.saving}</p>
				{:else if saveState === 'saved'}
					<p class="saved"><Icon name="check-circle" />{t.drafts.saved}</p>
				{:else if saveState === 'failed'}
					<p class="error-text">{fill(t.drafts.saveFailed, { error: saveError })}</p>
				{/if}
			</div>
		</section>

		{#if opening}
			<div class="opening" aria-live="polite">
				{#if opening.status === 'busy'}
					<p class="notice" role="status">{fill(t.opening.busy, { name: opening.name })}</p>
				{:else if opening.status === 'invalid' && opening.result}
					<p class="notice error">{fill(t.opening.invalid, { name: opening.name })}</p>
					<PackReport name={opening.name} size={opening.bytes.length} result={opening.result} />
				{:else if opening.status === 'bundle'}
					{@const current = opening}
					<p class="body-large">{fill(t.opening.bundle, { name: current.name })}</p>
					<div class="draft-actions">
						{#each current.packs ?? [] as option (option.root)}
							<button
								type="button"
								class="button tonal small"
								onclick={() => openBytes(current.name, current.bytes, option.root)}
							>
								{option.name}
								{option.version}
							</button>
						{/each}
					</div>
				{:else if opening.status === 'failed'}
					<p class="notice error">
						{fill(t.opening.failed, { name: opening.name, error: opening.error ?? '' })}
					</p>
				{/if}
			</div>
		{/if}

		{#if pack.origin}
			<section class="card origin" aria-labelledby="origin">
				<h2 id="origin" class="title-large">
					{fill(t.opening.editing, { name: pack.origin.name, version: pack.origin.version })}
				</h2>
				{#if !saveAsCopy}
					<p class="body-medium">{fill(t.opening.update, { version: pack.version })}</p>
				{/if}
				<label class="check-row">
					<input
						type="checkbox"
						checked={saveAsCopy}
						onchange={(e) => chooseCopy(e.currentTarget.checked)}
					/>
					<span>{t.opening.copy}</span>
				</label>
				{#if kept && kept.animations > 0}
					<p class="body-small on-surface-variant">
						{fill(t.opening.kept, { animations: kept.animations })}
					</p>
				{/if}
				{#if kept && kept.undecodable > 0}
					<p class="body-small on-surface-variant">
						{fill(t.opening.undecodable, { n: kept.undecodable })}
					</p>
				{/if}
			</section>
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
				<CoverPicker bind:cover={pack.cover} size={authoring.targets.spriteSize} name={pack.name} />
				{#if pack.origin}
					<label class="field version">
						<span class="label-large">{t.details.version}</span>
						<input
							type="text"
							maxlength="64"
							bind:value={pack.version}
							autocomplete="off"
							aria-describedby="version-hint"
						/>
						<span id="version-hint" class="body-small on-surface-variant"
							>{t.details.versionHint}</span
						>
					</label>
				{/if}
				<details class="more" open={pack.homepage !== null || pack.tags.length > 0}>
					<summary class="label-large">{t.more.title}</summary>
					<div class="more-fields">
						<label class="field">
							<span class="label-large">{t.more.homepage}</span>
							<input
								type="url"
								maxlength="2000"
								placeholder="https://"
								autocomplete="url"
								value={pack.homepage ?? ''}
								aria-describedby="homepage-hint"
								aria-invalid={homepageInvalid}
								oninput={(e) => (pack.homepage = e.currentTarget.value.trim() || null)}
							/>
							<span id="homepage-hint" class="body-small on-surface-variant">
								{homepageInvalid ? t.more.homepageInvalid : t.more.homepageHint}
							</span>
						</label>
						<label class="field">
							<span class="label-large">{t.more.tags}</span>
							<input
								type="text"
								maxlength="800"
								autocomplete="off"
								bind:value={tagsText}
								aria-describedby="tags-hint"
								oninput={() => (pack.tags = authoring.parseTags(tagsText))}
							/>
							<span id="tags-hint" class="body-small on-surface-variant">
								{pack.tags.length > 0
									? fill(t.more.tagsResult, { tags: pack.tags.join(', ') })
									: t.more.tagsHint}
							</span>
						</label>
					</div>
				</details>
				<p class="body-small on-surface-variant pack-id">
					{t.details.packId}: <code>{packId}</code>
					<br />{pack.id ? t.details.packIdKept : t.details.packIdHint}
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

	.more summary {
		cursor: pointer;
		min-height: 2.5rem;
		display: flex;
		align-items: center;
	}

	.more-fields {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
		margin-top: 0.75rem;
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

	.draft-actions {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 0.75rem;
	}

	.draft-list {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.draft-list li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container);
	}

	.draft-list img,
	.thumb {
		width: 3rem;
		height: 3rem;
		border-radius: 1rem;
		object-fit: cover;
		flex: none;
	}

	.thumb {
		display: grid;
		place-items: center;
		background: var(--md-sys-color-surface-container-highest);
		color: var(--md-sys-color-on-surface-variant);
	}

	.draft-text {
		display: flex;
		flex: 1 1 12rem;
		flex-direction: column;
		min-width: 0;
		overflow-wrap: anywhere;
	}

	.draft-buttons,
	.confirm {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem;
	}

	.button.danger {
		--button-bg: var(--md-sys-color-error);
		--button-fg: var(--md-sys-color-on-error);
	}

	.save-state p {
		display: flex;
		gap: 0.375rem;
		align-items: center;
	}

	.save-state :global(.icon) {
		width: 1rem;
		height: 1rem;
		color: var(--md-sys-color-primary);
	}

	.error-text {
		color: var(--md-sys-color-error);
	}

	.opening {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.origin {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		background: var(--md-sys-color-secondary-container);
		color: var(--md-sys-color-on-secondary-container);
	}

	.version input {
		max-width: 12rem;
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
