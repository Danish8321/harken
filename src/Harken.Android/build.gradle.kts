plugins {
    // Versions live in gradle/libs.versions.toml, with the reasons for the non-obvious
    // ones (ARC-021).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
}

// One formatter, applied to every module, so "correctly formatted" is a fact the build
// states rather than a habit each file records differently (ARC-037). ktlintCheck is
// wired into check.sh; ktlintFormat is the fix.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // Room's KSP output is Kotlin and lands under build/. It is not ours to format,
        // and formatting it would mean re-formatting on every schema change.
        filter {
            exclude { it.file.path.startsWith(layout.buildDirectory.get().asFile.path) }
        }
    }
}
