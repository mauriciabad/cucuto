# 0036. App languages: English and Spanish, kept complete by the build

- Status: accepted
- Date: 2026-10-24
- Issue: #107

## Context

The owner answered on #70: "multilingual, use english and spanish, and for the app too." The website already ships
both languages (#38, defaults in #109: Spain Spanish with informal "tú", URLs under `/es/`). Several agents add
screens and strings in parallel, so a Spanish translation done once would drift within days.

[Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages) (Android 13+) let
people pick the app's language in the system settings, separately from the phone's language. AGP builds the
`LocaleConfig` from the resource folders when `generateLocaleConfig` is on.

## Decision

- **Languages.** English is the default (`values/`, declared as `unqualifiedResLocale=en` in
  `res/resources.properties`). Spanish lives in `values-es/`, one file per English file (`strings.xml`,
  `strings_chat.xml`, ...). It is Spain Spanish with "tú", in the same voice as the website (`web/src/lib/i18n/es.ts`)
  and `brand/README.md`. Without a region qualifier, it also serves Latin American Spanish.
- **Shared terms** with the website's install guide: *Diagnóstico*, *Enviar notificación de prueba*, *interruptor de
  voces*, *ámbito* (LSPosed scope), *voz*, *personaje*, *pack*, *colección* (bundle), *gorjeo* (the self-test chirp).
  The list is at the top of `values-es/strings.xml`.
- **Per-app language** the official way: `androidResources.generateLocaleConfig = true` in `:app`. AGP writes the
  locale list (`en`, `es`) and adds `android:localeConfig` to the manifest, so Cucuto appears under Settings ›
  System › Languages › App languages. `localeFilters = en, es` keeps library translations in other languages out
  of the APK, so a dialog from a library never shows a third language.
- **The build keeps Spanish complete**, in both directions:
  - Lint (`./gradlew lint`, the `android-lint` CI job) treats `MissingTranslation`, `ExtraTranslation`,
    `MissingQuantity` and the `StringFormat*` checks as errors (`build-logic` `KotlinAndroid.kt`, every Android
    module).
  - `LocaleParityTest` (`:app` unit tests, the `android-test` CI job) scans every `src/*/res/values` folder in the
    Android project. It fails when a string, plural or array has no Spanish entry, when Spanish keeps one that
    English dropped, when the placeholders differ, or when a Spanish plural lacks `one`, `many` or `other`.
    `translatable="false"` strings (the app name, WhatsApp's product names) must stay English-only.
  - `SpanishScreensTest` (Robolectric, native graphics, `es` at 360 dp) renders Home, the character picker and the
    status card, and fails if any text is clipped or ellipsized.
- **Spanish plurals** list `one`, `many` and `other`. CLDR uses `many` for round millions ("1 000 000 de packs"),
  so its text equals `other`.

## Language row in Settings

Settings has a **Language** section (`ui/settings/LanguageSection.kt`) with one row, "App language". Its summary
is the language picked for Cucuto, named in itself ("Español"), or "Same as the phone". Tapping it opens Android's
own page (`Settings.ACTION_APP_LOCALE_SETTINGS` for `package:app.mauri.cucuto`), the documented place for the
choice. That page keeps the "System default" entry and syncs with Android's App languages list. Choosing a language
recreates the activity in that language.

If an in-app picker is ever wanted instead, set
`context.getSystemService(LocaleManager::class.java).applicationLocales` to `LocaleList.forLanguageTags("es")`, or
to `LocaleList.getEmptyLocaleList()` for the system default. No AppCompat is needed, because `minSdk` is 37.

## Consequences

- Every user-visible string needs English and Spanish in the same PR (`CLAUDE.md`). An agent that adds an English
  string without its Spanish twin gets a red `check` and a red `lint`.
- Design-gallery strings (debug only) are translated too, so the rule stays simple. Preview data and the
  Diagnostics decision log's raw values (package names, URIs, hook names) stay as they are.
- New languages need only a `values-xx/` folder, an entry in `localeFilters` and an update to the parity test's
  locale list.
