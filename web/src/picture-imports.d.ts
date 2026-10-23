// `import icon from './icon.png?picture'` or `'./icon.png?w=192&picture'` (see scripts/picture.ts).
declare module '*?picture' {
	const picture: import('#lib/picture.js').PictureData;
	export default picture;
}

declare module '*&picture' {
	const picture: import('#lib/picture.js').PictureData;
	export default picture;
}
