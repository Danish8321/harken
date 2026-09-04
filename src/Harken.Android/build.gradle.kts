plugins {
    // 8.12+ rather than 8.7.3: lifecycle resolves to 2.9.0 through the Compose BOM, and that
    // release's lint checks are compiled against the Kotlin 2.1 analysis API. AGP 8.7's
    // bundled lint carries the older one, so lintVitalRelease crashed with
    // "Found class …KaCallableMemberCall, but interface was expected" and no release build
    // could be produced at all.
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
