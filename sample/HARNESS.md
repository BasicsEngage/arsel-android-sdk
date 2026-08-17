# Running the push test harness

This app is the end-to-end harness for the Arsel Android SDK. Everything on screen is read back
through the SDK's own public surface (`getInstallationId()`, `diagnostics()`), so what you see is
exactly what an integrator can see in the field.

Companion doc:
[`arsel-android-sdk/PUSH-HARNESS.md`](https://github.com/BasicsEngage/arsel-android-sdk/blob/main/PUSH-HARNESS.md)
(build the SDK).

---

## 1. Publish the SDK first

The app resolves `sa.arsel:push-fcm` from `mavenLocal()`. In the SDK repo:

```bash
./gradlew publishToMavenLocal
```

Re-run this after **every** SDK change, or the app silently keeps building against the previous AAR.

## 2. Local config (both gitignored)

`local.properties`:

```properties
sdk.dir=/home/you/Android/sdk
```

`app/src/<flavor>/google-services.json` — from **your** Firebase project. Firebase console → Add
project → Add Android app → download. **The package name must match the flavor's applicationId
exactly**, suffix included, or the build fails with *"No matching client found for package name"*:

| Flavor | applicationId to register | File goes at |
| --- | --- | --- |
| `staging` | `com.example.arselsample.staging` | `app/src/staging/google-services.json` |
| `prod` | `com.example.arselsample` | `app/src/prod/google-services.json` |

Use **two separate Firebase projects**, not one project with two Android apps. The backend sends via
`projects/<projectId>/messages:send` authenticated by the uploaded service account, and a service
account authorizes sending to its *entire* project — so a shared project would let staging push to
prod-registered handsets. Both can live under one Google account; FCM sending is free.

> Never put a `google-services.json` at `app/` — the plugin falls back to it for **every** flavor,
> which is exactly the cross-environment sharing the split prevents.

## 3. Point it at a backend

The backend is chosen by **picking a variant**, not by editing a URL at runtime. Both flavors are
declared in `app/build.gradle.kts`; each carries its own base URL and client key:

| Flavor | Backend | Use for |
| --- | --- | --- |
| `staging` | the test/sandbox environment Arsel gives you | day-to-day testing |
| `prod` | `https://api.arsel.sa` | a final check against production, once provisioned |

Fill in `ARSEL_BASE_URL` (staging) and `ARSEL_CLIENT_KEY` for the flavor you are using: the opaque
`pub_…` publishable key from the org's push app, never a raw org id. It ships inside every
integrator's APK by design, so it belongs in tracked source. **Point it at a dedicated test org** —
never a real customer's. Push must be enabled for the org — create a push app in your Arsel
dashboard, or contact Arsel to enable it.

Why variants rather than a runtime switch: `ArselStore` keeps `installationId` and `deviceSecret`
in one SharedPreferences file, so one install retargeted at another backend would
present a `deviceSecret` that backend never issued. The `applicationIdSuffix` gives each flavor its
own storage, and both install side by side as "Arsel Sample (staging)" and "Arsel Sample (prod)".

**`prod` does not build until you provision it** — it needs its own `google-services.json`, its
org's `pub_…` key, and push enabled for that org.

`ArselConfig` enforces HTTPS, so a local backend at `http://10.0.2.2:8076` would additionally need
a `networkSecurityConfig` cleartext exception. There is no `local` flavor; the staging flavor is the
path of least resistance.

## 4. Build and install

```bash
export JAVA_HOME=/usr/lib/jvm/<your-jdk>     # directory, not .../bin/java
./gradlew :app:installStagingDebug
```

Use the **variant-specific** task. Bare `assembleDebug` / `build` covers every variant and so fails
on `prod` until it is provisioned. The APK, if you want it directly, is at
`app/build/outputs/apk/staging/debug/app-staging-debug.apk`.

**The device or emulator must have Google Play Services.** FCM cannot work without it. An
`android-35/default` system image does **not** have it — use
`system-images;android-35;google_apis_playstore;x86_64`, or a physical handset.

## 5. What to exercise, in order

### The events API — do this first, on a fresh install

Deliberately before anything push-related, because none of it needs push. **Do not grant
notification permission yet.**

1. **`track()`.** Type an event name and an optional property, press *track()*. `events=` in the
   diagnostics log line goes up, then back to zero when the queue drains. In the Arsel dashboard the
   event appears against an anonymous contact — one with no email and no phone, identified only by
   the anonymous id shown on screen.
2. **The merge proof.** Enter an external ID and press *Run the merge proof*. It tracks an event,
   identifies, then tracks another. **Both events must end up on one contact**, keyed by your
   external ID, and the anonymous contact must be gone. This is the only end-to-end proof that an
   anonymous history survives identification — if it regresses, every pre-login event a customer
   collects is silently orphaned.
3. **Confirm no subscription exists.** `enablementStatus` should still read `NOT_DETERMINED`, and
   the contact should show no push subscription. A contact with a real event history and no way to
   push to them is the correct shape, not a bug.
4. **`reset()`, then track again.** The anonymous ID on screen must change, and the new event must
   land on a *different* contact. This is what stops a shared handset handing the next user the
   previous one's history.

### The push API

5. **FCM token appears** at the top. If it doesn't, `google-services.json` is wrong or Play Services
   is missing — nothing else in this section will work until it does.
6. **`registerNow()`** → diagnostics should flip to `registered=true`, then `deviceSecret: held`
   once the queue drains. `flushNow()` drains immediately instead of on WorkManager's schedule.
7. **Confirm the contact binding.** The registration carries the anonymous id, so the subscription
   lands on the same contact the events built. `identified=true` confirms the backend resolved one.
   To assert the binding from a server instead, call `POST /v1/push/devices` with the
   installation id shown at the top and your secret API key.
8. **Permission.** Request it, and watch the harness report the result back to the backend.
9. **Send a campaign** from the dashboard to that contact. The notification should render, and
   `delivered` / `displayed` / `opened` should appear in the campaign's push analytics.

### Two ways to bind a device to a contact

| | Who asserts it | Trust |
| --- | --- | --- |
| `identify(externalId = …)` in the app | the **app** | A claim, resolved through the identifier ladder. Right for a value your own app already knows. |
| `POST /v1/push/devices` from your server | your **backend** | Authoritative, and it overrides the anonymous binding. Needs the secret API key. |

The harness only does the first: it has no backend of its own, and **the org's secret API key must
never be compiled into an APK.** Do not "fix" that by adding a secret-key call here.

## 6. Reading `diagnostics()`

| Field | Means |
| --- | --- |
| `registered` | A register has been enqueued for the current device state. |
| `identified` | The backend resolved this device to a contact — it is addressable by campaign. |
| `deviceSecret` | `MISSING` means every mutation is being refused; registration has not landed. |
| `queueDepth` | Persistently non-zero → the network or the contract is broken. Check `lastResponse`. |
| `anonymousId` | The identity events carry before login. Must change after `reset()`. |
| `hasAssertedIdentity` | `identify()` has supplied at least one identifier. Local state; `identified` is what the backend confirmed. |
| `eventQueueDepth` | The events half of `queueDepth`. Broken out because the two fail independently — push can be fine while every event bounces off a bad client key. |
| `lastResponse` | Status + path of the last drained request. `400` is a contract error, `404` on a mutation is a rejected device secret. |

## 7. Expected behaviours that look like bugs

| Symptom | Actually |
| --- | --- |
| `reset()` leaves the device receiving the old contact's campaigns | `reset()` rotates the identity for *future* events; it does not unbind the existing push subscription. Use `optOut()` when the user asks to stop entirely. |
| After `optOut()`, register returns but nothing arrives | Correct. Opt-out is durable and is never resurrected by registration. |
| Engagements accepted (`202`) but no analytics rows | An engagement with no base `sent` record is parked, not counted. |
| Engagement counts appear, attributed revenue is zero | The org's push app has no signing secret, so `arsel_sig` cannot be verified and only `valid` signatures count for attribution. |
| Second `delivered` for the same message does nothing | Deduped on `(messageId, eventType, subscriptionId)`. |
| An event name starting `arsel.` is ignored | Reserved for the SDK's own events so customer names can never collide. |
| No `arsel.session_end` after backgrounding the app | Correct. It is emitted on the *next* foreground, backdated to when the app left — there is no timer. A user who never returns produces none. |
| Rotating the screen does not start a new session | Correct, and deliberate: a rotation briefly drops to zero activities, and billing a session for it would inflate every count. |
| Events flow with `enablementStatus: NOT_DETERMINED` | Correct. The events API authenticates with the publishable key and never needs a device secret. |
