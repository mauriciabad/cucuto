// The pack format rules on the website: shared/pack-spec (Kotlin, the same code packer and the app run) compiled to
// JavaScript, never a TypeScript port (owner's answer on #60, docs/decisions/0022-pack-spec-in-the-browser.md).
//
// `cd shared/pack-spec && ./gradlew syncWebPackSpec` writes the compiled module to ./generated/ (git-ignored; CI's
// `pack-spec-js` job builds it before the `web` job). This file types its exports and turns them into plain objects,
// so it has no import of the generated code: index.ts loads it in the site, tests/pack-spec.test.ts under Bun.

/** `error` blocks an import; `warning` is shown and the import continues. */
export type Severity = 'error' | 'warning';

/** Validation stages, in order (docs/pack-format.md#validation-stages). */
export type Stage = 'archive' | 'layout' | 'manifest' | 'references' | 'media' | 'runtime';

/** Standalone JSON documents that are not packs. */
export type DocumentKind = 'repository' | 'backup' | 'backup-envelope';

/** One finding. Only `code` (from spec/error-codes.json) is stable; `message` is English and may change. */
export interface PackIssue {
	code: string;
	severity: Severity;
	stage: Stage;
	message: string;
	/** Path inside the pack or bundle, when the issue concerns a file. */
	file: string | null;
	/** JSON Pointer into the manifest, when the issue concerns a manifest value. */
	pointer: string | null;
}

/** A pack found in a validated archive. `root` is '' for a single pack, or its folder inside a bundle. */
export interface PackSummary {
	root: string;
	id: string;
	name: string;
	version: string;
	author: string;
	license: string;
	characters: number;
}

export interface PackSpecResult {
	valid: boolean;
	issues: PackIssue[];
	/** Distinct and sorted: what the golden fixtures in spec/fixtures compare. */
	errorCodes: string[];
	warningCodes: string[];
	/** `null` when the manifests could not be read, and for standalone documents. */
	kind: 'pack' | 'bundle' | null;
	packs: PackSummary[];
}

export interface PackSpec {
	/** The newest pack format version this build reads. */
	formatVersion: number;
	/** A pack or bundle archive: any `.zip`, the type comes from the manifest at its root. */
	validateArchive(bytes: Uint8Array): PackSpecResult;
	/** An unpacked pack or bundle: relative '/'-separated paths and their contents (a folder, the pack creator). */
	validateFiles(files: Iterable<readonly [string, Uint8Array]>): PackSpecResult;
	validateDocument(kind: DocumentKind, bytes: Uint8Array): PackSpecResult;
	/** The pack creators' shared conventions (authoring/ in shared/pack-spec, the same Kotlin as the app's creator). */
	authoring: Authoring;
}

/** A file the creator holds: `key` is opaque to Kotlin (the website uses its own IDs), `extension` has no dot. */
export interface DraftFileRef {
	key: string;
	extension: string;
}

/** A pack being made, as `PackDraft` in shared/pack-spec. Blank optional texts are left out of the manifest. */
export interface PackDraftInput {
	/** Null to derive `local.<author>.<name>-<idSuffix>`. */
	id: string | null;
	idSuffix: string;
	name: string;
	version?: string;
	author: string;
	license: string;
	description?: string | null;
	cover?: DraftFileRef | null;
	characters: CharacterDraftInput[];
	/** The pack's homepage (https) and tags. */
	homepage?: string | null;
	tags?: string[];
}

/** A character of a `PackDraftInput`. `tags` and `spriteAnimated` are pass-through data from an opened pack. */
export interface CharacterDraftInput {
	/** Null to derive a unique slug from the name. */
	id: string | null;
	name: string;
	sprite: DraftFileRef | null;
	seedColor: string | null;
	clips: ClipDraftInput[];
	/** Null for the format's default (`auto`). */
	haptic?: HapticJson | null;
	description?: string | null;
	tags?: string[];
	/** A GIF, animated WebP or animated JPEG XL, kept from the opened pack. */
	spriteAnimated?: DraftFileRef | null;
	/** Ringtone variants (`RingtoneForm`, or as an opened pack had them): written with only `file` replaced. */
	ringtones?: { file: DraftFileRef; entry: Record<string, unknown> }[];
}

export interface ClipDraftInput {
	file: DraftFileRef;
	label: string | null;
	weight?: number | null;
	/** The clip's condition (day and night clips, moments), as `writeCondition` makes it. */
	when?: Record<string, unknown> | null;
	/** A rare clip's chance, one in `oneIn` (docs/pack-format.md#special-clips). */
	oneIn?: number | null;
}

/** One envelope point of a custom haptic, as in `pack.json`. */
export interface ControlPointJson {
	intensity: number;
	sharpness: number;
	ms: number;
}

/** One custom haptic event, as in `pack.json`: a preset or a basic envelope. */
export interface HapticEventJson {
	at: number;
	preset?: 'CLICK' | 'TICK' | 'LOW_TICK';
	scale?: number;
	initialSharpness?: number;
	envelope?: ControlPointJson[];
}

/** A character's `haptic`, as in `pack.json`. */
export interface HapticJson {
	mode: 'auto' | 'none' | 'custom';
	events?: HapticEventJson[];
}

/** Why a pasted pattern was refused (`HapticPatternText.Problem` in shared/pack-spec). */
export interface HapticProblem {
	kind:
		| 'NotJson'
		| 'NotCustom'
		| 'NoEvents'
		| 'TooManyEvents'
		| 'NotAnEvent'
		| 'MissingField'
		| 'UnknownField'
		| 'NotWholeNumber'
		| 'OutOfRange'
		| 'UnknownPreset'
		| 'PointCount'
		| 'NotClosed'
		| 'Unsorted'
		| 'Overlap'
		| 'TooLong';
	event?: number;
	point?: number;
	field?: string;
	value?: string;
	min?: number;
	max?: number;
	count?: number;
	ms?: number;
	until?: number;
}

export type HapticParse =
	{ valid: true; events: HapticEventJson[] } | { valid: false; problem: HapticProblem };

/** A clip as "Copy for AI" describes it. `loudness` rows are `[ms, loudness, sharpness]`, 0 to 100. */
export interface PromptClip {
	path: string;
	durationMs: number | null;
	loudness?: [number, number, number][] | null;
}

/** An archive opened for editing. `draft` keys are paths in the archive; `roots` lists a bundle's pack folders. */
export interface OpenedPack {
	result: PackSpecResult;
	roots: string[];
	draft: PackDraftInput | null;
	/** Audio paths in the archive and their length in milliseconds. */
	durations: Record<string, number>;
}

/** A bundle being made from finished packs (`BundleDraft` in shared/pack-spec). */
export interface BundleDraftInput {
	id: string;
	name: string;
	version: string;
	author?: string | null;
	license?: string | null;
	homepage?: string | null;
	description?: string | null;
	cover?: DraftFileRef | null;
	tags?: string[];
	packIds: string[];
}

export type BundleProblem =
	| 'BUNDLE_ID_INVALID'
	| 'BUNDLE_NAME_MISSING'
	| 'VERSION_INVALID'
	| 'NO_PACKS'
	| 'TOO_MANY_PACKS'
	| 'PACK_TWICE'
	| 'ID_CLASH'
	| 'UNSUPPORTED_FILE';

export interface BundleAssembly {
	problems: BundleProblem[];
	manifestJson: string | null;
	/** `packs/<pack id>`, in the order of `packIds`. */
	roots: string[];
	coverPath: string | null;
	fileName: string | null;
}

/** One archive indexed for a repository, as `packer index` does. */
export interface IndexedArchive {
	result: PackSpecResult;
	/** The `repository.json` entry, or null when the archive is invalid. */
	entry: Record<string, unknown> | null;
	/** Preview files to copy: `path` in the repository folder, `source` in the archive. */
	previews: { path: string; source: string }[];
}

export interface RepositoryTools {
	/** Where a file is downloaded from: `packs/<file>`, or `<baseUrl>/<file>` when the packs live elsewhere. */
	downloadUrl(fileName: string, baseUrl: string | null): string;
	entry(bytes: Uint8Array, downloadUrl: string, sha256: string): IndexedArchive;
	/** `repository.json` (null when the schema check fails) and the check. */
	index(
		meta: {
			name: string;
			description?: string | null;
			homepage?: string | null;
			generatedAt?: string | null;
		},
		entries: Record<string, unknown>[]
	): { json: string | null; result: PackSpecResult };
}

/** What a draft still needs (`DraftProblem.Kind`); `character` is the index of the character concerned. */
export interface DraftProblem {
	kind:
		| 'PACK_NAME_MISSING'
		| 'AUTHOR_MISSING'
		| 'LICENSE_MISSING'
		| 'NO_CHARACTERS'
		| 'TOO_MANY_CHARACTERS'
		| 'CHARACTER_NAME_MISSING'
		| 'SPRITE_MISSING'
		| 'NO_CLIPS'
		| 'TOO_MANY_CLIPS'
		| 'UNSUPPORTED_FILE';
	character: number | null;
}

/** A laid-out pack: `pack.json` and the archive path of every draft file, in archive order. */
export interface Assembly {
	problems: DraftProblem[];
	manifestJson: string | null;
	packId: string | null;
	fileName: string | null;
	files: { path: string; key: string }[];
}

/** A clip after the shared pipeline: mono floats at 48 kHz. `loudnessLufs` is null for silence. */
export interface ProcessedClip {
	samples: Float32Array;
	sampleRate: number;
	gainDb: number;
	loudnessLufs: number | null;
	silent: boolean;
	durationMs: number;
}

/** The creators' targets (Authoring.Audio, Authoring.Image and Limits in shared/pack-spec). */
export interface AuthoringTargets {
	sampleRate: number;
	channels: number;
	clipBitrate: number;
	spriteSize: number;
	clipMinMs: number;
	clipMaxMs: number;
	/** Clips longer than this get a gentle hint, never an error (ADR 0019). */
	clipHintMs: number;
	maxSourceMs: number;
	hookLengthMs: number;
	/** Ringtones last 5 s to 6 min (the format's limits). */
	ringtoneMinMs: number;
	ringtoneMaxMs: number;
	/** Custom haptics: the longest timeline, most events and most points per envelope. */
	hapticMaxMs: number;
	hapticMaxEvents: number;
	hapticMaxPoints: number;
}

/** A span of a `when.date` as typed: `MM-DD`, `to` null (or equal) for one day. */
export interface DateFormJson {
	from: string;
	to?: string | null;
}

/**
 * A `when` (and a clip's `oneIn`) as the creators edit it: `ConditionForm` in shared/pack-spec, shared with the app's
 * creator, so both read and write conditions alike.
 */
export interface ConditionFormJson {
	time: 'ANY' | 'DAY' | 'NIGHT' | 'HOURS';
	/** `HH:MM`, for `HOURS`. */
	from: string;
	to: string;
	/** `spring` … `winter`; empty for all year. */
	seasons: string[];
	/** `mon` … `sun`; empty for every day. */
	weekdays: string[];
	dates: DateFormJson[];
	/** Clips only. */
	moment: 'ANY' | 'FIRST_OF_DAY' | 'BURST';
	burst: number;
	/** Clips only: about one message in `oneIn`; null for an ordinary clip. */
	oneIn: number | null;
}

/** A ringtone version as the creators edit it (`RingtoneForm` in shared/pack-spec). */
export interface RingtoneFormJson {
	label: string;
	condition: ConditionFormJson;
	loop: 'WHOLE' | 'ONCE' | 'SECTION';
	loopStartMs: number;
	/** Null for the end of the file. */
	loopEndMs: number | null;
	fadeInMs: number;
	weight: number;
	tags: string[];
}

/** Something a condition or ringtone form still needs (`FormProblem.Kind`); `index` is the date concerned. */
export interface FormProblem {
	kind:
		| 'TIME_INVALID'
		| 'TIME_RANGE_EMPTY'
		| 'DATE_INVALID'
		| 'TOO_MANY_DATES'
		| 'BURST_OUT_OF_RANGE'
		| 'ONE_IN_OUT_OF_RANGE'
		| 'RINGTONE_DURATION_OUT_OF_RANGE'
		| 'LOOP_INVALID'
		| 'FADE_IN_OUT_OF_RANGE'
		| 'WEIGHT_OUT_OF_RANGE';
	index: number | null;
}

export interface Authoring {
	targets: AuthoringTargets;
	licensePresets: string[];
	/** The license a new pack starts with, as in the app: `Personal use only` (free text, a warning). */
	personalUseLicense: string;
	assemble(draft: PackDraftInput): Assembly;
	packFileName(name: string, id: string): string;
	suggestPackId(author: string, name: string, suffix: string): string;
	randomIdSuffix(): string;
	/** Cut `[startMs, endMs)` of 48 kHz mono `samples`, trim silence, normalize, fade. `samples` is not changed. */
	processClip(
		samples: Float32Array,
		startMs: number,
		endMs: number,
		options: { trimSilence: boolean; normalize: boolean }
	): ProcessedClip;
	/** The hook of a song for a clip of `lengthMs` (the shared auto-cut heuristic): `[startMs, endMs]`. */
	findHook(samples: Float32Array, sampleRate: number, lengthMs: number): [number, number];
	/** Opens a pack (or the pack in folder `root` of a bundle) for editing, with the app's pass-through rules. */
	openPack(bytes: Uint8Array, root?: string | null): OpenedPack;
	/** The version that installs over `installed` as an update. */
	versionAfter(current: string, installed: string | null): string;
	assembleBundle(draft: BundleDraftInput): BundleAssembly;
	isPackId(id: string): boolean;
	repository: RepositoryTools;
	/** "Copy for AI": the prompt for a character's clips and current pattern (shared with the app). */
	hapticPrompt(
		character: string | null,
		clips: PromptClip[],
		current: HapticEventJson[] | null
	): string;
	/** `{"mode":"custom","events":[…]}`, compact, as `pack.json` carries it. */
	hapticPatternJson(events: HapticEventJson[]): string;
	/** "Paste pattern": the pattern in a text, checked with the pack format's haptic rules. */
	parseHapticPattern(text: string): HapticParse;
	/** The form for a clip's or ringtone's `when` (null for none) and a clip's `oneIn`. */
	conditionForm(when: Record<string, unknown> | null, oneIn: number | null): ConditionFormJson;
	/** The `when` (null for none) and `oneIn` a form stands for; `clip` keeps the clip-only fields. */
	writeCondition(
		form: ConditionFormJson,
		clip: boolean
	): { when: Record<string, unknown> | null; oneIn: number | null; problems: FormProblem[] };
	/** The form for a pack's ringtone entry. */
	ringtoneForm(entry: Record<string, unknown>): RingtoneFormJson;
	/** The ringtone entry for `file`; `durationMs` (null when unknown) checks the length and the loop. */
	writeRingtone(
		form: RingtoneFormJson,
		file: string,
		durationMs: number | null
	): { entry: Record<string, unknown>; problems: FormProblem[] };
	/** Tags typed as text (`Animals, 8-bit games`), as the format's tags (`animals`, `8-bit-games`). */
	parseTags(text: string): string[];
	/** Whether `url` is an absolute https URL. */
	isHomepage(url: string): boolean;
}

type Nullable<T> = T | null | undefined;

interface RawIssue {
	readonly code: string;
	readonly severity: string;
	readonly stage: string;
	readonly message: string;
	readonly file: Nullable<string>;
	readonly pointer: Nullable<string>;
}

interface RawResult {
	readonly valid: boolean;
	readonly issues: RawIssue[];
	readonly errorCodes: string[];
	readonly warningCodes: string[];
	readonly kind: Nullable<string>;
	readonly packs: PackSummary[];
}

/** The exports of generated/pack-spec.mjs (declared in generated/pack-spec.d.mts; jsMain/PackSpecJs.kt in Kotlin). */
export interface PackSpecModule {
	packFormatVersion(): number;
	validateArchive(bytes: Int8Array): RawResult;
	validateFiles(paths: string[], contents: Int8Array[]): RawResult;
	validateDocument(kind: string, bytes: Int8Array): RawResult;
	assemblePack(draft: string): {
		readonly problems: string[];
		readonly manifestJson: Nullable<string>;
		readonly packId: Nullable<string>;
		readonly fileName: Nullable<string>;
		readonly files: { readonly path: string; readonly key: string }[];
	};
	packFileName(name: string, id: string): string;
	suggestPackId(author: string, name: string, suffix: string): string;
	randomIdSuffix(): string;
	licensePresets(): string[];
	personalUseLicense(): string;
	processClip(
		samples: Float32Array,
		startMs: number,
		endMs: number,
		trimSilence: boolean,
		normalize: boolean
	): {
		readonly samples: Float32Array;
		readonly sampleRate: number;
		readonly gainDb: number;
		readonly loudnessLufs: number;
		readonly silent: boolean;
		readonly durationMs: number;
	};
	findHook(samples: Float32Array, sampleRate: number, lengthMs: number): number[];
	authoringTargets(): AuthoringTargets;
	openPack(
		bytes: Int8Array,
		root: Nullable<string>
	): {
		readonly result: RawResult;
		readonly roots: string[];
		readonly draftJson: Nullable<string>;
		readonly durationsJson: Nullable<string>;
	};
	versionAfter(current: string, installed: Nullable<string>): string;
	assembleBundle(draft: string): {
		readonly problems: string[];
		readonly manifestJson: Nullable<string>;
		readonly roots: string[];
		readonly coverPath: Nullable<string>;
		readonly fileName: Nullable<string>;
	};
	isPackId(id: string): boolean;
	repositoryEntry(
		bytes: Int8Array,
		downloadUrl: string,
		sha256: string
	): {
		readonly result: RawResult;
		readonly entryJson: Nullable<string>;
		readonly previewPaths: string[];
		readonly previewSources: string[];
	};
	repositoryDownloadUrl(fileName: string, baseUrl: Nullable<string>): string;
	repositoryIndex(
		name: string,
		description: Nullable<string>,
		homepage: Nullable<string>,
		generatedAt: Nullable<string>,
		entries: string[]
	): { readonly json: Nullable<string>; readonly result: RawResult };
	hapticPrompt(
		character: Nullable<string>,
		clips: string,
		currentEventsJson: Nullable<string>
	): string;
	hapticPatternJson(eventsJson: string): string;
	parseHapticPattern(text: string): {
		readonly eventsJson: Nullable<string>;
		readonly problemJson: Nullable<string>;
	};
	conditionForm(whenJson: Nullable<string>, oneIn: Nullable<number>): string;
	writeCondition(
		formJson: string,
		clip: boolean
	): {
		readonly whenJson: Nullable<string>;
		readonly oneIn: Nullable<number>;
		readonly problems: string[];
	};
	ringtoneForm(entryJson: string): string;
	writeRingtone(
		formJson: string,
		file: string,
		durationMs: number
	): { readonly entryJson: string; readonly problems: string[] };
	parseTags(text: string): string[];
	isHomepage(url: string): boolean;
}

// Kotlin's ByteArray is an Int8Array: a view over the same memory, no copy.
const int8 = (bytes: Uint8Array) => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);

const plain = (r: RawResult): PackSpecResult => ({
	valid: r.valid,
	issues: r.issues.map((i) => ({
		code: i.code,
		severity: i.severity as Severity,
		stage: i.stage as Stage,
		message: i.message,
		file: i.file ?? null,
		pointer: i.pointer ?? null
	})),
	errorCodes: [...r.errorCodes],
	warningCodes: [...r.warningCodes],
	kind: (r.kind ?? null) as PackSpecResult['kind'],
	packs: r.packs.map((p) => ({
		root: p.root,
		id: p.id,
		name: p.name,
		version: p.version,
		author: p.author,
		license: p.license,
		characters: p.characters
	}))
});

/** Types the compiled module and returns plain, serializable results. */
export function packSpec(module: PackSpecModule): PackSpec {
	return {
		formatVersion: module.packFormatVersion(),
		validateArchive: (bytes) => plain(module.validateArchive(int8(bytes))),
		validateFiles: (files) => {
			const paths: string[] = [];
			const contents: Int8Array[] = [];
			for (const [path, bytes] of files) {
				paths.push(path);
				contents.push(int8(bytes));
			}
			return plain(module.validateFiles(paths, contents));
		},
		validateDocument: (kind, bytes) => plain(module.validateDocument(kind, int8(bytes))),
		authoring: authoring(module)
	};
}

function authoring(module: PackSpecModule): Authoring {
	const t = module.authoringTargets();
	return {
		targets: {
			sampleRate: t.sampleRate,
			channels: t.channels,
			clipBitrate: t.clipBitrate,
			spriteSize: t.spriteSize,
			clipMinMs: t.clipMinMs,
			clipMaxMs: t.clipMaxMs,
			clipHintMs: t.clipHintMs,
			maxSourceMs: t.maxSourceMs,
			hookLengthMs: t.hookLengthMs,
			ringtoneMinMs: t.ringtoneMinMs,
			ringtoneMaxMs: t.ringtoneMaxMs,
			hapticMaxMs: t.hapticMaxMs,
			hapticMaxEvents: t.hapticMaxEvents,
			hapticMaxPoints: t.hapticMaxPoints
		},
		licensePresets: [...module.licensePresets()],
		personalUseLicense: module.personalUseLicense(),
		assemble(draft) {
			const r = module.assemblePack(JSON.stringify(draft));
			return {
				problems: r.problems.map((code) => {
					const [kind, index] = code.split(':');
					return {
						kind: kind as DraftProblem['kind'],
						character: index === undefined ? null : Number(index)
					};
				}),
				manifestJson: r.manifestJson ?? null,
				packId: r.packId ?? null,
				fileName: r.fileName ?? null,
				files: r.files.map((f) => ({ path: f.path, key: f.key }))
			};
		},
		packFileName: (name, id) => module.packFileName(name, id),
		suggestPackId: (author, name, suffix) => module.suggestPackId(author, name, suffix),
		randomIdSuffix: () => module.randomIdSuffix(),
		processClip(samples, startMs, endMs, { trimSilence, normalize }) {
			const r = module.processClip(samples, startMs, endMs, trimSilence, normalize);
			return {
				samples: r.samples,
				sampleRate: r.sampleRate,
				gainDb: r.gainDb,
				loudnessLufs: Number.isNaN(r.loudnessLufs) ? null : r.loudnessLufs,
				silent: r.silent,
				durationMs: r.durationMs
			};
		},
		findHook(samples, sampleRate, lengthMs) {
			const [start, end] = module.findHook(samples, sampleRate, lengthMs);
			return [start, end];
		},
		openPack(bytes, root) {
			const r = module.openPack(int8(bytes), root ?? null);
			return {
				result: plain(r.result),
				roots: [...r.roots],
				draft: r.draftJson ? (JSON.parse(r.draftJson) as PackDraftInput) : null,
				durations: r.durationsJson ? (JSON.parse(r.durationsJson) as Record<string, number>) : {}
			};
		},
		versionAfter: (current, installed) => module.versionAfter(current, installed),
		assembleBundle(draft) {
			const r = module.assembleBundle(JSON.stringify(draft));
			return {
				problems: r.problems.map((p) => p as BundleProblem),
				manifestJson: r.manifestJson ?? null,
				roots: [...r.roots],
				coverPath: r.coverPath ?? null,
				fileName: r.fileName ?? null
			};
		},
		isPackId: (id) => module.isPackId(id),
		repository: {
			downloadUrl: (fileName, baseUrl) => module.repositoryDownloadUrl(fileName, baseUrl),
			entry(bytes, downloadUrl, sha256) {
				const r = module.repositoryEntry(int8(bytes), downloadUrl, sha256);
				return {
					result: plain(r.result),
					entry: r.entryJson ? (JSON.parse(r.entryJson) as Record<string, unknown>) : null,
					previews: r.previewPaths.map((path, i) => ({ path, source: r.previewSources[i] }))
				};
			},
			index(meta, entries) {
				const r = module.repositoryIndex(
					meta.name,
					meta.description ?? null,
					meta.homepage ?? null,
					meta.generatedAt ?? null,
					entries.map((e) => JSON.stringify(e))
				);
				return { json: r.json ?? null, result: plain(r.result) };
			}
		},
		hapticPrompt: (character, clips, current) =>
			module.hapticPrompt(
				character,
				JSON.stringify(clips),
				current && current.length > 0 ? JSON.stringify(current) : null
			),
		hapticPatternJson: (events) => module.hapticPatternJson(JSON.stringify(events)),
		parseHapticPattern(text) {
			const r = module.parseHapticPattern(text);
			if (r.eventsJson)
				return { valid: true, events: JSON.parse(r.eventsJson) as HapticEventJson[] };
			return {
				valid: false,
				problem: JSON.parse(r.problemJson ?? '{"kind":"NotJson"}') as HapticProblem
			};
		},
		conditionForm: (when, oneIn) =>
			conditionFormOf(JSON.parse(module.conditionForm(when ? JSON.stringify(when) : null, oneIn))),
		writeCondition(form, clip) {
			const r = module.writeCondition(JSON.stringify(form), clip);
			return {
				when: r.whenJson ? (JSON.parse(r.whenJson) as Record<string, unknown>) : null,
				oneIn: r.oneIn ?? null,
				problems: r.problems.map(formProblem)
			};
		},
		ringtoneForm(entry) {
			const form = JSON.parse(module.ringtoneForm(JSON.stringify(entry))) as RingtoneFormJson;
			return {
				...form,
				loopEndMs: form.loopEndMs ?? null,
				condition: conditionFormOf(form.condition)
			};
		},
		writeRingtone(form, file, durationMs) {
			const r = module.writeRingtone(JSON.stringify(form), file, durationMs ?? Number.NaN);
			return {
				entry: JSON.parse(r.entryJson) as Record<string, unknown>,
				problems: r.problems.map(formProblem)
			};
		},
		parseTags: (text) => [...module.parseTags(text)],
		isHomepage: (url) => module.isHomepage(url)
	};
}

/** A form from Kotlin, whose JSON leaves nulls out, with every optional field present as null. */
function conditionFormOf(form: ConditionFormJson): ConditionFormJson {
	return {
		...form,
		oneIn: form.oneIn ?? null,
		dates: form.dates.map((d) => ({ from: d.from, to: d.to ?? null }))
	};
}

/** `KIND` or `KIND:<index>` from Kotlin. */
function formProblem(code: string): FormProblem {
	const [kind, index] = code.split(':');
	return { kind: kind as FormProblem['kind'], index: index === undefined ? null : Number(index) };
}
