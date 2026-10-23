// Fixed brand colors for the logo, icon and illustrations. They come from the generated tonal palettes
// (brand/tokens/colors.json) except "sunny", a pure accent kept outside the UI color system.
export const BRAND = {
  tangerine: '#FF6B2C', // seed
  rust: '#A83900', // primary 40
  walnut: '#321200', // neutral 10
  night: '#1E0800', // neutral 4 = dark background
  cream: '#FFF1EB', // neutral 96
  apricot: '#FFB59A', // primary 80
  peach: '#FFE3D4', // neutral 92 = light surfaceContainer
  sunny: '#FFD25E', // accent: the bird's beak
};

/** Color sets for the layered mark. */
export const MARK_COLORS = {
  // Default: works on white, cream and dark backgrounds.
  full: { house: BRAND.tangerine, door: BRAND.rust, opening: BRAND.walnut, head: BRAND.cream, beak: BRAND.sunny, eye: BRAND.walnut },
  // On a tangerine (or other saturated brand) background.
  onColor: { house: BRAND.cream, door: '#FF9A6C', opening: BRAND.walnut, head: BRAND.tangerine, beak: BRAND.rust, eye: BRAND.walnut },
};
