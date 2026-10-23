# Google Sans Flex

`android/app/src/main/res/font/google_sans_flex.ttf` is a subset of
[Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) (SIL Open Font License 1.1, see `OFL.txt`),
the brand typeface (`brand/README.md#typography`).

- Source: `ofl/googlesansflex/GoogleSansFlex[ROND,opsz,slnt,wdth,wght].ttf` from
  [google/fonts](https://github.com/google/fonts) at commit `bd8f81ddb5c74d5c8897b36ad88b440266245103`.
- Subset with HarfBuzz 14.6.0 to Latin, Latin Extended, punctuation, euro and arrows, keeping the `ROND`, `opsz`
  and `wght` axes and pinning `slnt=0`, `wdth=100` (3.9 MB to 645 KB):

```
hb-subset 'GoogleSansFlex[ROND,opsz,slnt,wdth,wght].ttf' \
  --unicodes=0000-024F,2000-206F,20AC,2190-21FF --layout-features='*' \
  --variations="slnt=0,wdth=100" --output-file=google_sans_flex.ttf
```
