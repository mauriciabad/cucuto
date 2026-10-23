import { defineParams } from '@sveltejs/kit/params';

// `[[lang=lang]]`: the optional locale prefix. English lives at the root, so only the prefixed locales match.
// SvelteKit loads this file outside Vite during the build, so it can't import #lib aliases: keep the list in sync
// with PREFIXED_LOCALES in src/lib/i18n/index.ts.
const PREFIXED = ['es'] as const;

export const params = defineParams({
	lang(param): (typeof PREFIXED)[number] | undefined {
		return PREFIXED.find((locale) => locale === param);
	}
});
