# Changelog

All notable changes to the Arsel Android SDK.

This project follows [Semantic Versioning](https://semver.org/). A breaking change costs every
integrator an app-store release, so the public API and the wire contract are frozen within a major
version: additive changes ship as minor releases, and anything breaking waits for `2.0.0`.

---

## [1.0.0] — 2026-08-17

First public release. Two subsystems in one artifact, deliberately independent of each other.

### Events and identity — `sa.arsel:core`

Works on a handset that has never granted notification permission, has no FCM token, and does not
have Firebase on the classpath at all.

- `Arsel.track(name, properties)` — durable custom events, queued to disk and drained by
  WorkManager, so they survive process death, offline periods and app kills.
- `Arsel.identify(externalId, email, phoneNumber)` — everything tracked beforehand under the
  anonymous identity merges onto the contact it resolves to. `email` and `phoneNumber` are
  shape-checked client-side; a changed `externalId` drops the previous identity's stored email and
  phone rather than carrying them onto a different person.
- An anonymous identity minted at first run and persisted, exposed by `Arsel.getAnonymousId()`.
  Distinct from the installation id, which names the handset — `reset()` rotates the anonymous id so
  a shared device never hands the next user the previous one's history.
- Automatic `arsel.session_start` / `arsel.session_end`, derived from foreground/background
  transitions with no timers. A session ends after 30 minutes away, and the end event is emitted on
  the next foreground backdated to when the app actually left. A user who never returns produces no
  `session_end` — a fabricated end time is worse than a missing one.
- The `arsel.` event-name prefix and the `arsel_*` push data keys are reserved and refused to hosts,
  so a customer's own names can never collide with the SDK's.

### Push notifications — `sa.arsel:push-fcm`

Firebase glue only. `core` never touches a Firebase type, which is what lets the events API run with
no Firebase present.

- Drop-in delivery: `ArselPushService` claims Arsel messages, renders the notification on a
  configured channel, and reports engagement. Hosts running their own `FirebaseMessagingService`
  call `Arsel.isArselData(data)` / `Arsel.handlePushData(...)` instead.
- Engagement reporting distinguishes `DELIVERED`, `DISPLAYED`, `SUPPRESSED` (with a reason),
  `OPENED`, `CLICKED` (action buttons only) and `DISMISSED`.
- Notification actions, deep links, images and per-campaign channel overrides.
- `Arsel.optOut()` — a durable, user-initiated opt-out, distinct from `reset()`, which is logout and
  leaves the device subscribed.
- Registration carries the anonymous id, so a push subscription lands on the same contact the events
  attach to. Backends that already know the signed-in user can bind authoritatively instead, via
  `POST /v1/push/devices` with the installation id.

### Reliability

- **Persist before send; confirm on 2xx.** Requests are queued to disk, removed by id, and the drain
  stops at the first retryable failure to preserve oldest-first ordering.
- Event sends carry an `Idempotency-Key` — the queued request's persisted id, identical across
  retries — closing the duplicate window between a server 2xx and a dequeue that never happened.
- The device secret is issued exactly once on first registration and presented as
  `X-Arsel-Device-Auth` on every subsequent mutation.
- Inbound messages are claimed in two phases, so a render failure releases the claim and FCM's
  redelivery gets another chance rather than suppressing the notification permanently.
- `Retry-After` is honoured on `429`.

### Operability

- `Arsel.diagnostics()` — a snapshot safe to paste into a support ticket: no FCM token, no device
  secret. Registration and subscription state, last response code and path, queue depths, permission
  state, channel importance, and whether Firebase is present.
- No callbacks into the host by design — a bug in a host callback invoked mid-teardown takes down a
  customer's app. Observation is by polling `diagnostics()`.
- Auto Backup and device-transfer exclusion rules for the SDK's preferences, so a restored backup
  never resurrects another device's installation identity.

### Documentation

A `docs/` set covering [events](docs/events.md), [identity](docs/identity.md),
[push notifications](docs/push-notifications.md), the [API reference](docs/api-reference.md),
[troubleshooting](docs/troubleshooting.md), [Play Data safety](docs/data-safety.md),
[migrating from CleverTap](docs/migrating-from-clevertap.md) and the
[wire reference](docs/wire-reference.md).
