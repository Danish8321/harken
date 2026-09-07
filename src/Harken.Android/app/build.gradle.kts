import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // kotlin.android is gone: AGP 9's built-in Kotlin support replaces it (ARC-044).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Everything the build needs to know about which build this is (ARC-022).
 */
fun properties(name: String): Properties = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use { load(it) }
}

val versionProperties = properties("version.properties")

/**
 * The short commit this APK was built from, or "unknown" outside a git checkout.
 *
 * Emitted in the launch telemetry, which is the point: "1.0" on every build a user has
 * ever installed means a bug report cannot be tied to the code that produced it.
 */
val gitSha: String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() && !it.contains(" ") } ?: "unknown"

/**
 * The commit count, so versionCode rises on its own.
 *
 * A hand-edited number is a number nobody edits, and Play refuses an upload whose
 * versionCode has not gone up. Falls back to version.properties for a build from a source
 * archive, which has no history to count.
 */
val gitCommitCount: Int = runCatching {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim().toInt()
}.getOrNull() ?: versionProperties.getProperty("versionCodeFallback", "1").toInt()

val keystoreProperties = properties("keystore.properties")

// room-migration:2.8.4 generates its FieldBundle serializer against kotlinx-serialization
// 1.8.1 (its own direct dependency line says so) but ships a stale 1.7.3 "strictly" BOM
// constraint bundled in the same release that downgrades it right back — the two disagree
// within Room's own published metadata. Loading the 1.7.3 GeneratedSerializer interface
// against a class generated for 1.8.1 is an AbstractMethodError on
// typeParametersSerializers() at runtime (see MigrationTestHelper's connectedAndroidTest
// failure, and Google Issue Tracker 400483860). Forcing back to what Room itself asked for
// undoes Room's own downgrade; no fix is out from Room as of 2.8.4, the latest stable.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
        )
    }
}

android {
    namespace = "com.harken.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.harken.android"
        // Foreground service microphone type needs API 26+ (Service.startForeground with
        // a type); AudioRecord/notification-action Stop button work fine from there too.
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = versionProperties.getProperty("versionName", "0.0.0")
        resValue("string", "app_name", "Harken")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // Single ABI for now — matches minSdk 26+modern-device assumption, avoids
        // multi-ABI native build time while on-device transcription is unproven
        // (ADR-0011).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests {
            // android.util.Log now called from several catch blocks (error-surfacing pass);
            // plain JVM unit tests hit the unmocked stub otherwise, which throws instead of
            // no-op-ing, aborting the coroutine before it reaches failLocal/onError.
            isReturnDefaultValues = true
        }
    }

    // Present only when keystore.properties is: assembleRelease on a machine with no
    // signing key still runs, still exercises R8 and resource shrinking, and produces an
    // unsigned APK. Refusing to build there would put the release gate out of reach of
    // every machine but one.
    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // 46.6 MB of dex over three files without this, most of it Compose and
            // material-icons-extended that the app never references. 3.0 MB with it,
            // in one dex file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Distinct package so the debug build installs alongside a already-installed
            // release Harken instead of replacing it — its DataStore/Room live separately too.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Harken Debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For GIT_SHA. Off by default since AGP 8, and the launch telemetry needs it to
        // say which build a report came from.
        buildConfig = true
        // The debug build's "Harken Debug" app_name override needs this explicitly on
        // AGP 9 (ARC-044) — it used to be on by default.
        resValues = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
            // MigrationTestHelper opens the exported schema JSON as a test asset. Without
            // this it cannot see them and every migration test fails at "Cannot find the
            // schema file", which reads like a missing migration and is not one.
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

// Room writes the database's shape to JSON on every build (ARC-039). Two things depend on
// it: MigrationTestHelper builds a real old-version database from it rather than from a
// CREATE TABLE typed out by hand, and the committed files are the only record of what
// each shipped version actually looked like.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
