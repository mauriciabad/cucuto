// Loads the browser build of shared/pack-spec on demand (core.ts explains where it comes from). The module is about
// 290 kB gzipped, so pages import it only when someone checks a file, and it gets its own chunk.
import { packSpec, type PackSpec, type PackSpecModule } from './core.js';

export * from './core.js';

// A glob instead of a plain import, so `bun run build` still works without a JDK (the checker then says it is not
// part of this build). CI sets CUCUTO_REQUIRE_PACK_SPEC=1, and vite.config.ts fails the build if the module is missing.
const modules = import.meta.glob<PackSpecModule>('./generated/pack-spec.mjs');
const load = Object.values(modules)[0];

/** Whether this build of the site includes the validator. */
export const packSpecAvailable = load !== undefined;

let cached: Promise<PackSpec> | undefined;

/** The validator, or null when this build of the site does not include it. */
export function loadPackSpec(): Promise<PackSpec> | null {
	if (!load) return null;
	cached ??= load()
		.then(packSpec)
		.catch((error: unknown) => {
			cached = undefined; // a failed chunk download can be retried
			throw error;
		});
	return cached;
}
