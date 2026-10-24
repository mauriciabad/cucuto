<!--
	The bundle tool (#191): finished packs in, one bundle out, laid out by shared/pack-spec like `packer bundle` and
	checked with the same validator before download. Nothing is uploaded.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Icon from '#lib/components/Icon.svelte';
	import PackReport from '#lib/components/PackReport.svelte';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		buildBundle,
		readPack,
		type BundledFile,
		type BundleOutcome
	} from '#lib/creator/bundle.js';
	import type { EncodedImage } from '#lib/creator/image.js';
	import { downloadFile, FIRST_VERSION, uid } from '#lib/creator/pack.js';
	import { loadPackSpec, packSpecAvailable, type PackSpec } from '#lib/pack-spec/index.js';
	import CoverPicker from './CoverPicker.svelte';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).bundle);
	const tc = $derived(messages(locale).create);

	let spec = $state.raw<PackSpec | null>(null);
	let loadError = $state('');

	let packs = $state.raw<BundledFile[]>([]);
	let problems = $state.raw<string[]>([]);
	let name = $state('');
	let author = $state('');
	let version = $state(FIRST_VERSION);
	let license = $state('');
	let description = $state('');
	let homepage = $state('');
	let tagsText = $state('');
	let cover = $state.raw<EncodedImage | null>(null);
	let customId = $state<string | null>(null);
	let suffix = $state('');

	let outcome = $state.raw<BundleOutcome | null>(null);
	let builtFrom = $state('');
	let building = $state(false);
	let buildError = $state('');

	onMount(() => {
		const loading = loadPackSpec();
		if (!loading) return;
		loading
			.then((loaded) => {
				suffix = loaded.authoring.randomIdSuffix();
				spec = loaded;
			})
			.catch((error: unknown) => (loadError = String(error)));
	});

	const suggestedId = $derived(spec ? spec.authoring.suggestPackId(author, name, suffix) : '');
	const id = $derived(customId ?? suggestedId);
	const tags = $derived(spec ? spec.authoring.parseTags(tagsText) : []);
	const fingerprint = $derived(
		JSON.stringify([
			id,
			name,
			version,
			author,
			license,
			description,
			homepage,
			tags,
			cover?.bytes.length,
			packs.map((p) => p.uid)
		])
	);
	const stale = $derived(outcome !== null && builtFrom !== fingerprint);
	const homepageInvalid = $derived(
		homepage.trim() !== '' && spec !== null && !spec.authoring.isHomepage(homepage)
	);

	async function addFiles(event: Event & { currentTarget: HTMLInputElement }) {
		const files = [...(event.currentTarget.files ?? [])];
		event.currentTarget.value = '';
		if (!spec) return;
		const found: string[] = [];
		const added: BundledFile[] = [];
		for (const file of files) {
			try {
				const bytes = new Uint8Array(await file.arrayBuffer());
				const read = readPack(spec, bytes);
				if (read.kind === 'pack') {
					added.push({ uid: uid('pack'), fileName: file.name, bytes, summary: read.summary });
				} else if (read.kind === 'bundle') {
					found.push(fill(t.packs.isBundle, { name: file.name }));
				} else {
					found.push(fill(t.packs.invalid, { name: file.name }));
				}
			} catch (error) {
				found.push(fill(t.packs.failed, { name: file.name, error: String(error) }));
			}
		}
		packs = [...packs, ...added];
		problems = found;
	}

	function remove(item: BundledFile) {
		packs = packs.filter((p) => p.uid !== item.uid);
	}

	async function check() {
		if (!spec) return;
		building = true;
		buildError = '';
		try {
			await new Promise((resolve) => setTimeout(resolve, 0));
			outcome = buildBundle(
				spec,
				{ id, name, version, author, license, description, homepage, tags },
				packs,
				cover
			);
			builtFrom = fingerprint;
		} catch (error) {
			outcome = null;
			buildError = fill(t.build.failed, { error: String(error) });
		} finally {
			building = false;
		}
	}

	function download() {
		if (outcome?.kind !== 'built') return;
		downloadFile(
			new File([outcome.bytes as BlobPart], outcome.fileName, { type: 'application/zip' })
		);
	}
</script>

{#if !packSpecAvailable}
	<p class="notice">{tc.unavailable}</p>
{:else if loadError}
	<p class="notice error" role="alert">{fill(tc.loadFailed, { error: loadError })}</p>
{:else if !spec}
	<p class="notice" role="status">{tc.loading}</p>
{:else}
	<div class="tool">
		<section class="step" aria-labelledby="packs">
			<h2 id="packs" class="headline-small">{t.packs.title}</h2>
			{#if packs.length === 0}
				<p class="body-medium">{t.packs.empty}</p>
			{:else}
				<ul class="files">
					{#each packs as item (item.uid)}
						<li>
							<div class="file-text">
								<span class="title-small">{item.summary.name}</span>
								<span class="body-small on-surface-variant">
									{fill(t.packs.item, {
										id: item.summary.id,
										version: item.summary.version,
										characters: item.summary.characters
									})}
								</span>
							</div>
							<button
								type="button"
								class="icon-button"
								aria-label={fill(t.packs.remove, { name: item.summary.name })}
								onclick={() => remove(item)}
							>
								<Icon name="delete" />
							</button>
						</li>
					{/each}
				</ul>
			{/if}
			<div class="row">
				<label class="button tonal small file">
					<Icon name="library-add" />
					{t.packs.add}
					<input
						class="visually-hidden"
						type="file"
						accept=".zip,application/zip"
						multiple
						aria-describedby="packs-hint"
						onchange={addFiles}
					/>
				</label>
				<span id="packs-hint" class="body-small on-surface-variant">{t.packs.hint}</span>
			</div>
			<div aria-live="polite">
				{#each problems as problem, i (i)}
					<p class="problem body-medium"><Icon name="error" />{problem}</p>
				{/each}
			</div>
		</section>

		<section class="step" aria-labelledby="details">
			<h2 id="details" class="headline-small">{t.details.title}</h2>
			<div class="card form">
				<label class="field">
					<span class="label-large">{t.details.name}</span>
					<input type="text" maxlength="80" bind:value={name} autocomplete="off" />
				</label>
				<label class="field">
					<span class="label-large">{t.details.author}</span>
					<input type="text" maxlength="120" bind:value={author} autocomplete="nickname" />
				</label>
				<label class="field">
					<span class="label-large">{t.details.id}</span>
					<input
						type="text"
						maxlength="128"
						value={id}
						oninput={(e) => (customId = e.currentTarget.value)}
						autocomplete="off"
						spellcheck="false"
						aria-describedby="id-hint"
					/>
					<span id="id-hint" class="body-small on-surface-variant">{t.details.idHint}</span>
				</label>
				<label class="field short">
					<span class="label-large">{t.details.version}</span>
					<input
						type="text"
						maxlength="64"
						bind:value={version}
						autocomplete="off"
						aria-describedby="version-hint"
					/>
					<span id="version-hint" class="body-small on-surface-variant"
						>{t.details.versionHint}</span
					>
				</label>
				<label class="field">
					<span class="label-large">{t.details.license}</span>
					<input
						type="text"
						bind:value={license}
						autocomplete="off"
						aria-describedby="license-hint"
					/>
					<span id="license-hint" class="body-small on-surface-variant"
						>{t.details.licenseHint}</span
					>
				</label>
				<label class="field">
					<span class="label-large">{t.details.description}</span>
					<textarea rows="3" maxlength="2000" bind:value={description}></textarea>
				</label>
				<label class="field">
					<span class="label-large">{t.details.homepage}</span>
					<input
						type="url"
						maxlength="2000"
						placeholder="https://"
						autocomplete="url"
						bind:value={homepage}
						aria-invalid={homepageInvalid}
						aria-describedby="homepage-hint"
					/>
					<span id="homepage-hint" class="body-small on-surface-variant"
						>{homepageInvalid ? t.details.homepageInvalid : t.details.homepageHint}</span
					>
				</label>
				<label class="field">
					<span class="label-large">{t.details.tags}</span>
					<input
						type="text"
						maxlength="800"
						autocomplete="off"
						bind:value={tagsText}
						aria-describedby="tags-hint"
					/>
					<span id="tags-hint" class="body-small on-surface-variant"
						>{tags.length > 0
							? fill(t.details.tagsResult, { tags: tags.join(', ') })
							: t.details.tagsHint}</span
					>
				</label>
				<CoverPicker bind:cover size={spec.authoring.targets.spriteSize} {name} />
			</div>
		</section>

		<section class="step" aria-labelledby="build">
			<h2 id="build" class="headline-small">{t.build.title}</h2>
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
							{#each outcome.problems as p (p)}
								<li>{t.build.problems[p] ?? p}</li>
							{/each}
						</ul>
					</div>
				{:else if outcome?.kind === 'built'}
					{#if stale}
						<p class="notice"><Icon name="warning" />{t.build.stale}</p>
					{/if}
					<PackReport name={outcome.fileName} size={outcome.bytes.length} result={outcome.result} />
					{#if outcome.result.valid}
						<button type="button" class="button download" disabled={stale} onclick={download}>
							<Icon name="download" />
							{t.build.download}
						</button>
					{/if}
				{/if}
			</div>
		</section>
	</div>
{/if}

<style>
	.tool {
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

	.short input {
		max-width: 12rem;
	}

	.files {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.files li {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border-radius: 1.25rem;
		background: var(--md-sys-color-surface-container);
	}

	.file-text {
		display: flex;
		flex: 1;
		flex-direction: column;
		min-width: 0;
		overflow-wrap: anywhere;
	}

	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 0.75rem;
	}

	.check,
	.download {
		align-self: flex-start;
	}

	.problem {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		color: var(--md-sys-color-error);
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
</style>
