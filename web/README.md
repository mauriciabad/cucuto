# Cucuto website

The static site at [cucuto.mauri.app](https://cucuto.mauri.app): SvelteKit 3 (Svelte 5 runes), TypeScript, Vite 8,
`adapter-static` with every route prerendered, deployed to GitHub Pages by `.github/workflows/web.yml`.

```sh
pnpm install
pnpm dev          # http://localhost:5173
pnpm check        # svelte-check (types, a11y warnings fail the run)
pnpm lint         # Prettier + ESLint (flat config)
pnpm build        # static site in build/
pnpm test         # Playwright smoke + axe tests against the built site (pnpm exec playwright install chromium first)
SCREENSHOTS=1 pnpm exec playwright test screenshots   # full-page screenshots in test-results/screenshots/
```

## Notes

- **Brand.** Tokens, logos, icons, the OG image and the door-open animation are copies of files in `brand/`. Rebuild
  the brand, then run `pnpm sync:brand` and commit the result. `colors.css` and `typography.css` are not formatted by
  Prettier so the copies stay identical.
- **Fonts.** Google Sans Flex and Google Sans Code are self-hosted with Fontsource. The site loads the `rond.css` cut
  (weight and ROND axes, about 70 kB for Latin) instead of `full.css` (all axes, 1.4 MB for Latin): optical size is the
  only axis we give up.
- **No third-party requests.** A Content Security Policy (`vite.config.ts`) only allows our own origin; Playwright
  fails on any console error, so a CSP violation breaks the build.
- **URLs.** `trailingSlash: 'always'`: pages are written as `/install/index.html`, the form GitHub Pages serves
  natively. Link with `resolve('install/')` from `$app/paths`.
- **404.** `adapter-static` writes the SPA fallback to `404.html`, which GitHub Pages serves for unknown paths.
- **PWA.** `static/manifest.webmanifest` plus `src/service-worker/index.ts`, which precaches the app shell and every
  prerendered page. The manifest's `file_handlers` entry opens `.soundpack` files on `/open/`, a stub until the pack
  creator (#48).
- **Motion.** `src/lib/styles/motion.css` turns the brand's spring tokens into CSS `linear()` easings. Everything
  respects `prefers-reduced-motion`.
- **TypeScript 6.** TypeScript 7 is out, but SvelteKit 3, svelte-check and typescript-eslint still declare
  `typescript@^6` as their peer, so the site stays on 6.x until they support 7.
