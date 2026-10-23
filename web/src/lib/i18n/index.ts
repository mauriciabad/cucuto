// The site ships in English (at the root) and Spanish (under /es/), owner decision on issue #70. Every page lives in
// src/routes/[[lang=lang]]/, so one component serves both languages; the copy comes from the dictionaries here.
import { en, type Messages } from './en.js';
import { es } from './es.js';

export const LOCALES = ['en', 'es'] as const;
export type Locale = (typeof LOCALES)[number];
export const DEFAULT_LOCALE: Locale = 'en';

/** Locales that get a path prefix (the default one lives at the root). */
export const PREFIXED_LOCALES: ReadonlyArray<Locale> = ['es'];

export const LOCALE_INFO: Record<Locale, { name: string; htmlLang: string; ogLocale: string }> = {
	en: { name: 'English', htmlLang: 'en', ogLocale: 'en_US' },
	es: { name: 'Español', htmlLang: 'es', ogLocale: 'es_ES' }
};

const dictionaries: Record<Locale, Messages> = { en, es };

export type { Messages };

/** The locale a pathname belongs to: `/es` and `/es/…` are Spanish, everything else English. */
export function localeFromPath(pathname: string): Locale {
	const first = pathname.split('/')[1];
	return PREFIXED_LOCALES.find((locale) => locale === first) ?? DEFAULT_LOCALE;
}

/** The pathname without its locale prefix: `/es/install/` becomes `/install/`. */
export function stripLocale(pathname: string): string {
	const locale = localeFromPath(pathname);
	if (locale === DEFAULT_LOCALE) return pathname;
	const rest = pathname.slice(locale.length + 1);
	return rest === '' ? '/' : rest;
}

/** A root-relative page path (`/install/`) in the given locale (`/es/install/`). */
export function localizePath(path: string, locale: Locale): string {
	const clean = stripLocale(path);
	return locale === DEFAULT_LOCALE ? clean : `/${locale}${clean}`;
}

export function messages(locale: Locale): Messages {
	return dictionaries[locale];
}

/** Fills `{name}` placeholders. Values are inserted as is: only pass trusted, build-time strings. */
export function fill(template: string, values: Record<string, string | number>): string {
	return template.replace(/\{(\w+)\}/g, (match, name: string) =>
		name in values ? String(values[name]) : match
	);
}
