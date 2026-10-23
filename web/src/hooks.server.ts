import type { Handle } from '@sveltejs/kit/hooks';
import { LOCALE_INFO, localeFromPath } from '#lib/i18n/index.js';

// Every page is prerendered: this sets <html lang> (the `%lang%` placeholder in app.html) for each language.
export const handle: Handle = ({ event, resolve }) => {
	const lang = LOCALE_INFO[localeFromPath(event.url.pathname)].htmlLang;
	return resolve(event, {
		transformPageChunk: ({ html }) => html.replace('%lang%', lang)
	});
};
