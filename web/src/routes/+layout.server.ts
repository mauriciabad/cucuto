import { appRelease } from '#lib/server/build-data.js';
import type { LayoutServerLoad } from './$types';

// Read once at build time (prerender) and shared by every page: the Download APK buttons need it.
export const load: LayoutServerLoad = () => ({ appRelease: appRelease() });
