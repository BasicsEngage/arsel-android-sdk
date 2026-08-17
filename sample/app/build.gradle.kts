plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services) // requires app/google-services.json (see README)
}

android {
    namespace = "com.example.arselsample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.arselsample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    // The backend is chosen by picking a variant, never at runtime: ArselStore keeps
    // installationId and deviceSecret in one SharedPreferences file, so a single install
    // retargeted at another backend would present a deviceSecret that backend never issued. The
    // applicationIdSuffix is what makes the two installs independent.
    flavorDimensions += "backend"

    productFlavors {
        create("staging") {
            dimension = "backend"
            applicationIdSuffix = ".staging"
            resValue("string", "app_name", "Arsel Sample (staging)")
            // Point this at the test/sandbox environment Arsel gives you. Must be HTTPS.
            buildConfigField("String", "ARSEL_BASE_URL", "\"REPLACE_WITH_STAGING_BASE_URL\"")
            // The test org's publishable pub_ key (see HARNESS.md).
            buildConfigField("String", "ARSEL_CLIENT_KEY", "\"REPLACE_WITH_STAGING_CLIENT_KEY\"")
        }
        create("prod") {
            dimension = "backend"
            resValue("string", "app_name", "Arsel Sample (prod)")
            buildConfigField("String", "ARSEL_BASE_URL", "\"https://api.arsel.sa\"")
            // This variant does not build until app/src/prod/google-services.json exists; set the
            // org's pub_ key before using it, and treat changing it as a reviewed change.
            buildConfigField("String", "ARSEL_CLIENT_KEY", "\"REPLACE_WITH_PROD_CLIENT_KEY\"")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The Arsel SDK — resolved from mavenLocal(), never project(":push-fcm"): this build must
    // exercise the packaged artifact. Publish from the repo root first.
    // Pulls core transitively.
    implementation(libs.arsel.push.fcm)

    // Firebase — the HOST app provides it (the SDK depends on firebase-messaging as compileOnly).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
}
