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
		validateDocument: (kind, bytes) => plain(module.validateDocument(kind, int8(bytes)))
	};
}
