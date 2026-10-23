// Fetches what the site needs from GitHub Releases before `vite build` (and `vite dev`):
//
// - The latest app release (tag `v*`, not a draft or prerelease, with an `.apk` asset): version, APK URL and size,
//   written to `.build-data/app-release.json` (`null` when there is no app release yet: the site says "coming soon").
// - The demo pack repository: `repository.zip` from the newest `packs-v*` release (see
//   .github/workflows/packs-release.yml), unpacked into `static/repo/` and served as `/repo/index.json` (plus
//   `/repo/repository.json`) and `/repo/previews/...`. Without a packs release the index is valid but empty.
//
// Never fails the build: on network errors it keeps the previous data (or writes the fallbacks) and warns.
// Environment: GITHUB_TOKEN (optional, raises the API rate limit), GITHUB_REPOSITORY (default
// mauriciabad/cucuto), CUCUTO_OFFLINE=1 (skip the network), CUCUTO_REPO_DIR=path (use a local `packer index`
// output folder instead of the release, for testing packer changes).
import { unzipSync } from 'fflate';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, normalize, sep } from 'node:path';

const web = join(import.meta.dirname, '..');
const dataDir = join(web, '.build-data');
const repoDir = join(web, 'static', 'repo');
const repository = process.env.GITHUB_REPOSITORY || 'mauriciabad/cucuto';
const offline = process.env.CUCUTO_OFFLINE === '1';

interface Asset {
	id: number;
	name: string;
	size: number;
	updated_at: string;
	browser_download_url: string;
}
interface Release {
	tag_name: string;
	name: string | null;
	html_url: string;
	draft: boolean;
	prerelease: boolean;
	published_at: string | null;
	assets: Asset[];
}

const EMPTY_INDEX = {
	formatVersion: 1,
	name: 'Cucuto demo packs',
	description: 'Free CC0 packs made for Cucuto.',
	homepage: 'https://cucuto.mauri.app',
	packs: []
};

function headers(): Record<string, string> {
	const h: Record<string, string> = {
		accept: 'application/vnd.github+json',
		'x-github-api-version': '2022-11-28',
		'user-agent': 'cucuto-web-build'
	};
	if (process.env.GITHUB_TOKEN) h.authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
	return h;
}

async function fetchReleases(): Promise<Release[]> {
	const response = await fetch(`https://api.github.com/repos/${repository}/releases?per_page=100`, {
		headers: headers(),
		signal: AbortSignal.timeout(15_000)
	});
	if (!response.ok) throw new Error(`GitHub API: HTTP ${response.status}`);
	return (await response.json()) as Release[];
}

function writeJson(file: string, value: unknown) {
	mkdirSync(dirname(file), { recursive: true });
	writeFileSync(file, JSON.stringify(value, null, '\t') + '\n');
}

function appRelease(releases: Release[]) {
	for (const release of releases) {
		if (release.draft || release.prerelease || !/^v\d/.test(release.tag_name)) continue;
		const apks = release.assets.filter((a) => a.name.endsWith('.apk'));
		const apk = apks.find((a) => !/debug/i.test(a.name)) ?? apks[0];
		if (!apk) continue;
		return {
			version: release.tag_name.replace(/^v/, ''),
			tag: release.tag_name,
			releaseUrl: release.html_url,
			publishedAt: release.published_at,
			apk: { name: apk.name, url: apk.browser_download_url, size: apk.size }
		};
	}
	return null;
}

/** Unpacks a repository.zip into static/repo, refusing paths that escape it. */
function unpackRepository(zip: Uint8Array, marker: string) {
	rmSync(repoDir, { recursive: true, force: true });
	for (const [name, data] of Object.entries(unzipSync(zip))) {
		if (name.endsWith('/')) continue;
		const target = normalize(join(repoDir, name));
		if (!target.startsWith(repoDir + sep))
			throw new Error(`Unsafe path in repository.zip: ${name}`);
		mkdirSync(dirname(target), { recursive: true });
		writeFileSync(target, data);
	}
	finishRepository(marker);
}

/** `packer index` writes repository.json; the site's documented URL is /repo/index.json. Serve both. */
function finishRepository(marker: string) {
	const index = join(repoDir, 'repository.json');
	if (!existsSync(index)) throw new Error('repository.json missing');
	cpSync(index, join(repoDir, 'index.json'));
	writeFileSync(join(dataDir, 'repo-source'), marker + '\n');
}

function ensureRepository() {
	if (existsSync(join(repoDir, 'index.json'))) return;
	rmSync(repoDir, { recursive: true, force: true });
	writeJson(join(repoDir, 'repository.json'), EMPTY_INDEX);
	finishRepository('empty');
}

async function packsRepository(releases: Release[]) {
	const release = releases.find(
		(r) =>
			!r.draft &&
			r.tag_name.startsWith('packs-v') &&
			r.assets.some((a) => a.name === 'repository.zip')
	);
	if (!release) {
		console.warn('build-data: no packs-v* release with repository.zip yet, serving an empty index');
		rmSync(repoDir, { recursive: true, force: true });
		ensureRepository();
		return;
	}
	const asset = release.assets.find((a) => a.name === 'repository.zip')!;
	const marker = `${release.tag_name} ${asset.id} ${asset.updated_at}`;
	const sourceFile = join(dataDir, 'repo-source');
	if (existsSync(sourceFile) && readFileSync(sourceFile, 'utf8').trim() === marker) {
		console.log(`build-data: pack repository from ${release.tag_name} (cached)`);
		return;
	}
	const response = await fetch(asset.browser_download_url, {
		headers: { 'user-agent': 'cucuto-web-build' },
		signal: AbortSignal.timeout(60_000)
	});
	if (!response.ok) throw new Error(`repository.zip: HTTP ${response.status}`);
	unpackRepository(new Uint8Array(await response.arrayBuffer()), marker);
	console.log(`build-data: pack repository from ${release.tag_name}`);
}

mkdirSync(dataDir, { recursive: true });
const releaseFile = join(dataDir, 'app-release.json');

if (process.env.CUCUTO_REPO_DIR) {
	rmSync(repoDir, { recursive: true, force: true });
	cpSync(process.env.CUCUTO_REPO_DIR, repoDir, { recursive: true });
	finishRepository(`local ${process.env.CUCUTO_REPO_DIR}`);
	console.log(`build-data: pack repository from ${process.env.CUCUTO_REPO_DIR}`);
}

let releases: Release[] | undefined;
if (!offline) {
	try {
		releases = await fetchReleases();
	} catch (error) {
		console.warn(`build-data: could not list releases (${error}); keeping the previous data`);
	}
}

if (releases) {
	const app = appRelease(releases);
	writeJson(releaseFile, app);
	console.log(
		app
			? `build-data: app release ${app.version}`
			: 'build-data: no app release yet ("coming soon")'
	);
	if (!process.env.CUCUTO_REPO_DIR) {
		try {
			await packsRepository(releases);
		} catch (error) {
			console.warn(`build-data: could not fetch the pack repository (${error})`);
		}
	}
}
if (!existsSync(releaseFile)) writeJson(releaseFile, null);
ensureRepository();
