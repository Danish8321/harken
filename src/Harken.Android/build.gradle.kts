plugins {
    // Versions live in gradle/libs.versions.toml, with the reasons for the non-obvious
    // ones (ARC-021).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
