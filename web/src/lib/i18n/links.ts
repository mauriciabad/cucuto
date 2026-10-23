import { resolve } from '$app/paths';
import {
	ISSUES_URL,
	LICENSE_URL,
	PACKER_README_URL,
	PACK_FORMAT_URL,
	RELEASES_URL,
	REPO_URL
} from '#lib/site.js';
import { localizePath, type Locale } from './index.js';

/** The site's pages, as root-relative paths in the default locale. */
export type PagePath =
	'/' | '/packs/' | '/install/' | '/authors/' | '/authors/pack-format/' | '/privacy/' | '/open/';

/** A link to one of the site's pages in the given locale, with the base path applied. */
export function pageHref(path: PagePath, locale: Locale): string {
	// `resolve` takes a pathname without its leading slash ('es/install/', '' for the root) and applies the base
	// path. Its generated types can't express "a page path with an optional locale prefix", hence the cast.
	return (resolve as (pathname: string) => string)(localizePath(path, locale).slice(1));
}

/**
 * Every link the copy in the dictionaries may use as a `{placeholder}` inside `…Html` strings: the site's pages in
 * the current locale plus the project's external pages.
 */
export function linkValues(locale: Locale): Record<string, string> {
	return {
		home: pageHref('/', locale),
		packs: pageHref('/packs/', locale),
		install: pageHref('/install/', locale),
		authors: pageHref('/authors/', locale),
		packFormat: pageHref('/authors/pack-format/', locale),
		privacy: pageHref('/privacy/', locale),
		repo: REPO_URL,
		issues: ISSUES_URL,
		releases: RELEASES_URL,
		license: LICENSE_URL,
		packFormatSource: PACK_FORMAT_URL,
		packerReadme: PACKER_README_URL
	};
}
