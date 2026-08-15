# Review — the money and the login, 2026-08-15

A pass over the two paths where a defect costs something that cannot be re-earned by playing
better: the ad-free purchase, and getting into an account. Everything below marked **Fixed**
is done and building — `:app:testDebugUnitTest` passes (59 tests, 14 of them new) and
`:app:compileReleaseKotlin` is clean.

A second pass the same day re-verified findings 1–4 against the committed code, confirmed
one claim this document had been taking on faith (that the billing pass budget can actually
interrupt a lost Play callback — it can: billing-ktx 9.1.0 implements its suspend extensions
on `CompletableDeferred.await()`, which is cancellable), found finding 5, and wrote the
mockwebserver tests the first pass left as the top item on the open list.

Scope: `BillingManager`, `EntitlementStore`, `AdsManager`, `ConsentManager` and the purchase
UI; `SupabaseClient`, `SessionStore`, `AuthRepository`, the auth deep link, Google sign-in
through Credential Manager, and the RLS/grant story behind them.

What this review could **not** do is exercise either path against the live services: the
purchase flow still has never run against real Play (technical plan §5, 🔴), and nothing here
was tested against the live Supabase project. Everything below is source-level, and the two
server-side claims it makes are re-derived from the migrations rather than re-run.

---

## Fixed

### 1. A 401 never actually refreshed the session — **Fixed**

`SupabaseClient.request` retries an authorized request once after a 401, which is the whole
recovery mechanism for a token the server will not take. It called `refreshSession()`, and
`refreshSession()` opens with

```kotlin
if (existing.expiresAtMs - 60_000L > System.currentTimeMillis()) return existing
```

— so whenever the *stored* expiry had not yet passed, it handed back the very token that had
just been rejected and the "retry" was a guaranteed second 401. The refresh token was never
spent. That expiry check is right for the proactive path it was written for (refresh ahead of
expiry) and exactly wrong for the reactive one, because disagreeing with the stored expiry is
the only reason a 401 arrives at all.

The cases that produce one: the project's JWT secret rotated, the device clock moved
backwards between sessions (the expiry is absolute device-epoch time, written when the token
was received), or the session was ended server-side. In every one of them the refresh token is
still good and the session is recoverable — and `AuthRepository.refreshProfile` reads the
resulting 401 as proof it is not, on a comment that says "has already survived one refresh
attempt inside the client". It had not survived one; there had not been one. A JWT secret
rotation would have signed out every installed copy on its next launch.

`refreshSession` now takes the rejected token: passing it switches the guard from "refresh if
it looks expired" to "refresh unless someone else already replaced this exact token", which is
still the double-check the lock needs, just the right question for the caller.
([SupabaseClient.kt](../app/src/main/java/tech/idct/whaaack/data/SupabaseClient.kt))

### 2. An expired reset link did nothing at all — **Fixed**

Supabase answers a password-reset or confirmation link in one of two shapes. On success the
fragment carries `access_token` / `refresh_token`; on failure it carries no tokens whatsoever,
only `error`, `error_code` and `error_description` — which is what a player gets when the link
has aged out, has been used already, or was superseded by requesting a second reset.

`handleAuthDeepLink` read `params["access_token"] ?: return`. So the player tapped the link in
their inbox, watched Whaaack! come to the front, and was shown **nothing** — no message, no
navigation, no error. There is no way to tell from inside the app that the link was at fault
rather than the app, and the obvious next move is to tap the same dead link again. This is the
end of the only self-service route back into a locked-out account; the next step after it is
the website contact form.

The giveaway was already in the code: the fragment decoder carried a comment about
`error_description` reading as gibberish without it, and nothing has ever read
`error_description`.

Parsing now lives in a pure `parseAuthFragment` returning `Tokens` / `Failed` / `Ignored`
([AuthLink.kt](../app/src/main/java/tech/idct/whaaack/data/AuthLink.kt)), the ViewModel
branches on it, and a failure raises a toast that names the cause and the way out — for
`otp_expired`, that the newest email is the one that works, since requesting another reset is
itself a way of killing the first link. A failure deliberately does **not** navigate: the link
foregrounds the app whatever it was doing, and pulling somebody out of a live run to show them
a dead link is worse than the toast. Half a token pair is treated the same way rather than
returning silently. Nine tests in
[AuthLinkTest](../app/src/test/java/tech/idct/whaaack/data/AuthLinkTest.kt), which also closes
one of the four items in the technical plan's untested-modules list.

### 3. An account change with no session reported success — **Fixed**

`updateDisplayName` began `val session = client.currentSession() ?: return`. `runAuth` counts
a block that returns without throwing as a success: it ticks `actionSucceeded`, which closes
the sheet, and `changeDisplayName` then toasts "Display name updated". Nothing had been sent
anywhere. A player whose refresh token had been rejected — the client drops the session on a
400–403, quietly — could rename themselves as often as they liked and be congratulated every
time.

`updateEmail`, `updatePassword` and `deleteAccount` had the opposite half of the same problem:
no check at all, and `SupabaseClient` falls back to the anon key when an authorized request
has no token. PostgREST answers that with an empty row set rather than a 401, so a signed-out
PATCH is a successful change of nothing; GoTrue and the RPC answer with errors phrased for a
developer.

All four now go through `requireSession()`, which raises a new `AuthError.SessionExpired`
("You're signed out — sign in again, then make the change") and drops `_player`, so the
account card on screen stops describing somebody who is not signed in. Distinct from
`NeedsRecentSignIn`, which is a session that exists and is merely too old for one operation.
([AuthRepository.kt](../app/src/main/java/tech/idct/whaaack/data/AuthRepository.kt))

### 4. A lost Play callback wedged every later entitlement check — **Fixed**

Every await in an entitlement pass is a callback from the Play Store app —
`startConnection`, `queryProductDetailsAsync`, `queryPurchasesAsync` — and none of them was
bounded. A callback that never arrives has nowhere to be noticed: the pass parks holding the
`gate` mutex, every later pass queues behind it for the life of the process, and
`restoringPurchases` never clears, so **Restore purchases** keeps its spinner and refuses
further taps. That is the one control a player reaches for when they have paid and the app
disagrees, so the failure lands exactly where it can do most damage.

A pass now runs under a 45-second budget and a timeout concludes `Check.Unknown`, which is the
answer this class already knows how to give: grants nothing, revokes nothing, leaves the
stored entitlement standing, and tells the player Play could not be reached. The budget is
deliberately far above any legitimate pass — restoring against an unreachable Play takes the
better part of ten seconds, and an unacknowledged purchase adds a retry loop with six seconds
of backoff in it — because this is a deadlock breaker, not a latency budget. Cancelling a pass
mid-flight is safe in both directions: a grant writes `_adsRemoved` before it touches disk and
Play re-reports the purchase next pass, and an interrupted acknowledgement is retried by every
later pass for the three days Play allows.
([BillingManager.kt](../app/src/main/java/tech/idct/whaaack/billing/BillingManager.kt))

### 5. A transient token-endpoint failure destroyed the session — **Fixed**

Found on the second pass, in the fix for finding 1, while writing the test that finding said
it deserved. `refreshSession` answered *any* failure of the refresh POST with null. For a
400–403 that is right — the server has looked at the refresh token and said no, the session
is over, and the store is cleared to agree. But a 503 from an outage, or a 429 from the rate
limit Supabase applies to `/auth/v1/token`, got the same null — and null makes `request`
rethrow the original 401, which `refreshProfile` reads (correctly, now) as a session that has
already survived a refresh attempt and cannot be saved. It responds by **clearing the stored
session**. One bad moment for the token endpoint, and a player whose refresh token was
perfectly good is signed out for keeps — the very outcome finding 1 existed to prevent,
arriving through the servers' good door instead of the bad one.

The same null also fed the anon-key fallback: an account change attempted while the stored
token was expired and the token endpoint was down went out with the anon key, PostgREST
answered the PATCH with an empty row set, and "Display name updated" was toasted over a
server that changed nothing — finding 3's ghost success, back through a different door.

`refreshSession` now throws for anything outside 400..403 instead of returning null, so a
refresh that says nothing about the refresh token reads as what it is — an outage — and
never as "no session". `refreshProfile` already treats a non-401 failure as "keep the
session, the cached name is merely stale", so the player rides out the outage signed in.
([SupabaseClient.kt](../app/src/main/java/tech/idct/whaaack/data/SupabaseClient.kt))

To make this testable on the JVM, `SessionStore` became an interface —
[DataStoreSessionStore](../app/src/main/java/tech/idct/whaaack/data/Session.kt) on the
device, an in-memory store in tests — and
[SupabaseClientTest](../app/src/test/java/tech/idct/whaaack/data/SupabaseClientTest.kt) now
runs five mockwebserver tests over the whole 401 recovery path: the forced refresh spends
the refresh token and retries with the minted pair (finding 1's regression test), a
definitive rejection clears the session and keeps the original 401, a 503 and a 429 both
leave the session standing, and a token past its stored expiry refreshes before the request
goes out. This closes what the first pass left as the highest-value item on the testing
list, and finding it is what the closing bought.

---

## Checked and sound

Recorded because the next reviewer should not have to re-derive them.

- **The entitlement decision favours the player, and correctly.** Only `OK` permits reading
  the purchase list; network liveness is proven separately by `queryProductDetails` before
  *and* after the ownership read; a revoke needs two verified-online negatives, at most one per
  process, persisted so they must fall in different sessions; `PENDING` and
  `UNSPECIFIED_STATE` neither grant nor revoke. This matches what the website's terms promise
  ("on more than one session"), which is not something the code and the copy usually agree
  about by accident.
- **The purchase is granted before it is acknowledged**, from both paths that can see one, and
  acknowledgement retries — the ordering that keeps a process death out of the three-day
  auto-refund window.
- **Backup exclusion is real, not assumed.** `backup_rules.xml` and
  `data_extraction_rules.xml` both name a single `<include>`, which makes the set exhaustive,
  so the session store and the entitlement store are excluded by construction rather than by a
  list somebody has to maintain. A refresh token cannot ride to a second device, and a
  purchase cannot be cloned onto one.
- **The Google sign-in nonce is the documented pattern**: a raw UUID, SHA-256'd and hex-encoded
  into the `GetGoogleIdOption`, with the raw value sent alongside the ID token for GoTrue to
  hash and compare. The `NoCredentialException` / `GetCredentialCancellationException` /
  `GetCredentialException` catch order is subclass-first, so the specific cases are not
  swallowed by the general one.
- **The obfuscated account id is a SHA-256 of the Supabase user id**, never the id itself and
  never an email, kept in step with the session rather than read at purchase time — so signing
  out cannot attach the previous player's handle to the next order.
- **Server-side, a client can only write what it should.** `scores` grants insert on four
  columns, so `created_at` — which both leaderboards order by and the weekly one filters on —
  is reachable only through its default; `profiles` grants update on `display_name` alone, so
  the 30-day cooldown cannot be defeated by first nulling `display_name_changed_at`, and
  `provider` (which decides whether Settings offers a password) cannot be rewritten. RLS says
  which rows, the grants say which columns, and both are needed.
- **The deep link cannot persist a broken session**: the account is resolved through
  `getAs("/auth/v1/user", access)` before anything is written, and a link that cannot be
  verified writes nothing and says so.

---

## Left open, deliberately

Not defects, or defects whose fix is a product decision rather than a correction. All are
already carried in [GO-TO-PRODUCTION-TECHNICAL.md](GO-TO-PRODUCTION-TECHNICAL.md) §5 with
their reasoning, and this review found no reason to move any of them:

- **The purchase flow has never run against real Play** (🔴). Nothing source-level substitutes
  for it: licence testers, buy, reinstall, restore, refund-and-revoke.
- **Purchases are not signature-verified.** Play Billing hands back `originalJson` and a
  `signature` over it; verifying them needs either a server or the licence key embedded in the
  app, which is what an attacker who has already installed a fake-billing app would extract.
  For a cosmetic unlock with no server cost behind it, local verification buys little — but it
  is a deliberate omission, not an oversight, and it belongs in the record as one.
- **Two ways past an interstitial** (the back gesture off game-over, and buying from the ad
  break releasing the navigation immediately). Closing one alone is theatre; they close
  together or not at all.
- **A cached interstitial has no expiry timestamp**, so an ad loaded at launch and offered an
  hour later fails to show — handled gracefully, but the impression is wasted and the ad-break
  prompt will have been raised in front of it.
- **Paying players still see the consent form**, and the ad-privacy row stays in Settings.

The first pass listed a fifth item here — that the 401-refresh-retry had no mockwebserver
test. Writing that test found finding 5, which is the argument for it made better than the
listing ever was.
