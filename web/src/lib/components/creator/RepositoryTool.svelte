<!--
	"Host your own repository" (#191): `packer index` in the browser. Packs and bundles in, a ready-to-upload folder out
	(repository.json, previews, packs), made by shared/pack-spec's RepositoryIndexer and checked with the repository
	schema, plus the exact steps to put it online. Entirely client-side.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Html from '#lib/components/Html.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import PackReport from '#lib/components/PackReport.svelte';
	import { formatSize } from '#lib/format.js';
	import { fill, localeFromPath, messages } from '#lib/i18n/index.js';
	import {
		addToAppLink,
		buildRepository,
		isHttpsUrl,
		safeFileName,
		sha256Hex,
		type IndexedFile,
		type RepositoryOutcome
	} from '#lib/creator/indexer.js';
	import { downloadFile, uid } from '#lib/creator/pack.js';
	import { loadPackSpec, packSpecAvailable, type PackSpec } from '#lib/pack-spec/index.js';

	const locale = $derived(localeFromPath(page.url.pathname));
	const t = $derived(messages(locale).repository);
	const tc = $derived(messages(locale).create);

	let spec = $state.raw<PackSpec | null>(null);
	let loadError = $state('');

	let files = $state.raw<IndexedFile[]>([]);
	let problems = $state.raw<string[]>([]);
	let name = $state('');
	let description = $state('');
	let homepage = $state('');
	let where = $state<'here' | 'elsewhere'>('here');
	let baseUrl = $state('');
	let address = $state('');

	let outcome = $state.raw<RepositoryOutcome | null>(null);
	let builtFrom = $state('');
	let building = $state(false);
	let buildError = $state('');
	let missing = $state.raw<string[]>([]);
	let copied = $state(false);

	onMount(() => {
		const loading = loadPackSpec();
		if (!loading) return;
		loading
			.then((loaded) => (spec = loaded))
			.catch((error: unknown) => (loadError = String(error)));
	});

	const fingerprint = $derived(
		JSON.stringify([name, description, homepage, where, baseUrl, files.map((f) => f.uid)])
	);
	const stale = $derived(outcome !== null && builtFrom !== fingerprint);
	const baseUrlProblem = $derived(
		where === 'elsewhere' && baseUrl.trim() !== '' && !isHttpsUrl(baseUrl)
	);
	const homepageProblem = $derived(homepage.trim() !== '' && !isHttpsUrl(homepage));
	const addressOk = $derived(isHttpsUrl(address));

	async function addFiles(event: Event & { currentTarget: HTMLInputElement }) {
		const picked = [...(event.currentTarget.files ?? [])];
		event.currentTarget.value = '';
		if (!spec) return;
		const found: string[] = [];
		const added: IndexedFile[] = [];
		for (const file of picked) {
			try {
				const bytes = new Uint8Array(await file.arrayBuffer());
				const result = spec.validateArchive(bytes);
				if (!result.valid) {
					found.push(fill(t.files.invalid, { name: file.name }));
					continue;
				}
				const taken = [...files, ...added].map((f) => f.fileName);
				added.push({
					uid: uid('file'),
					fileName: safeFileName(file.name, taken),
					bytes,
					sha256: await sha256Hex(bytes),
					result
				});
			} catch (error) {
				found.push(fill(t.files.failed, { name: file.name, error: String(error) }));
			}
		}
		files = [...files, ...added];
		problems = found;
	}

	function remove(file: IndexedFile) {
		files = files.filter((f) => f.uid !== file.uid);
	}

	function label(file: IndexedFile) {
		const r = file.result;
		const pack = r.packs[0];
		return {
			name: r.kind === 'bundle' ? file.fileName : (pack?.name ?? file.fileName),
			detail: fill(t.files.item, {
				kind: r.kind === 'bundle' ? t.files.bundle : t.files.pack,
				id:
					r.kind === 'bundle'
						? fill(messages(locale).packCheck.bundle, { count: r.packs.length })
						: (pack?.id ?? ''),
				version: pack?.version ?? '',
				size: formatSize(file.bytes.length, locale)
			})
		};
	}

	async function make() {
		if (!spec) return;
		const needs: string[] = [];
		if (name.trim() === '') needs.push(t.build.nameMissing);
		if (files.length === 0) needs.push(t.build.noFiles);
		if (where === 'elsewhere' && !isHttpsUrl(baseUrl)) needs.push(t.details.httpsOnly);
		missing = needs;
		outcome = null;
		if (needs.length > 0) return;
		building = true;
		buildError = '';
		try {
			await new Promise((resolve) => setTimeout(resolve, 0));
			outcome = buildRepository(
				spec,
				{ name, description, homepage, baseUrl: where === 'elsewhere' ? baseUrl.trim() : null },
				files
			);
			builtFrom = fingerprint;
		} catch (error) {
			buildError = fill(t.build.failed, { error: String(error) });
		} finally {
			building = false;
		}
	}

	function download() {
		if (outcome?.kind !== 'built') return;
		downloadFile(
			new File([outcome.zip as BlobPart], 'repository.zip', { type: 'application/zip' })
		);
	}

	async function copy() {
		if (outcome?.kind !== 'built') return;
		try {
			await navigator.clipboard.writeText(outcome.json);
			copied = true;
			setTimeout(() => (copied = false), 3000);
		} catch {
			copied = false;
		}
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
		<section class="step" aria-labelledby="files">
			<h2 id="files" class="headline-small">{t.files.title}</h2>
			{#if files.length === 0}
				<p class="body-medium">{t.files.empty}</p>
			{:else}
				<ul class="files">
					{#each files as file (file.uid)}
						{@const l = label(file)}
						<li>
							<div class="file-text">
								<span class="title-small">{l.name}</span>
								<span class="body-small on-surface-variant">{l.detail}</span>
								<code class="body-small">{file.fileName}</code>
							</div>
							<button
								type="button"
								class="icon-button"
								aria-label={fill(t.files.remove, { name: l.name })}
								onclick={() => remove(file)}
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
					{t.files.add}
					<input
						class="visually-hidden"
						type="file"
						accept=".zip,application/zip"
						multiple
						aria-describedby="files-hint"
						onchange={addFiles}
					/>
				</label>
				<span id="files-hint" class="body-small on-surface-variant">{t.files.hint}</span>
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
					<span class="label-large">{t.details.description}</span>
					<textarea rows="2" maxlength="2000" bind:value={description}></textarea>
				</label>
				<label class="field">
					<span class="label-large">{t.details.homepage}</span>
					<input
						type="url"
						bind:value={homepage}
						autocomplete="url"
						aria-describedby="homepage-hint"
						aria-invalid={homepageProblem}
					/>
					<span id="homepage-hint" class="body-small on-surface-variant">
						{homepageProblem ? t.details.httpsOnly : t.details.homepageHint}
					</span>
				</label>
				<fieldset class="field">
					<legend class="label-large">{t.details.where}</legend>
					<label class="radio">
						<input type="radio" name="where" value="here" bind:group={where} />
						<span>{t.details.here}</span>
					</label>
					<label class="radio">
						<input type="radio" name="where" value="elsewhere" bind:group={where} />
						<span>{t.details.elsewhere}</span>
					</label>
					{#if where === 'elsewhere'}
						<label class="field">
							<span class="label-large">{t.details.baseUrl}</span>
							<input
								type="url"
								bind:value={baseUrl}
								placeholder="https://github.com/me/packs/releases/download/v1"
								aria-describedby="base-hint"
								aria-invalid={baseUrlProblem}
							/>
							<span id="base-hint" class="body-small on-surface-variant">
								{baseUrlProblem ? t.details.httpsOnly : t.details.baseUrlHint}
							</span>
						</label>
					{/if}
				</fieldset>
			</div>
		</section>

		<section class="step" aria-labelledby="build">
			<h2 id="build" class="headline-small">{t.build.title}</h2>
			<button type="button" class="button make" disabled={building} onclick={make}>
				<Icon name="folder-zip" />
				{building ? t.build.making : t.build.make}
			</button>
			<div class="results" aria-live="polite">
				{#if missing.length > 0}
					<div class="card incomplete">
						<p class="title-medium">{t.build.incomplete}</p>
						<ul>
							{#each missing as m (m)}<li>{m}</li>{/each}
						</ul>
					</div>
				{:else if buildError}
					<p class="notice error">{buildError}</p>
				{:else if outcome?.kind === 'invalid'}
					<p class="notice error">{t.build.invalid}</p>
					<PackReport name="repository.json" size={0} result={outcome.result} />
				{:else if outcome?.kind === 'built'}
					{#if stale}
						<p class="notice"><Icon name="warning" />{t.build.stale}</p>
					{/if}
					<p class="body-large ready">
						<Icon name="check-circle" />{fill(t.build.ready, { n: outcome.entries })}
					</p>
					<div class="row">
						<button type="button" class="button" disabled={stale} onclick={download}>
							<Icon name="download" />
							{t.build.download}
						</button>
						<button type="button" class="button tonal" onclick={copy}>
							<Icon name="content-copy" />
							{copied ? t.build.copied : t.build.copy}
						</button>
					</div>
					<!-- A scrollable region must be reachable with the keyboard (WCAG 2.1.1, axe scrollable-region-focusable). -->
					<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
					<pre class="index" tabindex="0" aria-label="repository.json"><code>{outcome.json}</code
						></pre>
				{/if}
			</div>
		</section>

		<section class="step" aria-labelledby="host">
			<h2 id="host" class="headline-small">{t.host.title}</h2>
			<h3 class="title-large">{t.host.githubTitle}</h3>
			<ol class="steps">
				{#each t.host.github as item, i (i)}
					<li><Html text={item} /></li>
				{/each}
			</ol>
			<p class="body-small on-surface-variant">{t.host.githubNote}</p>
			<h3 class="title-large">{t.host.driveTitle}</h3>
			<p><Html text={t.host.driveHtml} /></p>
			<h3 class="title-large">{t.host.otherTitle}</h3>
			<p><Html text={t.host.otherHtml} /></p>
			<label class="field">
				<span class="label-large">{t.host.address}</span>
				<input
					type="url"
					bind:value={address}
					placeholder="https://me.github.io/my-packs/"
					aria-describedby="address-hint"
				/>
				<span id="address-hint" class="body-small on-surface-variant">{t.host.addressHint}</span>
			</label>
			{#if addressOk}
				<p class="body-medium add-link">
					{t.host.addLink}
					<a href={addToAppLink(address)}><code>{addToAppLink(address)}</code></a>
				</p>
			{/if}
			<p class="body-medium"><Html text={t.host.docsHtml} /></p>
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

	.make {
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

	.ready {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.ready :global(.icon) {
		color: var(--md-sys-color-primary);
	}

	.index {
		max-height: 24rem;
		overflow: auto;
		padding: 1rem;
		border-radius: 1rem;
		background: var(--md-sys-color-surface-container);
		font-size: 0.8125rem;
	}

	.steps {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		padding-left: 1.5rem;
	}

	.add-link code {
		overflow-wrap: anywhere;
	}
</style>
