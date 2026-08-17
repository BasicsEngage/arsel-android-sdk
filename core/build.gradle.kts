import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "sa.arsel.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${findProperty("VERSION_NAME")}\"")
    }

    buildFeatures {
        buildConfig = true
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

    testOptions {
        // The mockable android.jar throws "Stub!" from every method body. Defaulting instead keeps
        // an incidental android.util.Log call from failing a test that is about something else —
        // org.json is NOT covered by this, which is why the real implementation is a test dep.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // NewApi is why this runs at all. minSdk 23 compiled against SDK 35 means every framework
        // call has to be either available on 23 or behind a Build.VERSION guard, and the unit tests
        // cannot prove that — they run against the mockable android.jar, which has no API levels.
        // This is the cheap, device-free check that the version guards are actually all there.
        warningsAsErrors = true
        abortOnError = true

        // Every `commit()` in ArselStore is deliberate: each writes a value whose loss IS the bug
        // the field exists to prevent (see the comment at each call site).
        disable += "ApplySharedPref"
        // Registry is the process-scoped singleton and stores applicationContext only, which is
        // the shape lint cannot distinguish from a leaked Activity.
        disable += "StaticFieldLeak"
        // A library pins its dependencies low on purpose — the floor is the contract with the host
        // app, so "a newer version is available" is never a defect here.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

/**
 * Lock the public API surface — never leak an internal symbol — but only on production source.
 * `kotlinOptions.freeCompilerArgs` applies to *every* Kotlin compilation in the module, and under
 * strict explicit-API mode each JUnit test class and each `@Test fun` becomes a compile error for
 * a missing `public` modifier. Scoping it here keeps the guarantee where it means something.
 */
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx) // NotificationCompat / NotificationManagerCompat
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity) // ActivityResult permission API
    implementation(libs.androidx.work.runtime) // durable offline request queue (survives process death)

    // JVM unit tests. No Robolectric on purpose: the logic worth pinning down (wire parsing, body
    // shapes, queue semantics, retry classification) is pure, and the Android edges it does touch
    // are reached through seams a hand-written fake covers. org.json must precede the mockable
    // android.jar on the classpath, which is the order Gradle produces here.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
