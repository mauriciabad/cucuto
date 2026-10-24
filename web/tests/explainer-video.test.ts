/// <reference types="bun" />
// The explainer video slot (#42, ADR 0042): which files the Home page plays, and the draft captions in static/video/
// against the rules of docs/explainer-video.md. Run with `bun run test:unit`.
import { afterAll, describe, expect, test } from 'bun:test';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { explainerVideo, parseVtt, vttCueTexts } from '../src/lib/server/explainer-video.ts';

const staticVideo = join(import.meta.dir, '..', 'static', 'video');
const VTT =
	'WEBVTT\n\ns1\n00:00:00.500 --> 00:00:02.000\n[Ping]\n\n00:02.000 --> 00:04.000 line:90%\nHello <i>there</i>\n& more\n';

const dirs: string[] = [];
function videoDir(files: string[]) {
	const dir = mkdtempSync(join(tmpdir(), 'cucuto-video-'));
	dirs.push(dir);
	for (const file of files) writeFileSync(join(dir, file), file.endsWith('.vtt') ? VTT : '');
	return dir;
}
afterAll(() => dirs.forEach((dir) => rmSync(dir, { recursive: true, force: true })));

describe('explainerVideo', () => {
	test('is null until the English cut exists', () => {
		const dir = videoDir(['explainer.en.vtt', 'explainer.es.vtt', 'explainer.es.webm']);
		expect(explainerVideo('en', dir)).toBeNull();
		// A Spanish cut alone is used on the Spanish page only.
		expect(explainerVideo('es', dir)?.audioLang).toBe('es');
	});

	test('offers AV1 WebM first, then H.264 MP4, with a captions track per language', () => {
		const dir = videoDir([
			'explainer.webm',
			'explainer.mp4',
			'explainer.en.vtt',
			'explainer.es.vtt'
		]);
		const video = explainerVideo('en', dir);
		expect(video?.audioLang).toBe('en');
		expect(video?.sources.map((s) => s.src)).toEqual([
			'/video/explainer.webm',
			'/video/explainer.mp4'
		]);
		expect(video?.sources[0].type).toStartWith('video/webm; codecs="av01');
		expect(video?.sources[1].type).toStartWith('video/mp4; codecs="avc1');
		expect(video?.tracks).toEqual([
			{ src: '/video/explainer.en.vtt', srclang: 'en', label: 'English' },
			{ src: '/video/explainer.es.vtt', srclang: 'es', label: 'Español' }
		]);
		expect(video?.transcript).toEqual(['[Ping]', 'Hello there & more']);
	});

	test('the Spanish page falls back to the English cut, then prefers its own', () => {
		const english = videoDir(['explainer.mp4', 'explainer.es.vtt']);
		const fallback = explainerVideo('es', english);
		expect(fallback?.audioLang).toBe('en');
		expect(fallback?.sources.map((s) => s.src)).toEqual(['/video/explainer.mp4']);
		expect(fallback?.tracks.map((t) => t.srclang)).toEqual(['es']);
		expect(fallback?.transcript).toHaveLength(2);

		const both = videoDir(['explainer.mp4', 'explainer.es.webm', 'explainer.es.mp4']);
		const spanish = explainerVideo('es', both);
		expect(spanish?.audioLang).toBe('es');
		expect(spanish?.sources.map((s) => s.src)).toEqual([
			'/video/explainer.es.webm',
			'/video/explainer.es.mp4'
		]);
		expect(spanish?.transcript).toEqual([]);
	});
});

describe('parseVtt', () => {
	test('reads cue IDs, times with and without hours, and skips NOTE blocks', () => {
		const cues = parseVtt(`WEBVTT\n\nNOTE a comment\nover two lines\n\n${VTT.slice(8)}`);
		expect(cues).toEqual([
			{ id: 's1', start: 0.5, end: 2, lines: ['[Ping]'] },
			{ id: undefined, start: 2, end: 4, lines: ['Hello <i>there</i>', '& more'] }
		]);
		expect(vttCueTexts(VTT)).toEqual(['[Ping]', 'Hello there & more']);
	});

	test('rejects files that are not WebVTT', () => {
		expect(() => parseVtt('1\n00:00:00,500 --> 00:00:02,000\nSRT\n')).toThrow();
	});
});

// The draft captions follow docs/explainer-video.md: SDH, at most 2 lines of 42 characters, 0.8–6 s per cue, inside
// 60 s, and the same cue IDs and times in English and Spanish (both cuts share one timeline).
describe('draft captions in static/video', () => {
	const read = (lang: string) =>
		parseVtt(readFileSync(join(staticVideo, `explainer.${lang}.vtt`), 'utf8'));
	const en = read('en');
	const es = read('es');

	for (const [lang, cues] of [
		['en', en],
		['es', es]
	] as const) {
		test(`${lang}: cues are readable and in order`, () => {
			expect(cues.length).toBeGreaterThan(10);
			let previousEnd = 0;
			for (const cue of cues) {
				expect(cue.id).toMatch(/^s\d+-[a-z-]+$/);
				expect(cue.lines.length).toBeGreaterThanOrEqual(1);
				expect(cue.lines.length).toBeLessThanOrEqual(2);
				for (const line of cue.lines) expect(line.length).toBeLessThanOrEqual(42);
				const duration = cue.end - cue.start;
				expect(duration).toBeGreaterThanOrEqual(0.8 - 0.001);
				expect(duration).toBeLessThanOrEqual(6);
				expect(cue.start).toBeGreaterThanOrEqual(previousEnd - 0.001);
				previousEnd = cue.end;
			}
			expect(previousEnd).toBeLessThanOrEqual(60);
		});
	}

	test('English and Spanish share cue IDs and times', () => {
		expect(es.map(({ id, start, end }) => ({ id, start, end }))).toEqual(
			en.map(({ id, start, end }) => ({ id, start, end }))
		);
	});
});
