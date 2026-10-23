# 0001. Android build setup and versions

- Status: accepted
- Date: 2026-10-23
- Issue: #2

## Context

The brief fixes Kotlin only, Android 17 only (`minSdk = targetSdk = 37`, newest 37.x `compileSdk`), one APK holding
the app and the libxposed API 102 module, Material 3 Expressive and the newest stable versions. The project is
maintained mostly by agents, so module configuration must live in one place and builds must be fast and cacheable.

## Decision

**Toolchain** (newest stable on 2026-10-23, checked on developer.android.com, Google Maven, Maven Central and
services.gradle.org):

| Area | Version | Notes |
| --- | --- | --- |
| Gradle | 9.8.2 | Wrapper with SHA-256 pinned; configuration cache (parallel) and build cache on |
| Android Gradle Plugin | 9.4.1 | AGP 9 defaults kept: built-in Kotlin, new DSL, no opt-out flags |
| Kotlin | 2.4.21 | Upgrades AGP's built-in Kotlin (KGP floor 2.2.10) through the build-logic classpath |
| KSP | 2.3.12 | KSP2, versioned independently of Kotlin |
| JDK | 21 | Java toolchain for all modules and the Gradle daemon (`gradle/gradle-daemon-jvm.properties`, auto-provisioned with foojay) |
| SDK | compile 37.2, min 37, target 37, build tools 37.0.0 | `compileSdk { version = release(37) { minorApiLevel = 2 } }` |
| Compose BOM | 2026.09.00 | Compose 1.12.1 stable |
| Compose Material 3 | 1.5.0-beta02 | The one allowed pre-release (Expressive); works on Compose 1.12 |
| Navigation 3 | 1.2.0 | With `lifecycle-viewmodel-navigation3` 2.11.0 |
| Hilt | 2.60.1 (androidx.hilt 1.4.0) | KSP |
| Room | 3.0.3 (`androidx.room3`) | Room 3 is the current major: KSP only, coroutines first, SQLiteDriver based |
| DataStore | 1.3.0 | |
| kotlinx.serialization / coroutines | 1.12.0 / 1.11.0 | |
| Coil 3 / OkHttp | 3.6.3 / 5.5.1 | |
| libxposed API | 102.0.0 (Maven Central `io.github.libxposed:api`) | `compileOnly` |
| Tests | JUnit 4.13.2, Turbine 1.2.1, kotlinx-coroutines-test | Wired into every module by the convention plugins |
| Style | Spotless 8.10.4 + ktlint 1.8.0 (`ktlint_official`) + Compose rules 0.6.8 | Part of `check` |

**Structure.** `android/build-logic` is an included build with convention plugins (`cucuto.android.application`,
`.library`, `.compose`, `.hilt`, `.room`, `cucuto.kotlin.serialization`, `cucuto.spotless`). Plugins are put on the
classpath as `implementation` dependencies of build-logic, so the root build declares no plugin versions and every
module sees one consistent set. Library namespaces derive from the Gradle path (`:core:pack` →
`app.mauri.cucuto.core.pack`). `build` runs debug and release (R8) assembly, Android Lint (`abortOnError`), ktlint
and every module's unit tests.

**Other choices.**

- **ktlint over detekt.** detekt 1.23 is built against Kotlin 2.0 and detekt 2 is still alpha; ktlint 1.8 with the
  Compose rules runs on any Kotlin version and is auto-fixable (`./gradlew spotlessApply`).
- **arm64-v8a only** (`abiFilters`), matching every supported device.
- **Module-wide opt-in** to `ExperimentalMaterial3ExpressiveApi` in `:app`: cucuto is built on Expressive, so
  per-call opt-ins add noise without information.
- **No Android backup**: `dataExtractionRules` exclude everything; cucuto has its own backup file and credentials
  must never leave the device.
- **Baseline profile module deferred** to a follow-up: it needs real user journeys to be worth anything.

## Consequences

- Version bumps happen in `android/gradle/libs.versions.toml` only (plus the Gradle wrapper and the foojay plugin
  version in `settings.gradle.kts`); Dependabot proposes them (see 0004).
- Material 3 is pinned to a beta and must move to 1.5.0 stable when it ships.
- Configuration-cache compatible from day one: build logic must not read project state at execution time.
- Gradle reports one deprecation (`Configuration.setVisible`) coming from a third-party plugin, not from our build
  logic; it disappears when that plugin updates.
