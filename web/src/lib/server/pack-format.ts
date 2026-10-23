// Renders docs/pack-format.md (the human pack format specification) to HTML at build time for
// /authors/pack-format/, so the page and the document can never drift apart. The page is prerendered: marked runs
// only during the build and never ships to the browser.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Marked, Renderer, type Tokens } from 'marked';
import { REPO_URL } from '#lib/site.js';

/** Relative links in the document resolve against its folder on GitHub (schemas, ADRs, packer README). */
const SOURCE_BASE = `${REPO_URL}/blob/main/docs/`;

export interface PackFormatDoc {
	title: string;
	html: string;
	toc: Array<{ id: string; title: string }>;
}

/** GitHub's heading anchors: lowercase, punctuation dropped (except `-` and `_`), spaces become hyphens. */
export function githubSlug(text: string): string {
	return text
		.toLowerCase()
		.trim()
		.replace(/[^\p{L}\p{N}\s_-]/gu, '')
		.replace(/\s/g, '-');
}

const escapeAttribute = (value: string) =>
	value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');

export function renderPackFormat(markdown: string): PackFormatDoc {
	let title = '';
	const toc: PackFormatDoc['toc'] = [];
	const seen = new Map<string, number>();
	const base = new Renderer();

	const marked = new Marked({ gfm: true }).use({
		renderer: {
			heading({ tokens, depth, text }: Tokens.Heading) {
				const plain = text.replace(/[`*]/g, '');
				// The page supplies its own <h1>; the document's title becomes the page subtitle.
				if (depth === 1) {
					title = plain;
					return '';
				}
				const slug = githubSlug(plain);
				const count = seen.get(slug) ?? 0;
				seen.set(slug, count + 1);
				const id = count === 0 ? slug : `${slug}-${count}`;
				if (depth === 2) toc.push({ id, title: plain });
				return `<h${depth} id="${id}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
			},
			link({ href, title: linkTitle, tokens }: Tokens.Link) {
				const label = this.parser.parseInline(tokens);
				const titleAttribute = linkTitle ? ` title="${escapeAttribute(linkTitle)}"` : '';
				if (href.startsWith('#'))
					return `<a href="${escapeAttribute(href)}"${titleAttribute}>${label}</a>`;
				const url = new URL(href, SOURCE_BASE).href;
				return `<a href="${escapeAttribute(url)}" rel="external"${titleAttribute}>${label}</a>`;
			},
			// Wide tables and code scroll sideways in a focusable box (axe: scrollable-region-focusable).
			table(token: Tokens.Table) {
				return `<div class="scroll" tabindex="0">${base.table.call(this, token)}</div>\n`;
			},
			code(token: Tokens.Code) {
				return base.code.call(this, token).replace('<pre>', '<pre tabindex="0">');
			}
		}
	});

	const html = marked.parse(markdown, { async: false });
	return { title, html, toc };
}

/** The document from the repository (the build runs in web/, the document lives in docs/). */
export function packFormatDoc(): PackFormatDoc {
	return renderPackFormat(
		readFileSync(join(process.cwd(), '..', 'docs', 'pack-format.md'), 'utf8')
	);
}
