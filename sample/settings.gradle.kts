pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Arsel SDK is resolved from here after you run, in the repo root:
        //   ./gradlew publishToMavenLocal
        mavenLocal()
    }
}

rootProject.name = "arsel-push-sample-app"
include(":app")
