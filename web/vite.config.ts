import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { existsSync } from 'node:fs';
import { defineConfig } from 'vite';
import { picture } from './scripts/picture.ts';

// The pack validator (shared/pack-spec compiled to JavaScript, src/lib/pack-spec/) is optional locally, so the site
// builds without a JDK; CI builds it first and sets CUCUTO_REQUIRE_PACK_SPEC=1 so it is never silently left out.
const packSpecModule = new URL('./src/lib/pack-spec/generated/pack-spec.mjs', import.meta.url);
if (!existsSync(packSpecModule)) {
	if (process.env.CUCUTO_REQUIRE_PACK_SPEC === '1') {
		throw new Error(
			'Missing src/lib/pack-spec/generated/: run ./gradlew syncWebPackSpec in shared/pack-spec'
		);
	}
	console.warn(
		'Pack validator not built (run ./gradlew syncWebPackSpec in shared/pack-spec); the site omits it.'
	);
}

export default defineConfig({
	build: {
		// Keep font files as files (the CSP allows fonts from our origin only, not data: URIs).
		assetsInlineLimit: (file) => (file.endsWith('.woff2') ? false : undefined),
		// The pack validator (src/lib/pack-spec) is one ~730 kB chunk (about 220 kB gzipped), loaded only on use.
		chunkSizeWarningLimit: 800
	},
	plugins: [
		// `?picture` imports: JPEG XL + AVIF + PNG at build time, rendered by <Picture> (#40).
		picture(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// Every route is prerendered (see src/routes/+layout.ts). GitHub Pages serves `404.html` for unknown
			// paths, so the SPA fallback doubles as the not-found page.
			adapter: adapter({ fallback: '404.html' }),
			paths: { origin: 'https://cucuto.mauri.app' },
			// The site promises no third-party requests: everything, fonts included, comes from our own origin.
			// Prerendered pages get this policy as a <meta http-equiv> tag with hashes for SvelteKit's inline scripts.
			csp: {
				mode: 'hash',
				directives: {
					'default-src': ['self'],
					'script-src': ['self'],
					'style-src': ['self', 'unsafe-inline'],
					'img-src': ['self', 'data:'],
					// Sample clips in the pack gallery come from /repo/previews/.
					'media-src': ['self'],
					'font-src': ['self'],
					'connect-src': ['self'],
					'manifest-src': ['self'],
					'worker-src': ['self'],
					'base-uri': ['self'],
					'form-action': ['self'],
					'object-src': ['none']
				}
			}
		})
	]
});
