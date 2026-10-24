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
	characters: {
		/** Null to derive a unique slug from the name. */
		id: string | null;
		name: string;
		sprite: DraftFileRef | null;
		seedColor: string | null;
		clips: { file: DraftFileRef; label: string | null; weight?: number | null }[];
	}[];
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
			hookLengthMs: t.hookLengthMs
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
		}
	};
}
