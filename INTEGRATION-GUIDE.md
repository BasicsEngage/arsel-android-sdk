# Arsel Android SDK — Build, Publish & Integrate

Two audiences: **(A)** Arsel engineers building/publishing the SDK, and **(B)** client integrators (or
our test app) adding the published SDK to their app. A complete, runnable example lives in the separate
repo **[`sample/`](sample/)** (use it
as a test sandbox — no production app required).

Deeper references: [Events](docs/events.md) · [Identity](docs/identity.md) ·
[Push notifications](docs/push-notifications.md) · [API reference](docs/api-reference.md) ·
[Troubleshooting](docs/troubleshooting.md) · [Data safety](docs/data-safety.md) ·
[Migrating from CleverTap](docs/migrating-from-clevertap.md).

---

## A. Build & publish the SDK (Arsel)

The SDK is a standard Gradle Android library (`core` + `push-fcm`). Pick a channel:

### A1. Local testing — Maven Local (fastest)
```bash
./gradlew publishToMavenLocal
```
Publishes `sa.arsel:core` + `sa.arsel:push-fcm` (the `VERSION_NAME` in
`gradle.properties`) to `~/.m2`. Consumers
add `mavenLocal()` and the dependency. This is what `sample/` uses.

**Re-publish after every SDK change**, or the sample app silently builds against the stale AAR.

### A2. Early external access — JitPack (zero infra)
Push this repo to GitHub, tag a release; consumers add `maven("https://jitpack.io")` and
`com.github.<org>:<repo>:<tag>`. Good for design partners before Central is set up. (Klaviyo ships via JitPack.)

### A3. Production — Maven Central
Publish via the Central Portal (e.g. the `vanniktech maven-publish` plugin: POM, sources/javadoc jars,
GPG signing, staging). The group (`sa.arsel`) must be **domain-verified** — use a domain you own
(`arsel.sa` → `sa.arsel`, or `com.basicsengage`), or the `io.github.<user>` fallback. Bump `VERSION_NAME`
in `gradle.properties`.

> **Stable since 1.0.** The public API and the wire contract are frozen within a major version — a
> breaking change costs every integrator an app-store release, so it waits for `2.0.0`. See
> [CHANGELOG.md](CHANGELOG.md). Still open: binary-compatibility validation in CI, so the freeze is
> enforced mechanically rather than by review.

---

## B. Integrate the SDK (client / host app)

### B1. Prerequisites (the client owns Firebase — Model A)
- A **Firebase project**; an Android app added to it with the client's package name.
- `google-services.json` in the app module; the `com.google.gms.google-services` Gradle plugin.
- For iOS reach the client configures their **APNs key inside their own Firebase project** (Android needs only FCM).

### B2. Add the repository + dependencies
`settings.gradle.kts` → add the source repo (`mavenLocal()`, JitPack, or Central). Then in `app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
dependencies {
    implementation("sa.arsel:push-fcm:1.0.0")               // pulls core transitively
    implementation(platform("com.google.firebase:firebase-bom:<ver>"))
    implementation("com.google.firebase:firebase-messaging")    // host provides Firebase (SDK uses it compileOnly)
}
```
The FCM service, the notification tap Activity, and `POST_NOTIFICATIONS` + `INTERNET` are merged in from
the SDK's manifests — the host adds nothing to its manifest for the drop-in case.

### B3. Initialize (Application.onCreate)
```kotlin
Arsel.initialize(
    this,
    ArselConfig.Builder(clientKey = BuildConfig.ARSEL_CLIENT_KEY, baseUrl = "https://api.arsel.sa")
        .defaultChannel("arsel_default", "Notifications")
        .smallIcon(R.drawable.ic_stat_notify) // a white status-bar icon
        .build(),
)
```

### B4. Track events

Events need no push token, no notification permission and no Firebase. They ride their own route,
authenticated by the same `clientKey` you passed to `initialize()` — a publishable `pub_…` value that
is safe in an APK, unlike your secret API key, which must never be compiled into one.

```kotlin
Arsel.track("product.viewed", mapOf("sku" to "A-1023", "price" to 149.99))
```

Delivery is durable: events are persisted and drained by WorkManager, so they survive being offline,
the app being killed, and the process dying. Property values are sent as-is for strings, numbers and
booleans; anything else is stringified rather than dropped.

Names beginning `arsel.` are reserved for the SDK's own events and are ignored.

The SDK emits `arsel.session_start` and `arsel.session_end` automatically. A session ends after 30
minutes in the background, but the end event is emitted on the *next* foreground, backdated to when
the app actually left — so a user who never comes back produces no `session_end`.

### B5. Identify the user (on login)

```kotlin
Arsel.identify(externalId = myUser.id)
```

Everything tracked before this call, under the anonymous identity, is merged onto the contact this
resolves to. Prefer `externalId` alone: it binds the contact without shipping the user's email
address through the app, and it is the one identifier that does not change under them. `email` and
`phoneNumber` are accepted too, and identifiers are remembered and ride every later event.

The push subscription follows along on its own: registration sends the anonymous id, so the device
resolves to the same contact its events attach to, and `identify()` merges that contact forward.

If you would rather assert the binding from your own backend, read `Arsel.getInstallationId()`, send
it to your server, and have it call Arsel's `POST /v1/push/devices` with your secret API key.
That binding is authoritative and overrides the anonymous one.

**Two ids, and they are not interchangeable:**

| | Names | Survives logout |
| --- | --- | --- |
| `getInstallationId()` | the **handset** | yes — it is the same device |
| `getAnonymousId()` | the **person** using it | no — `reset()` rotates it |

Never use the installation id as a user identifier. On a shared handset it would hand the next
person the previous one's history.

**Logout vs opt-out — these are not the same call:**
```kotlin
Arsel.reset()    // logout: clears the contact, keeps the device subscribed
Arsel.optOut()   // the user asked to stop: DURABLE, not resurrected by re-registration
```
Calling `optOut()` on logout would make the device permanently unreachable — signing back in would
never restore push.

### B6. Android 13+ permission (host decides when to prompt)
```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted? */ }
Arsel.requestNotificationPermission(launcher)   // after a primer/rationale
```

### B7. Host that already has its own FirebaseMessagingService
Remove ours (`tools:node="remove"` on `sa.arsel.push.fcm.ArselPushService`) and forward:
```kotlin
override fun onNewToken(t: String) { Arsel.setPushToken(t) }
override fun onMessageReceived(m: RemoteMessage) {
    if (m.isArselMessage) Arsel.handlePushData(applicationContext, m.data) else { /* your push */ }
}
```

---

## C. Test it end to end

See **[`sample/HARNESS.md`](sample/HARNESS.md)**
(and its README) for the full sandbox walkthrough: publish the SDK to mavenLocal, run the sample on
a Google-Play emulator, register against your Arsel org, send a push from the dashboard, and watch
it render + tap and engagement back — the harness exercises the real end-to-end path.

For the identity API, the sample's **merge proof** runs track → identify → track → flush and shows
the anonymous history landing on the identified contact.

When something doesn't work, [Troubleshooting](docs/troubleshooting.md) is organised by symptom.
