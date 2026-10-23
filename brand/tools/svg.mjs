// Small helpers shared by the brand build scripts.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const BRAND_DIR = fileURLToPath(new URL('../', import.meta.url));
export const out = (rel) => `${BRAND_DIR}${rel}`;

export function write(rel, content) {
  const file = out(rel);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, content);
  return file;
}

export const svgDoc = (viewBox, body, { title, width, height, extra = '' } = {}) => {
  const [, , w, h] = viewBox.split(' ').map(Number);
  const size = width ? ` width="${width}" height="${height ?? Math.round((width * h) / w)}"` : '';
  const t = title ? `<title>${title}</title>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}"${size}${title ? ' role="img"' : ''}>${t}${extra}${body}</svg>\n`;
};

export const pathEl = (d, fill, attrs = '') => `<path d="${d}" fill="${fill}"${attrs}/>`;

/** Renders an SVG file to PNG with librsvg. */
export function png(svgRel, pngRel, width, height) {
  const args = ['-w', String(width)];
  if (height) args.push('-h', String(height));
  mkdirSync(dirname(out(pngRel)), { recursive: true });
  execFileSync('rsvg-convert', [...args, out(svgRel), '-o', out(pngRel)]);
  return out(pngRel);
}

/** Lossless JPEG XL copy of a PNG (the project's preferred image format). */
export function jxl(pngRel, jxlRel = pngRel.replace(/\.png$/, '.jxl'), { lossless = true } = {}) {
  const args = lossless ? ['-d', '0', '-e', '9'] : ['-d', '1', '-e', '7'];
  execFileSync('cjxl', [out(pngRel), out(jxlRel), ...args, '--quiet']);
  return out(jxlRel);
}

/** Writes a .ico holding PNG-encoded images (supported by every browser since Windows Vista-era ICO readers). */
export function ico(entries, rel) {
  // entries: [{ size, data: Buffer(png) }]
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  const dir = Buffer.alloc(16 * entries.length);
  let offset = 6 + dir.length;
  entries.forEach((e, i) => {
    const o = i * 16;
    dir.writeUInt8(e.size >= 256 ? 0 : e.size, o);
    dir.writeUInt8(e.size >= 256 ? 0 : e.size, o + 1);
    dir.writeUInt8(0, o + 2);
    dir.writeUInt8(0, o + 3);
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(e.data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += e.data.length;
  });
  return write(rel, Buffer.concat([header, dir, ...entries.map((e) => e.data)]));
}
