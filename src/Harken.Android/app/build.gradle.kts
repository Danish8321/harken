plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.harken.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.harken.android"
        // Foreground service microphone type needs API 26+ (Service.startForeground with
        // a type); AudioRecord/notification-action Stop button work fine from there too.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        resValue("string", "app_name", "Harken")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Material 3 Expressive (MaterialExpressiveTheme, MotionScheme, ButtonGroup,
    // SplitButton, LoadingIndicator) needs material3 1.4 — the 2025.09 BOM still pins
    // 1.3.2, so this version is pinned above the BOM's constraint until a BOM train
    // carries 1.4 as its default.
    implementation("androidx.compose.material3:material3:1.4.0")
    // RoundedPolygon / Morph — the record button's Circle -> Cookie morph and the
    // shape-sequence loading indicator are both built on this.
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Full local mirror of sessions/segments/summaries plus local title+tag overrides.
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
