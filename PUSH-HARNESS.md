# Building the SDK for the push harness

The runnable end-to-end harness lives in [`sample/`](sample/) — its own Gradle build, deliberately
resolving the published AAR from `mavenLocal()` rather than the modules beside it, so it exercises
the SDK exactly the way an integrator does. This file covers the SDK half: producing the artifacts
the harness resolves.

Companion doc:
[`sample/HARNESS.md`](sample/HARNESS.md)
(run the harness).

## Build & publish to mavenLocal

```bash
export JAVA_HOME=/usr/lib/jvm/<jdk>     # the DIRECTORY, not .../bin/java
./gradlew assembleDebug test publishToMavenLocal
```

Publishes `sa.arsel:core` and `sa.arsel:push-fcm` (the `VERSION_NAME` in
`gradle.properties`) to `~/.m2`.

**Re-publish after every SDK change.** The harness resolves the version from `mavenLocal()` and
will silently keep building against a stale AAR otherwise.

## Gotchas

- `local.properties` with `sdk.dir=` is required and gitignored.
- `JAVA_HOME` must point at a JDK **directory** (17+), not the `java` binary.
- The SDK itself needs no `google-services.json` — only the harness app does (Firebase is
  `compileOnly` here by design).

## What to run next

Follow the harness repo's `PUSH-HARNESS.md` from its step 1 — it starts by consuming what you just
published.
