# Identity

How a handset becomes a person, and what happens to the history it built before it had a name.

## Three ids, and they are not interchangeable

| | Names | Survives logout | Getter |
| --- | --- | --- | --- |
| `installationId` | the **handset** | **yes** — it is the same device | `getInstallationId()` |
| `anonymousId` | the **person** using it | no — `reset()` rotates it | `getAnonymousId()` |
| `contactId` | the Arsel contact | n/a | server-side only |

The installation id survives logout on purpose: it is still the same device, and its push
subscription is still valid. **Never use it as a user identifier.** On a shared or resold handset,
the next person would inherit the previous one's history and, worse, their notifications. Every "why
did my colleague get my order confirmation" bug is this one.

`reset()` keeps them apart for you: it rotates the anonymous id and leaves the installation
registered and receiving.

## Anonymous first

The first time `initialize()` runs, the SDK mints a UUID and persists it. Every event carries it,
including after you identify — it is what the backend merges *from*.

```
first launch          →  anonymous_id: 9f2c…            (a contact exists already)
track("viewed")       →  anonymous_id: 9f2c…
track("added")        →  anonymous_id: 9f2c…
identify(ext = u_42)  →  anonymous_id: 9f2c…, external_id: u_42
                         ↳ the history merges onto the contact known as u_42
track("purchased")    →  anonymous_id: 9f2c…, external_id: u_42
```

An anonymous user is a real contact from the first event, not a placeholder. That is what makes
"abandoned cart for someone who never signed in" an addressable audience.

## Identifying — `identify()`

```kotlin
Arsel.identify(externalId = user.id)
Arsel.identify(email = user.email)
Arsel.identify(externalId = user.id, email = user.email, phoneNumber = user.phone)
```

Right when the value is one your app already knows. Call it **once per login**, not per event — the
identifiers are persisted and ride every later event.

`email` and `phoneNumber` are shape-checked client-side (basic email shape; E.164 for phone). An
invalid value is rejected with a logged error rather than stored — stored, it would 400 every later
event. And identifying a **different** `externalId` drops the previously stored email and phone:
they belonged to the previous user.

Emits `arsel.identify` immediately rather than waiting for your next `track()`: the merge is what you
asked for, and deferring it leaves the two contacts split until something unrelated happens to fire.

### How the push subscription finds the same contact

Registration sends the `anonymousId`, so the device resolves through the identifier ladder below
exactly as an event does. A device that registers before the user logs in still lands on the contact
its events attach to, and `identify()` afterwards merges that contact forward — no separate call, no
token round-trip.

When your backend already knows who this is at login and you want the binding asserted server-side
instead, read `getInstallationId()`, send it to your backend, and have it call
`POST /v1/push/devices` with your secret API key. That path is authoritative and overrides the
anonymous binding.

> **Never ship your secret API key in the APK.** Anyone can unzip an APK. The `pub_…` client key in
> your config is publishable and safe; the secret key belongs on your server only.

## Prefer `externalId`

Three reasons, in order of how much they cost you later:

1. **It doesn't change.** People change email addresses and phone numbers. Your own primary key
   doesn't. Every mutable identifier is a future duplicate contact.
2. **It ranks highest** of the client-assertable identifiers, so it wins every merge decision.
3. **It keeps PII out of the client.** `identify(email = …)` ships an address through the app and
   over the wire on every event.

When migrating, use **your own** user id — the primary key in your database — not your previous
vendor's id. Yours is the one you can look up, join on, and re-assert from a server-side import.

## The identifier ladder

| Rank | Identifier | Assertable from the app |
| --- | --- | --- |
| 1 | `contactId` | no — server-side only |
| 2 | `externalId` | yes |
| 3 | `email` | yes |
| 4 | `phoneNumber` | yes |
| 5 | `anonymousId` | yes |

Rank decides which contact wins when several match, and which one absorbs the other.

## What happens on a merge

**Nothing matches** → a contact is created carrying every identifier you sent.

**One matches** → that contact is used, and any identifier it doesn't already hold is **written onto
it** — never over an existing value. A contact created from an email import picks up its
`externalId` the first time the app identifies with one, and stops being a duplicate waiting to
happen.

**Several match, and the weaker one is recognized by nothing stronger than the identifier that
matched it** → the weaker contact is merged into the stronger. Its events, list memberships and
properties move.

**Several match, and the weaker one is *also* known by something stronger** → **no merge**, and the
conflict is logged. The event attaches to the highest-ranked match.

That last rule is what keeps identity from silently corrupting itself:

```
Contact A:  externalId u_42,  email sara@example.com
Contact B:  externalId u_99,  email sara@work.example.com

identify(externalId = "u_42", email = "sara@work.example.com")
```

`externalId` matches A, `email` matches B. B has its own `externalId`, so it isn't "just an email
address that happens to match" — merging would destroy a separately-identified person. The event goes
to A, B is untouched, and the conflict is recorded rather than resolved by guesswork.

**Merges do not run backwards.** Once two contacts are one, splitting them is not an operation.

## Logout vs opt-out

```kotlin
Arsel.reset()    // logout
Arsel.optOut()   // the user asked to stop receiving notifications
```

These are not the same call and cannot be substituted for each other.

| | `reset()` | `optOut()` |
| --- | --- | --- |
| Contact binding | cleared | untouched |
| Anonymous id | **rotated** | untouched |
| Device subscription | **kept** | durably removed |
| Reversible by a later registration | n/a | **no** — deliberately |

The backend's opt-out is durable and non-resurrectable: registration re-runs on every launch, so a
revocation those calls could undo would be no opt-out at all. Calling `optOut()` on logout therefore
leaves the handset **unreachable for the life of the install** — the same user signing back in would
not receive push on that device again. Only a reinstall starts it over, and only when the contact
still has another active device to keep them subscribed.

## Identity across devices

Two handsets identified with the same `externalId` resolve to the same contact. Each one's anonymous
history merges onto it as it identifies. There is no cross-device linking before that — two
anonymous installs are two people until one of them says otherwise.

## Common mistakes

| Mistake | What it causes |
| --- | --- |
| `identify()` on every screen | Harmless but pointless — identifiers persist |
| Using `installationId` as the user id | Shared-device history and notification leaks |
| `optOut()` on logout | The handset is unreachable for the life of the install |
| Never calling `reset()` | On a shared device, one contact accumulates several people |
| Identifying with your old vendor's id | You inherit a key you cannot join on or re-assert |
| Shipping the secret API key in the APK | Anyone can unzip an APK; use the `pub_…` client key |
