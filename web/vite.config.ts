import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	build: {
		// Keep font files as files (the CSP allows fonts from our origin only, not data: URIs).
		assetsInlineLimit: (file) => (file.endsWith('.woff2') ? false : undefined)
	},
	plugins: [
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
