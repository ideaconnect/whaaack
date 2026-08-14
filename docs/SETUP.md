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

### ⚠️ Outstanding: SMTP password

**Auth emails do not send yet.** Signup, password reset and email-change confirmation all
fail with `Error sending confirmation email` until this is fixed. It is a one-minute fix in
the dashboard.

What was verified:

- The Resend API key is valid and **send-only** (it cannot list domains — good practice).
- `idct.tech` **is** a verified sending domain: a test message from
  `noreply@idct.tech` was accepted by the Resend API (HTTP 200).
- The SMTP credentials work directly — `smtp.resend.com` authenticated and accepted mail on
  ports **587, 465 and 2587** from a plain SMTP client using user `resend` and the API key
  as the password.
- Despite that, Supabase still reports `Error sending confirmation email`, and
  `supabase config push` reports "Remote Auth config is up to date" *even with
  `RESEND_API_KEY` unset*.

**Why the push does nothing:** before diffing, the CLI replaces every local secret with the
value the remote already holds — so a change that touches *only* a secret compares equal to
the remote and nothing is sent. `pass = "env(RESEND_API_KEY)"` can therefore never be pushed
on its own. It rides along only when some other, visible field also changed, and then the
whole body goes up at once. Enabling Google (section 3) is exactly such a change, so that
push should carry the password too — which also means `RESEND_API_KEY` must be exported when
you run it, or the literal string `env(RESEND_API_KEY)` gets written as the password.

**Fix:** paste the key directly in the dashboard —
Supabase → Authentication → Emails → SMTP Settings:

| Field | Value |
| --- | --- |
| Host | `smtp.resend.com` |
| Port | `587` |
| Username | `resend` |
| Password | the Resend API key |
| Sender email | `noreply@idct.tech` |
| Sender name | `Whaaack!` |

Then confirm with:

```bash
curl -X POST "$SUPABASE_URL/auth/v1/signup" -H "apikey: $ANON" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"Orchard12345","data":{"display_name":"Tester"}}'
```

A `200` with a `user` object (and no `access_token`, because confirmation is on) means mail
is going out.

Everything downstream of this is already verified working end to end: signup → profile
trigger → sign-in → score insert → all-time and weekly boards → own standing → account
deletion cascade.

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

It was created with `https://…supabase.co/auth/v1/callback` as a redirect URI, which is
unused by the native flow and auto-added `supabase.co` to the consent screen's authorized
domains. Harmless while the app needs no verification review; drop both if you ever want
that list to contain only domains you own.

### Still to do: push the provider

`enabled = true` is already set under `[auth.external.google]`. The remote still needs it:

```bash
GOOGLE_CLIENT_ID="964578061899-aaiu8iv8u6fngd93to4vkma91abip7k8.apps.googleusercontent.com,964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1.apps.googleusercontent.com" \
GOOGLE_CLIENT_SECRET="<web secret from secrets/>" \
RESEND_API_KEY="<resend key>" \
supabase config push
```

Both ids go in `GOOGLE_CLIENT_ID`, web first — the remote splits on the comma into the
client id plus the dashboard's *Authorized Client IDs*, which is what makes Supabase accept
a token minted for the native app. See the note in `config.toml` for why they cannot be two
separate variables.

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

## 5. Running it

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

## 6. Before release

- [ ] Fix SMTP (section 2) — signup is broken without it
- [x] Google **Android** OAuth client (section 3)
- [x] Google **web** OAuth client and `GOOGLE_WEB_CLIENT_ID` (section 3)
- [ ] `supabase config push` to enable the Google provider remotely (section 3)
- [ ] Publish the Google OAuth consent screen to Production (section 3)
- [ ] Confirm the AdMob unit `…/2703686934` is of type **Interstitial** (section 4)
- [ ] Publish the privacy policy at `https://idct.tech/whaaack/privacy` (linked from About)
- [ ] Create a release keystore, add its SHA-1 to the Google Android OAuth client
- [ ] Bump `versionCode` / `versionName` in `app/build.gradle.kts`
- [ ] Upload `assets/icon/play-store-512.png` as the Play Console store icon — it is
      generated alongside the launcher icons and is not part of the APK
      (`python tools/generate_launcher_icons.py`)
