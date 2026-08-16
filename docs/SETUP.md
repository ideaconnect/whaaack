# Whaaack! — setup and operations

Everything the project needs that is *not* in the repo, and why.

No secrets live in git. The app reads them from `local.properties` (git-ignored) at build
time; the Supabase CLI reads them from environment variables at push time.

---

## 1. `local.properties`

```properties
sdk.dir=/path/to/android-sdk

SUPABASE_URL=https://pklrfcbyseitdbxkmsnw.supabase.co
SUPABASE_ANON_KEY=sb_publishable_...

# Only needed once Google sign-in is configured (section 3).
GOOGLE_WEB_CLIENT_ID=

# Only needed for accounts minted from Play Games (section 9). Blank is supported and
# simply leaves ranked play behind a sign-up, exactly as before.
PGS_SERVER_CLIENT_ID=
```

`SUPABASE_ANON_KEY` is a *publishable* key. It is meant to ship inside the APK — row level
security, not key secrecy, is what protects the data. It is kept out of git only so that
rotating it does not mean rewriting history.

Debug builds automatically substitute Google's always-fill test ad unit, so running the app
locally never touches live ad inventory.

---

## 2. Supabase

The project is linked already (`supabase/config.toml` → `project_id`).

```bash
supabase db push                       # schema, RLS, leaderboard functions
RESEND_API_KEY=... supabase config push  # auth settings
```

### Schema

| Object | Purpose |
| --- | --- |
| `profiles` | Public identity: display name (case-insensitively unique), provider |
| `scores` | One row per completed ranked run; `millis` is the score |
| `handle_new_user()` | Signup trigger — derives a display name and de-duplicates it |
| `leaderboard(scope, limit)` | Ranked standings, one row per player (their best run) |
| `my_standing(scope)` | The caller's own rank, which may sit below the visible page |
| `delete_my_account()` | Lets a player erase themselves without a service key |

`scope` is `all_time` or `weekly`; weekly counts from Monday 00:00 UTC.

RLS: profiles are world-readable (names appear on the board), scores are readable only by
their owner. Aggregate standings come from `SECURITY DEFINER` functions, so a client can
see the board without being able to enumerate other players' raw rows.

### SMTP — verified working (2026-08-16)

A live signup probe against the project (`bartosz+smtptest@idct.tech`) returned 200 with a
real user, a populated `identities` array and a `confirmation_sent_at` timestamp — GoTrue
answers 500 `Error sending confirmation email` when the mailer is down, so this is proof the
password is configured remotely. The mailer runs through the dashboard's SMTP settings
(`smtp.resend.com:587`, user `resend`, the Resend key as password, sender
`noreply@idct.tech`).

Worth keeping for the next secret rotation: `supabase config push` cannot carry a
secret-only change. Before diffing, the CLI replaces every local secret with the value the
remote already holds, so `pass = "env(RESEND_API_KEY)"` on its own always compares "up to
date". A rotated key goes in through the dashboard, or rides a push that also changes some
visible field — with `RESEND_API_KEY` exported, or the literal string `env(RESEND_API_KEY)`
becomes the password.

To re-verify after any change:

```bash
curl -X POST "$SUPABASE_URL/auth/v1/signup" -H "apikey: $ANON" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you+probe@yourdomain","password":"Orchard12345","data":{"display_name":"SMTP Probe"}}'
```

A `200` whose user has a non-empty `identities` array and a `confirmation_sent_at` means
mail went out (delete the probe user in the dashboard afterwards). A `200` with an *empty*
`identities` array means the address already has an account — that is the enumeration
shield, not a mailer answer.

Everything downstream is verified working end to end: signup → profile trigger → sign-in →
score insert → all-time and weekly boards → own standing → account deletion cascade.

---

## 3. Google sign-in

The app implements the native Credential Manager flow, including nonce hashing. It stays
disabled until the OAuth clients exist — the button greys out rather than failing.

Two clients are needed, in Google Cloud project **`whaaack-505409`** (APIs & Services →
Credentials). They play different roles and are easy to mix up: the **web** client is the
identity Supabase verifies ID tokens against and the one compiled into the app, while the
**Android** client is the audience Google actually mints the token for.

### Done: Android client

`964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1.apps.googleusercontent.com`

Package `tech.idct.whaaack`. Confirm it carries this machine's debug SHA-1, or debug builds
get no credential back:

```
A8:87:91:6F:1B:42:11:BA:82:4F:C8:5B:0A:8A:83:DC:BD:9B:07:EB
```

```bash
keytool -list -v -alias androiddebugkey \
  -keystore ~/.android/debug.keystore -storepass android -keypass android
```

Each developer's debug certificate is different, so every machine that needs a working
debug sign-in adds its own SHA-1 here. Add the release certificate's SHA-1 too — including
Play App Signing's, from the Play Console — before shipping.

### Done: web client

`964578061899-aaiu8iv8u6fngd93to4vkma91abip7k8.apps.googleusercontent.com`

Its secret is in the downloaded JSON under `secrets/` (git-ignored). This is the id the app
compiles in — `GOOGLE_WEB_CLIENT_ID` in `local.properties`, already set — and the one
Supabase verifies ID tokens against.

Putting the **Android** id in `GOOGLE_WEB_CLIENT_ID` is the classic failure here, and it does
not announce itself: you get an `aud` mismatch on the token rather than anything that reads
like a configuration error.

It was created with `https://…supabase.co/auth/v1/callback` as a redirect URI, which is
unused by the native flow and auto-added `supabase.co` to the consent screen's authorized
domains. Harmless while the app needs no verification review; drop both if you ever want
that list to contain only domains you own.

### Done: the provider is live remotely

`GET /auth/v1/settings` on the project answers `"google": true` (checked 2026-08-16), so the
remote has the provider enabled and there is nothing left to push. For a future change —
rotating the web secret, adding a client id — the shape is:

```bash
GOOGLE_CLIENT_ID="964578061899-aaiu8iv8u6fngd93to4vkma91abip7k8.apps.googleusercontent.com,964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1.apps.googleusercontent.com" \
GOOGLE_CLIENT_SECRET="<web secret from secrets/oauth/web_client_secret_*.json>" \
RESEND_API_KEY="<resend key, secrets/resend.txt>" \
supabase config push
```

(All three exported together, because the push sends the whole body at once — see the SMTP
note in section 2 for why a secret-only change never diffs.)

Both ids go in `GOOGLE_CLIENT_ID`, web first — the remote splits on the comma into the
client id plus the dashboard's *Authorized Client IDs*, which is what makes Supabase accept
a token minted for the native app.

They cannot be two separate variables. The CLI's substitution matcher is anchored —
`^env\((.*)\)$` — so the whole value has to be one `env()` call; `"env(A),env(B)"` matches
nothing and is pushed verbatim as that literal string. Hardcoding both ids in `config.toml`
is also fine if you prefer: client ids are not secrets, and the web one already ships inside
the APK.

**If you would rather not risk a `config push` at all**, the dashboard does the same job:
Authentication → Sign In / Providers → Google — paste the web id as *Client ID*, the web
secret as *Client Secret*, and the Android id under *Authorized Client IDs*. That route
touches nothing else, which is its whole advantage now that SMTP is working (§2): a push
rewrites the entire auth config from this file, including the SMTP password.

### Consent screen

Publish it to **Production**. While it is in Testing only accounts explicitly listed as
testers can sign in. The flow requests just `openid`/`email`/`profile`, all non-sensitive,
so publishing needs no verification review.

**Authorized domains: `idct.tech`, and nothing Supabase-related.** That list gates only the
domains used in redirect URIs and in the homepage / privacy / terms links. Nothing here
redirects through a browser — Credential Manager returns the ID token in-process and the app
posts it to `grant_type=id_token` — so the web client needs no redirect URI, and
`supabase.co` never has to be authorized. Don't add the `/auth/v1/callback` URI "just in
case": it would drag `supabase.co` into a list where every entry has to be a domain you can
prove you own.

Enter the domain as the bare eTLD+1 (`idct.tech`, not `www.idct.tech`, not a URL), and add it
*before* pasting the privacy policy link or the field will be rejected. The logo matters more
than it looks: in the native flow it is what appears on the account-picker sheet.

> `RESEND_API_KEY` is not optional in that command even though it looks unrelated. Any
> unset `env(...)` is pushed as the literal string `env(RESEND_API_KEY)`, which would
> replace the SMTP password with nonsense. The flip side is useful: because enabling Google
> is a *visible* change, this push finally carries the SMTP password with it — see the note
> in section 2 on why a secret-only push does nothing.

On first Google sign-in the signup trigger seeds the display name from the Google account
name, de-duplicating it if taken. The player can change it later in Settings.

---

## 4. AdMob

Already wired: app id `ca-app-pub-6904561240517963~2412756903` is injected into the
manifest, and the interstitial unit is `ca-app-pub-6904561240517963/2703686934`.

> The configured unit must be of type **Interstitial** in the AdMob console. If it was
> created as a Rewarded or Rewarded Interstitial unit it will never fill, and the app will
> silently skip the ad (navigation is never blocked by advertising).

The placement used to be a *rewarded* interstitial that granted no reward, which is a format
mismatch AdMob's policies take a dim view of — the rewarded formats expect an explicit value
exchange. It is now a plain interstitial, which is what the placement always behaved like.

Debug builds override the unit with Google's reserved always-fill interstitial test id
(`ca-app-pub-3940256099942544/1033173712`), so development never touches live inventory. The
format of the test id has to match the format the code loads, or it will never fill either.

`AdsManager` shows at most one ad every two minutes. Both routes off the game-over screen ask
for one, so without that cap a player alternating "Play again" with a thirty-second run would
see an ad between every attempt.

### EU consent — no extra ID needed

The question was whether the consent message created in the AdMob console needs an ID
embedded in the app. **It does not.**

Google's User Messaging Platform fetches your published message using the **AdMob
application id** that is already in the manifest. That is the only link between app and
message. There is no "message id" or "consent form id" to copy anywhere.

What does matter:

1. In AdMob → **Privacy & messaging** → GDPR, the message must be **published** (not draft)
   and must target this app.
2. The app must call the UMP SDK before requesting ads — `ConsentManager.gather()` runs on
   launch from `MainActivity`, and `AdsManager` refuses to initialise until
   `canRequestAds` is true.
3. Settings shows an **Ad privacy options** row whenever
   `privacyOptionsRequirementStatus == REQUIRED`, which is the "withdraw consent" entry
   point the TCF requires.

This is confirmed working: on first launch the emulator showed the real published GDPR
message with the app's own icon, listing 210 partners.

The only ID you may ever want is for **testing**, and it is not stored in the app. To force
the EEA experience on a device outside the EU, run once and copy the hashed device id the
Ads SDK prints to logcat:

```
Use ConsentDebugSettings.Builder().addTestDeviceHashedId("ABCD…") to set this as a debug device.
```

Pass it to `vm.gatherConsent(activity, debugDeviceHashedId = "ABCD…")` in `MainActivity`.

---

## 5. The ad-free unlock

One one-time product, created and **Active** in Play Console:

| | |
|---|---|
| Product id | `no.ads.forever` |
| Name | Whaaack the ads! |
| Purchase option | `no-ads-forever-buy`, type Buy, **backwards compatible**, 173 countries |

The id is compiled in as the `REMOVE_ADS_PRODUCT_ID` BuildConfig default, so nothing needs to
be set in `local.properties` for it. Backwards compatible is load-bearing: it is what keeps
the singular `oneTimePurchaseOfferDetails` populated, which is where the price is read and why
`launchPurchase` passes no offer token. Adding a second purchase option would empty that
accessor and the upsell would quietly stop appearing.

### Why the price is missing on a development build

Play does not price a product for a build it did not install. A sideloaded debug APK
(`installerPackageName=null`) gets `SERVICE_DISCONNECTED` or `ITEM_UNAVAILABLE` from
`queryProductDetails`, so `BillingManager.price` stays null, and **every upsell hides itself**
— the Home row, the Settings row and the ad-break dialog. Ads still play, because that path
only needs to know the player has *not* bought it. This is correct behaviour, not a bug: there
is nothing to sell, so nothing is offered.

Seeing the real thing needs the real conditions — an AAB uploaded to a track, installed
**from Play**, with the account added under Setup → Licence testing. The full matrix is in
[GO-TO-PRODUCTION-TECHNICAL.md](GO-TO-PRODUCTION-TECHNICAL.md) §5.

For working on the screens rather than the transaction, a debug build can stand a price in:

```properties
# local.properties — debug builds only, blank in release whatever this says
REMOVE_ADS_PLACEHOLDER_PRICE=12,99 zł
```

That fills in the displayed price only. The purchase itself is not faked: tapping through
still reaches Play, which declines, which is the half of the flow worth watching fail. Leave
it blank to see exactly what production does.

> `local.properties` is read as UTF-8 by `app/build.gradle.kts`. `Properties.load(InputStream)`
> is specified as ISO-8859-1, which turns `12,99 zł` into `12,99 zÅ‚` on screen — the build
> file uses a `Reader` for precisely this reason.

---

## 6. Running it

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tech.idct.whaaack/.MainActivity
```

Deep links (password reset, email confirmation) arrive as `whaaack://auth#access_token=…`.
To exercise one by hand:

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "whaaack://auth#access_token=…&refresh_token=…&type=recovery"
```

---

## 7. Play Games Services

Achievements, Game Stats and the sign-in they hang off, covered in full in
[PLAY-GAMES.md](PLAY-GAMES.md). The app side is committed, and so are the four achievement
ids — the console has all four, and `app/build.gradle.kts` carries them as defaults, so
nothing needs configuring per machine. Game Stats needs two uploads that have not been made
yet; `python tools/build_game_stats_zip.py` prepares and pre-flights them.

Read §2 of that document first. The fingerprint now on the **Android** OAuth client,
`B0:83:0D:DA:DD:E3:54:16:61:3B:B9:D1:53:30:E2:B9:D9:1A:25:0C`, is neither this machine's
debug certificate nor the upload key — it looks like Play App Signing's, which is right for
what players install and means **nothing built locally can authenticate** until a second
Android client carries the debug SHA-1. That also puts a question mark over the debug Google
sign-in recorded as working in section 3, since it is the same client.

---

## 9. Accounts minted from Play Games

Lets a player who has Play Games and no Whaaack! account be ranked, by making them one out of
the identity they already hold. Design and testing notes live in
[PLAY-GAMES.md §8](PLAY-GAMES.md#8-accounts-minted-from-play-games); this is the wiring.

Nothing here is required to ship. With `PGS_SERVER_CLIENT_ID` blank the feature is inert and
ranked play needs a sign-up, as it did before.

**Status (2026-08-16): steps 2–4 are done** — the id is in `local.properties`, both secrets
are set, and the deployed function answers a garbage code with `code_rejected`, which proves
the gateway lets it run, the apikey gate passes, and the exchange reaches Google. Step 1 is
the one still open: the web client `…aaiu8iv8u…` must be registered as a **Game server**
credential in the Play Games Services console, or the SDK will not issue codes for it.

### Step 1 — a Game server credential

Play Console → Play Games Services → Setup and management → Configuration → **Add credential**
→ type **Game server**. Point it at a **web** OAuth client (create one in Google Cloud project
`whaaack-505409` if there is none: APIs & Services → Credentials → Create → OAuth client ID →
Application type **Web application**).

This is a *different kind* of credential from the Android ones in section 7. The Android
client identifies the app by package name and SHA-1 and has no secret; this one has a secret,
and the secret is the entire reason the exchange can be trusted.

⚠️ It may be the same web client as `GOOGLE_WEB_CLIENT_ID`, but it does not have to be, and
only the one registered here as a Game server credential will work. They are kept as separate
keys for exactly that reason — substituting one for the other fails at Google's token endpoint
with an error the app cannot explain.

### Step 2 — the app's half

```properties
# local.properties
PGS_SERVER_CLIENT_ID=<the web client id from step 1>
```

### Step 3 — the function's half

```bash
supabase secrets set PGS_WEB_CLIENT_ID=<the same web client id>
supabase secrets set PGS_WEB_CLIENT_SECRET=<its client secret>
supabase functions deploy play-games-auth
```

`SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` are injected by the
platform — do not set them yourself. `PGS_PLAYER_EMAIL_DOMAIN` is optional and defaults to
`pgs.whaaack.invalid`; `.invalid` is reserved by RFC 2606 and can never resolve, which is what
guarantees these derived addresses are identifiers rather than mailboxes.

The client secret exists in exactly one place — Supabase's secret store. It must never reach
`local.properties`, because everything there is compiled into the APK.

### Step 4 — check it

```bash
supabase functions logs play-games-auth
```

Sign out of the Whaaack! account with Play Games still signed in, tap **Play ranked**, accept
the invitation. A ranked run should start with no sign-up screen. `code_rejected` in the log
is almost always the wrong client id or a stale secret; `not_configured` means the two secrets
above were never set.

---

## 10. Before release

- [x] SMTP — verified working by a live signup probe, 2026-08-16 (section 2)
- [x] Google **Android** OAuth client (section 3)
- [x] Google **web** OAuth client and `GOOGLE_WEB_CLIENT_ID` (section 3)
- [x] Google provider live remotely — `/auth/v1/settings` answers `"google": true` (section 3)
- [ ] Publish the Google OAuth consent screen to Production (section 3)
- [ ] Confirm the AdMob unit `…/2703686934` is of type **Interstitial** (section 4)
- [x] Create and activate the one-time product `no.ads.forever` (section 5)
- [ ] Run the purchase matrix from Play, with licence testers (section 5)
- [~] Second Android OAuth client on the debug SHA-1 — **deliberately not done**. Play
      re-signs with `B0:83:…`, which the existing credentials already carry, so Google
      sign-in and PGS work from an internal-testing track onwards. The accepted cost is
      that neither can be tested on a locally built APK. See PLAY-GAMES.md §2.
- [x] Create the four achievements; their ids are committed (section 7)
- [ ] Upload `PlayerGameEvent.csv` and `GameStats.zip` for Game Stats (section 7)
- [ ] Add Play Games testers, then **publish** the PGS configuration (section 7)
- [x] `PGS_SERVER_CLIENT_ID` set, both function secrets set, `play-games-auth` deployed and
      smoke-tested (a garbage code answers `code_rejected`, proving the whole chain to
      Google's token endpoint) — 2026-08-16 (section 9)
- [ ] Register the web client as a **Game server** credential in Play Games Services →
      Configuration → Credentials (section 9). Until then `requestServerSideAccess` issues
      no codes and every mint fails with "Play Games couldn't confirm your account".
- [ ] Clear `REMOVE_ADS_PLACEHOLDER_PRICE` from `local.properties` before believing a
      pre-release build's upsell (section 5) — it is debug-only, but it is also a lie
- [ ] Publish the privacy policy at `https://idct.tech/whaaack/privacy` (linked from About)
- [ ] Create a release keystore, add its SHA-1 to the Google Android OAuth client
- [ ] Bump `versionCode` / `versionName` in `app/build.gradle.kts`
- [ ] Upload `assets/icon/play-store-512.png` as the Play Console store icon — it is
      generated alongside the launcher icons and is not part of the APK
      (`python tools/generate_launcher_icons.py`)
