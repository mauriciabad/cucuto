// The explainer video on Home (#42, ADR 0042). The files are optional: the site looks for them in static/video/ at
// build time and shows the "coming soon" poster until the English cut exists. The brief for the video, with the file
// names and encoding settings, is docs/explainer-video.md.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { DEFAULT_LOCALE, LOCALES, LOCALE_INFO, type Locale } from '#lib/i18n/index.js';

export interface ExplainerVideo {
	/** Language of the cut's voice-over (and app UI). */
	audioLang: Locale;
	/** Most preferred first: AV1 in WebM, then H.264 in MP4. */
	sources: Array<{ src: string; type: string }>;
	/** One captions track per language that has a file. */
	tracks: Array<{ src: string; srclang: Locale; label: string }>;
	/** The captions in the page's language as plain text, one entry per cue, for the transcript. */
	transcript: string[];
}

// Codec strings for the encodes in docs/explainer-video.md (AV1 Main 10-bit level 4.1, H.264 High 4.2), so browsers
// that can't decode AV1 skip straight to the MP4.
const FORMATS = [
	{ ext: 'webm', type: 'video/webm; codecs="av01.0.09M.10, opus"' },
	{ ext: 'mp4', type: 'video/mp4; codecs="avc1.64002A, mp4a.40.2"' }
] as const;

export const VIDEO_DIR = join(process.cwd(), 'static', 'video');

/** `explainer` for the English cut, `explainer.es` for the Spanish one. */
const cutName = (lang: Locale) => (lang === DEFAULT_LOCALE ? 'explainer' : `explainer.${lang}`);

/**
 * What the Home page in `locale` plays, or `null` while the video doesn't exist. A page uses its own language's cut
 * when there is one, and otherwise the English cut (with captions in its language).
 */
export function explainerVideo(locale: Locale, dir = VIDEO_DIR): ExplainerVideo | null {
	const sourcesFor = (lang: Locale) =>
		FORMATS.filter(({ ext }) => existsSync(join(dir, `${cutName(lang)}.${ext}`))).map(
			({ ext, type }) => ({ src: `/video/${cutName(lang)}.${ext}`, type })
		);

	let audioLang = locale;
	let sources = sourcesFor(locale);
	if (sources.length === 0 && locale !== DEFAULT_LOCALE) {
		audioLang = DEFAULT_LOCALE;
		sources = sourcesFor(DEFAULT_LOCALE);
	}
	if (sources.length === 0) return null;

	const tracks = LOCALES.filter((lang) => existsSync(join(dir, `explainer.${lang}.vtt`))).map(
		(lang) => ({
			src: `/video/explainer.${lang}.vtt`,
			srclang: lang,
			label: LOCALE_INFO[lang].name
		})
	);
	const captions = join(dir, `explainer.${locale}.vtt`);
	const transcript = existsSync(captions) ? vttCueTexts(readFileSync(captions, 'utf8')) : [];
	return { audioLang, sources, tracks, transcript };
}

export interface VttCue {
	id?: string;
	start: number;
	end: number;
	lines: string[];
}

const TIMESTAMP = /^(?:(\d+):)?(\d{2}):(\d{2})\.(\d{3})$/;

function seconds(stamp: string): number {
	const match = TIMESTAMP.exec(stamp);
	if (!match) throw new Error(`Invalid WebVTT timestamp: ${stamp}`);
	const [, h = '0', m, s, ms] = match;
	return Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(ms) / 1000;
}

/**
 * The cues of a WebVTT file (https://www.w3.org/TR/webvtt1/): enough of the format for the site's own captions.
 * Skips the header, NOTE, STYLE and REGION blocks; cue settings after the end time are ignored.
 */
export function parseVtt(source: string): VttCue[] {
	const blocks = source
		.replace(/^\uFEFF/, '')
		.replace(/\r\n?/g, '\n')
		.split(/\n{2,}/);
	if (!/^WEBVTT(?:[ \t]|$)/.test(blocks[0] ?? '')) throw new Error('Not a WebVTT file');
	const cues: VttCue[] = [];
	for (const block of blocks.slice(1)) {
		const lines = block.split('\n').filter((line) => line.trim() !== '');
		if (lines.length === 0 || /^(NOTE|STYLE|REGION)(?:[ \t]|$)/.test(lines[0])) continue;
		const timingAt = lines[0].includes('-->') ? 0 : 1;
		const timing = lines[timingAt]?.split('-->');
		if (!timing || timing.length !== 2) throw new Error(`Invalid WebVTT cue: ${lines[0]}`);
		cues.push({
			id: timingAt === 1 ? lines[0] : undefined,
			start: seconds(timing[0].trim()),
			end: seconds(timing[1].trim().split(/\s+/)[0]),
			lines: lines.slice(timingAt + 1)
		});
	}
	return cues;
}

/** Each cue's text on one line, without markup: the transcript. */
export function vttCueTexts(source: string): string[] {
	return parseVtt(source).map((cue) =>
		cue.lines
			.join(' ')
			.replace(/<[^>]*>/g, '')
			.replace(/&lt;/g, '<')
			.replace(/&gt;/g, '>')
			.replace(/&nbsp;/g, ' ')
			.replace(/&amp;/g, '&')
			.trim()
	);
}
