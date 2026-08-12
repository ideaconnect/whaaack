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
  `RESEND_API_KEY` unset* — so the CLI is not reliably transmitting the secret through
  `pass = "env(RESEND_API_KEY)"`.

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

1. **Google Cloud console** → APIs & Services → Credentials, in the project you want to own
   these identities.
2. Create an **OAuth client ID → Android**:
   - package name `tech.idct.whaaack`
   - SHA-1 of your signing certificate. For debug:
     ```bash
     keytool -list -v -alias androiddebugkey \
       -keystore ~/.android/debug.keystore -storepass android -keypass android
     ```
     Add the release certificate's SHA-1 too (including Play App Signing's, from the Play
     Console) before shipping.
3. Create a second **OAuth client ID → Web application**. This is the one Supabase verifies
   ID tokens against.
4. Put the **web** client id in `local.properties` as `GOOGLE_WEB_CLIENT_ID`.
5. Enable the provider in Supabase:
   ```bash
   # in supabase/config.toml set [auth.external.google] enabled = true
   GOOGLE_CLIENT_ID=<web client id> \
   GOOGLE_CLIENT_SECRET=<web client secret> \
   RESEND_API_KEY=... supabase config push
   ```
   Add the **Android** client id to the provider's *Authorized Client IDs* list as well, so
   Supabase accepts tokens minted for the native app.

On first Google sign-in the signup trigger seeds the display name from the Google account
name, de-duplicating it if taken. The player can change it later in Settings.

---

## 4. AdMob

Already wired: app id `ca-app-pub-6904561240517963~2412756903` is injected into the
manifest, and the rewarded interstitial unit is
`ca-app-pub-6904561240517963/7453330598`.

> The configured unit must be of type **Rewarded Interstitial** in the AdMob console. If it
> was created as a plain Rewarded or Interstitial unit it will never fill, and the app will
> silently skip the ad (navigation is never blocked by advertising).

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
- [ ] Configure Google OAuth clients (section 3)
- [ ] Confirm the AdMob unit is a Rewarded Interstitial (section 4)
- [ ] Publish the privacy policy at `https://idct.tech/whaaack/privacy` (linked from About)
- [ ] Create a release keystore, add its SHA-1 to the Google Android OAuth client
- [ ] Bump `versionCode` / `versionName` in `app/build.gradle.kts`
