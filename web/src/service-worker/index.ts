// App-shell service worker (https://svelte.dev/docs/kit/service-workers): precache the build output, the small
// static files and every prerendered page, so the site loads offline and the installed PWA starts instantly.
import { self } from '$app/service-worker';
import { version } from '$app/env';
import { immutable, assets, prerendered } from '$app/manifest';
import { resolve } from '$app/paths';

const CACHE = `cucuto-${version}`;

// Fonts are split per script; only Latin is worth precaching, the rest load (and get cached) on demand.
const skipImmutable = (path: string) => path.endsWith('.woff2') && !/-latin(-ext)?-/.test(path);
// The social card is for crawlers, CNAME and robots.txt are for the host. The explainer video (#42) is large and
// streamed with range requests, so its files are never precached (nor cached by the fetch handler below).
const skipAsset = (path: string) =>
	/^(og-image\.png|CNAME|robots\.txt)$/.test(path) || path.startsWith('video/');
const VIDEO = resolve('/video/');

const SHELL = [
	...immutable.filter((file) => !skipImmutable(file.path)).map((file) => resolve(file.path)),
	...assets.filter((file) => !skipAsset(file.path)).map((file) => resolve(file.path)),
	...prerendered.map((page) => resolve(page.path))
];
const IMMUTABLE = new Set(immutable.map((file) => resolve(file.path)));

self.addEventListener('install', (event) => {
	event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
});

self.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) =>
				Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))
			)
	);
});

self.addEventListener('fetch', (event) => {
	const url = new URL(event.request.url);
	if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
	if (url.pathname.startsWith(VIDEO)) return;

	async function respond() {
		const cache = await caches.open(CACHE);

		// Hashed build files never change: cache first.
		if (IMMUTABLE.has(url.pathname)) {
			const cached = await cache.match(url.pathname);
			if (cached) return cached;
		}

		// Everything else: network first so new deployments show up, cache as the offline fallback.
		try {
			const response = await fetch(event.request);
			if (response.status === 200 && response.type === 'basic') {
				void cache.put(event.request, response.clone());
			}
			return response;
		} catch (error) {
			const cached = await cache.match(event.request, { ignoreSearch: true });
			if (cached) return cached;
			throw error;
		}
	}

	event.respondWith(respond());
});
