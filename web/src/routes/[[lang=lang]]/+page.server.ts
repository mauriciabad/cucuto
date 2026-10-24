import { DEFAULT_LOCALE, LOCALES } from '#lib/i18n/index.js';
import { explainerVideo } from '#lib/server/explainer-video.js';
import type { PageServerLoad } from './$types';

// The explainer video (#42) is optional: the build looks for its files in static/video/ and Home shows the
// "coming soon" poster until they exist (src/lib/server/explainer-video.ts, docs/explainer-video.md).
export const load: PageServerLoad = ({ params }) => {
	const locale = LOCALES.find((l) => l === params.lang) ?? DEFAULT_LOCALE;
	return { explainer: explainerVideo(locale) };
};
