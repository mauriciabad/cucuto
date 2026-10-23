// Fully static site: every route is prerendered at build time (adapter-static).
export const prerender = true;

// GitHub Pages serves `/install/index.html` for `/install/` and redirects `/install` there, so directory-style URLs
// are the canonical form and never depend on the host resolving extension-less `.html` files.
export const trailingSlash = 'always';
