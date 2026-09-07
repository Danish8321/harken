# ARC-044 — compileSdk 36 ceiling holds back 18 Lint version notices

- **Severity:** low
- **Status:** done
- **Area:** `app/build.gradle.kts`, `gradle/libs.versions.toml`

## Problem

Lint's `GradleDependency`/`NewerVersionAvailable`/`AndroidGradlePluginVersion`
checks name 18 libraries with a newer release available. Every one of them is
out of reach for the same reason: their 2026 builds ship AAR metadata requiring
`compileSdk 37`, and AGP 8.12 recommends no higher than `compileSdk 36`. Bumping
any one of them individually fails `checkDebugAarMetadata` — this was tried and
reverted while closing [ARC-037](ARC-037-lint-warnings-formatter-and-ci.md).

The ceiling is documented at the top of `gradle/libs.versions.toml`.

## The fix

One job, in order, not eighteen small ones:

1. AGP 8.12 -> 9.x.
2. `compileSdk` 36 -> 37 (and `targetSdk` alongside it if 37 is also the
   latest stable at the time).
3. Re-run the androidx/Kotlin/room/lifecycle/etc. bumps Lint has been naming —
   most should resolve cleanly once the ceiling moves.

Re-run `check.sh` and `test-full.sh` (device required — Room's migration test)
after each step, since AGP major bumps have broken `lintVitalRelease` here
before (see the comment on `agp` in `libs.versions.toml`).

## Deliberately not bundled with ARC-037

ARC-037 closed with these 18 named and this ticket filed, rather than staying
open for a ceiling only a toolchain bump moves — see that ticket's "The version
notices are a ceiling, not a backlog" section.

## Resolution, 2026-09-07

Went further than "ceiling only": every Lint-named bump was applied in the
same pass (Kotlin, ktlint-plugin, okhttp, coroutines-test included), not just
the 13 the compileSdk ceiling was directly blocking.

Final versions: AGP `9.4.0`, Gradle `9.6.0`, Kotlin `2.4.10`, KSP `2.3.11`,
`compileSdk`/`targetSdk` `37` (Android 17, "Cinnamon Bun"), compose-bom
`2026.08.00`, material3 `1.4.0`, room `2.8.4`, lifecycle `2.11.0`,
activity-compose `1.13.0`, navigation-compose `2.10.0`, core-ktx `1.19.0`,
datastore `1.2.1`, okhttp `5.5.0`, ktlint-plugin `14.2.0`, coroutines-test
`1.11.0`.

Five configuration-time errors, each fixed by reading the real Gradle error
and applying the minimal fix for it, in order:

1. AGP 8.12 does not run under Gradle 9.6.0 (`InternalProblems` internal API
   removed) — bumped `agp` to `9.4.0` in the same pass as the wrapper bump.
2. `org.jetbrains.kotlin.android` plugin is a hard configuration error
   alongside AGP 9's built-in Kotlin support — removed the plugin alias from
   `libs.versions.toml`, the root `build.gradle.kts`, and `app/build.gradle.kts`.
3. `kotlinOptions { jvmTarget = "17" }` no longer resolves — that DSL belonged
   to the removed plugin. Deleted the block; `compileOptions` already declared
   Java 17, so nothing replaced it.
4. AGP 9 disables `resValues` by default, breaking the debug variant's
   `resValue("string", "app_name", "Harken Debug")` override — added
   `buildFeatures { resValues = true }`.
5. `checkDebugAarMetadata`/`checkReleaseAarMetadata` named ~14 dependencies
   requiring `compileSdk 37` — bumped `compileSdk`/`targetSdk` to `37`, which
   is what this ticket was filed to do.

`ktlint-plugin` 14.2.0 added a chain-wrapping style rule that flagged existing
code; fixed by running `ktlintFormat` (mechanical, 35 files reformatted, no
logic changes) rather than hand-editing each one.

`ktlintCheck`/`lintDebug` were clean after all of the above, but
`connectedDebugAndroidTest` failed: `SessionDatabaseMigrationTest` threw
`AbstractMethodError` on `GeneratedSerializer.typeParametersSerializers()` on
`FieldBundle$$serializer`. Root cause, found by reading
`./gradlew app:dependencies --configuration debugAndroidTestRuntimeClasspath`:
`room-migration:2.8.4` itself directly depends on
`kotlinx-serialization-json:1.8.1`, but the same release also bundles a
`strictly 1.7.3` constraint that downgrades it right back — Room's own
published metadata disagrees with itself (matches
[Google Issue Tracker 400483860](https://issuetracker.google.com/issues/400483860)
and [Kotlin/kotlinx.serialization#2968](https://github.com/Kotlin/kotlinx.serialization/issues/2968)).
2.8.4 is still the latest stable Room release (checked
developer.android.com/jetpack/androidx/releases/room) — no upstream fix yet.
Worked around in `app/build.gradle.kts` with a `resolutionStrategy.force(...)`
back to `1.8.1` (what Room itself asked for) for
`kotlinx-serialization-{core,core-jvm,json,json-jvm}`. Verified with a
targeted `connectedDebugAndroidTest` run against just that class (3/3 passed),
then the full suite (9/9 passed).

Lint: 19 warnings before this ticket -> 3 after (`AndroidGradlePluginVersion`
naming Gradle 9.7.1, `NewerVersionAvailable` naming Kotlin 2.4.20,
`ChromeOsAbiSupport` — a deliberate single-ABI choice, ADR-0011). All 13 of
the compileSdk-blocked `GradleDependency`/`AndroidGradlePluginVersion` notices
this ticket was filed for are gone. Also fixed one incidental new warning
Lint surfaced under the bumped toolchain: `SessionSheetViewModel.onCleared()`
called `super.onCleared()`, which `ViewModel` defines empty (`EmptySuperCall`).

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (device
`eece2e35`, 9/9 instrumented tests, migration suite included).
