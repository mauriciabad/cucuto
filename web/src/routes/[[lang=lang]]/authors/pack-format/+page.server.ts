import { packFormatDoc } from '#lib/server/pack-format.js';
import type { PageServerLoad } from './$types';

// docs/pack-format.md rendered at build time (prerendered, like every page).
export const load: PageServerLoad = () => ({ doc: packFormatDoc() });
