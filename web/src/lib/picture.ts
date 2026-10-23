/** What a `?picture` import returns (see scripts/picture.ts), rendered by `<Picture>`. */
export interface PictureData {
	width: number;
	height: number;
	/** PNG, for browsers without JPEG XL or AVIF. */
	fallback: string;
	/** Most preferred first: JPEG XL (when cjxl was available at build time), then AVIF. */
	sources: Array<{ type: string; srcset: string }>;
}
