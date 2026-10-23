// @material/material-color-utilities 0.4.0 ships ESM files whose relative imports lack the ".js" extension,
// which Node refuses to resolve. This resolve hook adds the extension, then re-exports the package.
import { registerHooks } from 'node:module';

registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      if (error?.code === 'ERR_MODULE_NOT_FOUND' && specifier.startsWith('.') && !specifier.endsWith('.js')) {
        return nextResolve(`${specifier}.js`, context);
      }
      throw error;
    }
  },
});

export const mcu = await import('@material/material-color-utilities');
