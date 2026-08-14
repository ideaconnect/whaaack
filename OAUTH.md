1. Google Cloud console — create two OAuth clients
Consent screen first (Branding + Audience): External user type, app name, support email. Publish it to Production — while it sits in "Testing" only accounts you list explicitly can sign in. Scopes are just openid/email/profile, so no verification review is required.

Android client — package name tech.idct.whaaack (matches applicationId at app/build.gradle.kts:27), plus your signing SHA-1:


keytool -list -v -alias androiddebugkey \
  -keystore ~/.android/debug.keystore -storepass android -keypass android
Before shipping, add the release keystore's SHA-1 and Play App Signing's SHA-1 (from the Play Console) to this same client.

Web client — note both the ID and the secret. The secret is required because supabase/config.toml:62 declares one, even though the native ID-token flow doesn't verify against it. Optionally add https://pklrfcbyseitdbxkmsnw.supabase.co/auth/v1/callback as an authorized redirect URI — unused by the native flow, but free to set up now if a browser flow ever appears.

2. local.properties line 9 — currently empty

GOOGLE_WEB_CLIENT_ID=<the WEB client id>
The web one, not the Android one. It flows into BuildConfig at app/build.gradle.kts:49-53, is read at MainActivity.kt:109 as setServerClientId, and gates the button at MainActivity.kt:147. Putting the Android ID here is the classic failure — you get an aud mismatch rather than a clear error.

3. supabase/config.toml — flip enabled, and mind the Android ID
Set line 60 to enabled = true. Leave skip_nonce_check = false — that's correct, since the app hashes the nonce for Google at MainActivity.kt:112-120 and sends the raw one to Supabase at AuthRepository.kt:130.

Gap 1: the Android client id has no home in this file. The header comment at line 9 names GOOGLE_ANDROID_CLIENT_ID, but nothing references it, and the CLI's provider schema has no additional_client_ids field. What Supabase's dashboard calls Authorized Client IDs is reached by passing client_id as a comma-separated list — the remote splits it, and the CLI rejoins it on read when diffing.

Gap 2: you can't write client_id = "env(GOOGLE_CLIENT_ID),env(GOOGLE_ANDROID_CLIENT_ID)". The CLI's matcher is anchored (^env\((.*)\)$), so the whole value must be one env() call — anything else is pushed as a literal string. So keep line 61 as-is and put the comma-joined pair in the variable (or just hardcode both IDs there; client IDs aren't secrets — the web one already ships in the APK).

4. Push it

GOOGLE_CLIENT_ID="<web-id>,<android-id>" \
GOOGLE_CLIENT_SECRET="<web secret>" \
RESEND_API_KEY="<resend key>" \
supabase config push
Every one of those must be set. An unset env(VAR) is preserved as a literal and pushed verbatim as the secret value — so pushing without RESEND_API_KEY would overwrite your SMTP password with the string env(RESEND_API_KEY).

That last point cuts the other way too, and it likely fixes issue-8's neighbour in section 2 of the setup doc. The reason config push kept reporting "Remote Auth config is up to date" is that the diff replaces local secrets with the remote's stored value before comparing — a secret-only change is structurally invisible, so nothing is sent. Enabling Google changes enabled and client_id, which produces a real diff and forces a PATCH — and that PATCH body carries smtp_pass along with it. So this one push should set the Resend password too, provided the key is exported.

If you'd rather not risk the config push, the dashboard equivalent is Authentication → Sign In / Providers → Google: paste the web ID as Client ID, the web secret, and the Android ID under Authorized Client IDs.