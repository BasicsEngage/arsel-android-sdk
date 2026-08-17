import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "sa.arsel.push.fcm"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    /** Same rationale as core — see the lint block there. */
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

/** Production source only — see the same block in core for why tests are exempt. */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// Coordinates and POM come from gradle.properties (GROUP/VERSION_NAME + POM_*).
mavenPublishing {
    publishToMavenCentral()
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))
    // Signing is required by Central but must not break local/CI builds that have no key:
    // only wire it when the release pipeline provides the in-memory key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}

dependencies {
    api(project(":core"))

    // WRAPPER, NOT BUNDLER: compile against Firebase; the HOST app provides it at runtime
    // (their google-services.json + the google-services plugin + firebase-bom + firebase-messaging).
    // compileOnly => we never bundle Firebase nor pin the host's Firebase version. (Plan §3.3.)
    compileOnly(libs.firebase.messaging)

    // App Startup: wire the FCM bridge before Application.onCreate (so initialize() can fetch the token).
    implementation(libs.androidx.startup)
}
