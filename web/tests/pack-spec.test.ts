/// <reference types="bun" />
// Every golden fixture in spec/fixtures through the browser build of shared/pack-spec, with the same expected error
// and warning codes as packer (JVM) and the Android importer (#49, ADR 0022). Run with `bun run test:unit` after
// `cd ../shared/pack-spec && ./gradlew syncWebPackSpec`.
import { describe, expect, test } from 'bun:test';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { packSpec, type PackSpecModule, type PackSpecResult } from '../src/lib/pack-spec/core.ts';

const spec = join(import.meta.dir, '..', '..', 'spec');
const fixtures = join(spec, 'fixtures');
const generated = join(
	import.meta.dir,
	'..',
	'src',
	'lib',
	'pack-spec',
	'generated',
	'pack-spec.mjs'
);

if (!existsSync(generated)) {
	throw new Error(
		`${generated} is missing: run ./gradlew syncWebPackSpec in shared/pack-spec first`
	);
}
const validator = packSpec((await import(generated)) as PackSpecModule);

interface Fixture {
	path: string;
	kind: 'package' | 'repository' | 'backup' | 'backup-envelope';
	errors: string[];
	warnings: string[];
}

const manifest = JSON.parse(readFileSync(join(fixtures, 'fixtures.json'), 'utf8')) as {
	fixtures: Fixture[];
};

/** Every file below a folder, as pack-relative '/' paths, like FolderPackSource on the JVM. */
function* files(root: string, dir = root): Generator<[string, Uint8Array]> {
	for (const name of readdirSync(dir).sort()) {
		const path = join(dir, name);
		if (statSync(path).isDirectory()) yield* files(root, path);
		else yield [relative(root, path).split(sep).join('/'), new Uint8Array(readFileSync(path))];
	}
}

function validate(fixture: Fixture): PackSpecResult {
	const path = join(fixtures, fixture.path);
	if (fixture.kind !== 'package')
		return validator.validateDocument(fixture.kind, readFileSync(path));
	return statSync(path).isDirectory()
		? validator.validateFiles(files(path))
		: validator.validateArchive(readFileSync(path));
}

describe('golden fixtures (spec/fixtures/fixtures.json)', () => {
	for (const fixture of manifest.fixtures) {
		test(fixture.path, () => {
			const result = validate(fixture);
			const details = result.issues
				.map((i) => `${i.code} ${i.file ?? ''}: ${i.message}`)
				.join('\n');
			expect({ errors: result.errorCodes, warnings: result.warningCodes }, details).toEqual({
				errors: [...fixture.errors].sort(),
				warnings: [...fixture.warnings].sort()
			});
			expect(result.valid).toBe(fixture.errors.length === 0);
		});
	}
});

test('error codes and stages match spec/error-codes.json', () => {
	const codes = JSON.parse(readFileSync(join(spec, 'error-codes.json'), 'utf8')) as {
		codes: { code: string; severity: string; stage: string }[];
	};
	const known = new Map(codes.codes.map((c) => [c.code, c]));
	for (const fixture of manifest.fixtures) {
		for (const issue of validate(fixture).issues) {
			const entry = known.get(issue.code);
			expect(entry, issue.code).toBeDefined();
			expect<string[]>([issue.severity, issue.stage]).toEqual([entry!.severity, entry!.stage]);
		}
	}
});

test('reports the pack format version and pack summaries', () => {
	expect(validator.formatVersion).toBe(1);
	const result = validator.validateArchive(
		readFileSync(join(fixtures, 'valid', 'minimal-pack.soundpack.zip'))
	);
	expect(result.kind).toBe('pack');
	expect(result.packs).toHaveLength(1);
	expect(result.packs[0].root).toBe('');
});
