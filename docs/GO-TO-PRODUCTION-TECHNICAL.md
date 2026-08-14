# Whaaack! — go to production: technical

Everything that happens in the repo: code, SQL, Gradle, CI, tests and the site's markup.

Derived from a full audit of the tree on **2026-08-14**, split from a single plan so the two
kinds of work can be picked up independently. Items are verbatim from that audit; nothing was
dropped in the split.

The Play developer account, the AdMob account and both payments profiles already exist and are
verified — IDCT has published apps — so the multi-week external queues that normally dominate a
first launch do not apply, and neither does the 12-testers-for-14-days gate (production access is
a one-time account-level unlock). **Realistic timeline: about a week**, most of it your own
testing and the staged rollout.

See also: **[non-technical plan](GO-TO-PRODUCTION-NON-TECHNICAL.md)**.

## Where this stands

**37 of 96 done.** Sections are ordered by when you need them, not by size.

| Section | Done |
| --- | --- |
| 1. Engineering decisions to settle first | 0 / 3 |
| 2. Release engineering — the build has never been signed or run | 0 / 21 |
| 3. Code — defects that block or damage the launch | **done** |
| 4. Backend — Supabase | 5 / 21 |
| 5. Ads and billing — the code side | 1 / 13 |
| 6. The website — markup, CSS and CI | 5 / 6 |
| 7. Testing and QA | 0 / 6 |
| 8. The game itself | **done** |
| 9. Repo hygiene | **done** |

Everything ticked is on `main` and pushed. The database migration is applied to
`pklrfcbyseitdbxkmsnw` and verified against it, SMTP is live, and **the website is published** —
the Pages workflow ran green on `e08583e` with all seven guards, including the four new ones,
and `idct.tech/whaaack/` now serves the branded 404, the sitemap and the slimmed icons.

Ticked items keep their original diagnosis in the tense it was written, with a
**Done / Resolved / Decided** note recording what actually happened and where the outcome
differed from what the item proposed.

**The critical path is the release keystore (§2).** There is still no signing config, so no
installable release artifact has ever existed — which means every code fix in §3, §5 and §8 is
compiled and unit tested but has never run on a device. The R8 shakeout in §2 is what turns
"compiles" into "works", and most of what remains elsewhere is gated behind it.

**Two things are blocked on someone else, not on work:** the Web3Forms dashboard toggle that
would make the contact form's captcha real (§6 — tested, it currently accepts submissions
without one), and one line in the `ideaconnect.github.io` repo registering
`/whaaack/sitemap.xml` in the apex sitemap index.

--- | --- |
| 1. Engineering decisions to settle first | 0 / 3 |
| 2. Release engineering — the build has never been signed or run | 0 / 21 |
| 3. Code — defects that block or damage the launch | **done** |
| 4. Backend — Supabase | 5 / 21 |
| 5. Ads and billing — the code side | 0 / 13 |
| 6. The website — markup, CSS and CI | 0 / 6 |
| 7. Testing and QA | 0 / 6 |
| 8. The game itself | **done** |
| 9. Repo hygiene | **done** |

Everything in §3, §8 and §9 is implemented and building; four database items in §4 are pushed to
`pklrfcbyseitdbxkmsnw` and verified against it, and SMTP is live. Ticked items keep their original
diagnosis in the tense it was written, with a **Done / Resolved / Decided** note recording what
actually happened and where the outcome differed from what the item proposed.

**The critical path is the release keystore (§2).** There is still no signing config, so no
installable release artifact has ever existed — which means every fix below is compiled and unit
tested but has never run on a device. The R8 shakeout in §2 is the step that turns "compiles" into
"works", and most of what remains elsewhere is gated behind it.

**Severity**

| Tag | Meaning |
| --- | --- |
| 🔴 | **Blocker** — the release is impossible, will be rejected, or ships broken |
| 🟡 | **Pre-launch** — not a submission gate, but shipping without it costs money, users or sleep |
| ⚪ | **Nice to have** — do it when there is room |

---

## 1. Engineering decisions to settle first

These change what the other sections cost, so settle them early. Two of the three also change
documents that live in the non-technical plan, which is why they are called out rather than
buried in an implementation section.

- [ ] **Decide the crash-reporting strategy, and decide it before the Data safety form** 🟡
  There is no crash reporter — `WhaaackApp` is an empty five-line `Application`. Two branches.
  **(A) Play vitals only:** zero code, zero Data safety change, zero privacy-policy change, but
  you only see crashes from users who opted into diagnostics, aggregated, hours late, with no
  breadcrumbs and no way to know the game phase that produced a render-thread crash. **(B) A
  reporter** (Crashlytics or Sentry): real stacks with custom keys for `phase`, `level`, `ranked`,
  `signedIn`, `adsRemoved` — but it collects an installation ID and device data, so Play Data
  safety must declare Crash logs and Diagnostics and the privacy policy needs a paragraph it does
  not have. Given the riskiest code in this app is a bespoke render thread doing EGL teardown
  (README "How it renders"), branch A is thin. Whichever you pick, **decide before filling the
  Data safety form** — adding a reporter afterwards re-opens both documents and a Play review.

- [ ] **Decide whether v1 ships with product analytics** 🟡
  Same reasoning, same deadline. Play gives you installs, uninstalls, retention and vitals; AdMob
  gives impressions and eCPM. Neither tells you what fraction of installs finish a first run, how
  many reach sign-up, how many complete it (the metric that would prove SMTP works in the wild),
  or how many see the Remove-ads row and buy — and that last funnel is the only thing in the app
  that makes money directly. Either adopt a small event set (`run_finished`, `signup_started`,
  `signup_completed`, `leaderboard_opened`, `remove_ads_shown`, `remove_ads_purchased`) or write
  down that v1 ships blind and Play cohorts are accepted as the only signal.

- [ ] **Decide the large-screen posture** 🟡
  The activity is hard-locked to portrait and declares no `<uses-feature>`, so it is offered to
  tablets and Chromebooks unfiltered. Either accept phone-first quality (expect lower ranking on
  large screens, skip the tablet screenshot slots) or invest a little and become eligible for
  large-screen promotion. Either way, run it once on a 10-inch tablet emulator before launch so
  you learn what reviewers and the pre-launch report will see. See also the `appCategory` item in
  §3.

---

## 2. Release engineering — the build has never been signed or run

This is the critical path. Until there is a signing config there is no installable release
artifact, and until there is one, nothing below it has actually been exercised.

- [ ] **Create the upload keystore and wire a `signingConfig` into Gradle** 🔴
  `app/build.gradle.kts` has a `release` block with minification and ProGuard but **no
  `signingConfigs` and no `signingConfig` assignment** — a repo-wide grep returns nothing.
  `bundleRelease` succeeds and produces a 12.9 MB `.aab` with **zero META-INF entries**: it is
  unsigned, and Play rejects it outright. Generate the key
  (`keytool -genkeypair -v -keystore whaaack-upload.jks -storetype PKCS12 -keyalg RSA -keysize
  4096 -validity 10000 -alias whaaack-upload`), keep it **outside** the repo, put its credentials
  in a git-ignored `keystore.properties`, and add a `signingConfigs { create("release") { … } }`
  block that reads them and falls back to environment variables for CI. Guard the assignment on
  the keystore existing so a machine without it still configures. Verify with
  `jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab`.

- [ ] **Close the `.gitignore` gap *before* running keytool** 🔴
  `.gitignore` has `*.keystore` but **not `*.jks`** — which is exactly what keytool and Android
  Studio produce by default, so the key you are about to create would be committed on the next
  `git add .` in a public repo. Add `*.jks`, `keystore.properties`, `*.p12`, `*.pepk` and `*.der`
  first, then confirm with `git check-ignore -v upload-keystore.jks keystore.properties`.

- [ ] **Back up the upload key and record its fingerprints** 🔴
  Play App Signing means a lost *app signing* key is recoverable by Google, but a lost *upload*
  key still costs a support round-trip measured in days. Store the `.jks` and its passwords in a
  password manager plus one offline copy, and record the alias and the SHA-1/SHA-256 fingerprints
  in a git-ignored note so you can re-register them without the key file.

- [ ] **Enrol in Play App Signing and register the *Play-managed* SHA-1 with the Android OAuth client** 🔴
  This is the classic "works on my desk, silently dead in production" failure. Play re-signs your
  upload, so the certificate every Play-installed copy presents to Credential Manager is Google's,
  not yours — and Credential Manager only mints an ID token for a package+certificate pair
  registered on the OAuth client. Miss it and **every Play user** gets `NoCredentialException`
  from "Continue with Google". You cannot know the fingerprint until after the first upload, so
  the order is fixed: create the app → upload a signed AAB to **internal testing** (this generates
  the app signing key) → Play Console → Test and release → Setup → **App integrity** → copy the
  App signing key SHA-1 → add it to the Android client
  `964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1…` in Google Cloud project `whaaack-505409` →
  add the **upload** key's SHA-1 too → re-test sign-in from a Play-installed build. The
  `whaaack://auth` deep link is a custom scheme, not an App Link, so no `assetlinks.json` is
  needed for *this* item (but see §4).

- [ ] **Ship an `.aab`, not an `.apk`, and make a conscious call on the split dimensions** 🔴
  Play has required App Bundles for new apps since Aug 2021, so `bundleRelease` is the upload
  artifact — nothing in the repo mentions it; SETUP.md §5 documents only `assembleDebug`. The
  bundle's `BundleConfig.pb` has an empty `splits_config`, so bundletool defaults apply (ABI,
  density and language splits all on), which is right here. Language splits deserve a note: the
  app ships only English `values/`, while Compose and GMS contribute ~410 KB of translations Play
  will split per locale. Add the release command to SETUP.md §5. One verified non-issue — the AAB
  already embeds `BUNDLE-METADATA/…/proguard.map`, so Play deobfuscates crashes with no upload
  step.

- [ ] **Make the release build fail loudly when a secret is blank** 🔴
  `secret()` falls back to the empty string for `SUPABASE_URL`, `SUPABASE_ANON_KEY` and
  `GOOGLE_WEB_CLIENT_ID`. An AAB built anywhere `local.properties` is absent — fresh clone, CI
  runner, rebuilt machine — compiles, signs, passes lint and uploads fine, and ships a game where
  `SupabaseClient.isConfigured` is false, the leaderboard is empty, every auth call
  short-circuits, and Google sign-in returns immediately. Nothing in the build log says so. Add a
  `check(...)` on the release variant for those three plus
  `ADMOB_INTERSTITIAL_AD_UNIT_ID`, keeping debug unaffected. (Verified separately and *not* a
  problem: the Gradle configuration cache does track `local.properties` content, so a changed
  secret cannot be served stale.)

- [ ] **Fail the release build if it is carrying a test ad unit** 🟡
  The release build type adds no ad-unit override, so it takes whatever
  `ADMOB_INTERSTITIAL_AD_UNIT_ID` resolves to — which on a developer machine may be a test id
  someone pasted in while debugging. Throw at configuration time if the resolved release ad unit
  or app id starts with `ca-app-pub-3940256099942544`, and assert the app id and unit id share the
  `pub-6904561240517963` segment. Worth five minutes precisely because the failure is invisible: a
  release on the test unit serves perfect always-fill ads and earns exactly nothing.

- [ ] **Build, install and actually *run* the R8-minified release build** 🔴
  It has never been executed once — the only release artifact ever produced is
  `app-release-unsigned.apk`, which cannot be installed, so every reflection-sensitive path in the
  shrunk build is unverified. `assembleRelease` compiling clean is not the same thing. Once
  signing exists, exercise in one session: email sign-up and sign-in, the leaderboard round-trip
  (kotlinx.serialization + OkHttp TLS), the `whaaack://auth` deep link, Google sign-in via
  Credential Manager, the UMP consent form, one interstitial, `BillingManager`'s
  `queryProductDetails`/`queryPurchasesAsync` pair, DataStore reads and writes, and a full run
  including surface teardown — with `adb logcat -s AndroidRuntime:E` open. Note debug and release
  share the applicationId with no suffix, so `adb uninstall tech.idct.whaaack` first.

- [ ] **Define the versionCode discipline before the first upload burns code 1** 🟡
  `versionCode = 1` is legal for a genuinely first upload — the SETUP.md checklist item is
  misleading as written. What matters is the rule going forward: a versionCode must strictly
  increase, and Play permanently consumes one for **any** track, including an internal build you
  upload and immediately delete. Expect to burn 5–15 before production. Either accept manual
  bumping, or derive it in Gradle from `GITHUB_RUN_NUMBER` / a `version.properties` with a local
  fallback. Practical suggestion: start at `1000` for headroom. Keep `versionName` semantic.

- [ ] **Add annotated git tags and a CHANGELOG** 🟡
  `git tag` is empty and there is no CHANGELOG. Adopt `v1.0.0` annotated tags carrying the
  versionCode in the message, cut at the exact commit that produced the uploaded AAB, so an
  incoming crash report maps to a tree. Keep `CHANGELOG.md` in Keep-a-Changelog form and make its
  top section the literal source of Play's "What's new" (capped at 500 characters per locale).

- [ ] **Write `docs/RELEASE.md` — and state plainly that a Play release cannot be rolled back** 🟡
  `docs/` holds only REVIEW.md and SETUP.md; the release artifact format is never named in any
  markdown. Cover, in order: tag → bump version → `clean testDebugUnitTest lintRelease
  bundleRelease` → verify the signature → internal testing → smoke-test the R8'd build on a real
  device → closed → production. **There is no rollback.** You can only halt a staged rollout —
  which stops it reaching *new* users but does not remove it from anyone who already installed —
  and ship a fix under a strictly higher versionCode. Mandate a staged rollout (5% / 20% / 50% /
  100% with 24h holds) and named go/no-go criteria at each gate, and write the halt procedure down
  so it is not improvised under pressure.

- [ ] **Add an Android CI workflow** 🟡
  `.github/workflows/` contains only `pages.yml`, which is well built but touches nothing Android.
  Add `android.yml`: Temurin 17, `gradle/actions/setup-gradle` (which also validates the committed
  wrapper JAR), then `:app:testDebugUnitTest :app:lintVitalRelease :app:bundleRelease`. Build the
  **release** variant specifically — a keep-rule regression is invisible in debug and only shows
  as a runtime crash in the shipped app. The `secret()` helper already falls back to
  `System.getenv`, so GitHub Actions secrets need no build-script change. Decode the keystore to
  `$RUNNER_TEMP`, never the workspace root. Upload the AAB **and** `mapping.txt` as artifacts,
  keyed by versionCode — the AAB-embedded copy covers Play's deobfuscation but not a stack trace a
  user pastes into an email, and you cannot regenerate a byte-identical mapping later. Add
  `dependabot.yml` for Gradle so the ads SDK does not silently fall two majors behind again.

- [ ] **Add a `lint { }` block — but re-diagnose the lintRelease failure first** ⚪
  The audit claimed `lintRelease` fails on `PropertyEscape` from an unescaped Windows SDK path.
  That diagnosis is wrong: `local.properties` contains zero backslashes (forward slashes
  throughout) and PropertyEscape is Warning severity, which cannot fail a build absent
  `warningsAsErrors`. Re-run `./gradlew :app:lintRelease --stacktrace` and read the real issue id
  from the HTML report before suppressing anything. Then add the block — but introduce
  `checkDependencies = true` and `abortOnError = true` one at a time with a baseline, or CI goes
  red on day one from pre-existing library issues.

- [ ] **Keep the 16 KB page-size compliance you already have** ⚪
  Verified already compliant, directly rather than assumed: the two transitive native libraries
  (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) have `p_align = 0x4000` on
  every PT_LOAD segment across all four ABIs, every `.so` sits at a 16384-multiple offset stored
  uncompressed, and the AAB declares `page_alignment: PAGE_ALIGNMENT_16K`. But this is a property
  of dependencies you do not control, and Play has required 16 KB support since 1 Nov 2025, so a
  regression on a future bump is a rejected upload. Add a CI assertion over
  `lib/arm64-v8a/*.so` alignment.

- [ ] **Record the targetSdk position** ⚪
  `compileSdk`/`targetSdk` are 36 and `minSdk` is 26 (justified by `lockHardwareCanvas`). The API
  36 cutoff for new apps is **31 August 2026** — 17 days from the audit date — so targeting 36
  already satisfies it and the deadline pressure is on shipping, not on code. Note the next cycle
  (API 37, ~Aug 2027) in SETUP.md so it is not rediscovered.

- [ ] **Give debug builds an `applicationIdSuffix`** ⚪
  Once the app is live, a developer with the Play build installed cannot install debug over it.
  `applicationIdSuffix = ".debug"` fixes that — but the Android OAuth client is keyed on package
  name, so `tech.idct.whaaack.debug` needs its own client or debug Google sign-in stops working.
  If that is unwelcome, leave it and document the uninstall step instead.

- [ ] **Trim `proguard-rules.pro` — after the release build is proven to run** ⚪
  Verified: the app has **zero `@Serializable` classes** — every JSON path goes through
  `JsonElement`/`JsonObject` — so the serializer keep rules match nothing, and
  `-keepclassmembers … { *** Companion; }` pins every companion object against optimisation for no
  benefit. The four `-dontwarn` lines are already supplied by okhttp's own consumer rules. Reduce
  the file to a comment explaining why it is empty, but do it *after* the shakeout run so a
  regression is attributable, then re-run the shakeout.

- [ ] **Modernise the androidx/Compose stack** ⚪
  The catalog pins Compose BOM 2024.12.01 (Compose 1.7.6), activity-compose 1.9.3, core-ktx
  1.15.0, lifecycle 2.8.7 — a set released for API 35, compiled against API 36. That is where
  edge-to-edge and inset regressions live: `enableEdgeToEdge()` from activity 1.9.3 predates the
  Android 15/16 enforcement work, and `themes.xml` still sets `statusBarColor`/`navigationBarColor`,
  both deprecated and ignored from API 35 up. Bump and re-verify insets on API 35 and 36 in both
  gesture and 3-button nav. Do it **before** the R8 shakeout so you only validate one artifact.

- [ ] **Decide the `androidx.work` question once, not twice** ⚪
  `work-runtime:2.7.0` arrives transitively via play-services-ads and declares
  `SystemForegroundService` with **no `foregroundServiceType`** — under targetSdk ≥ 34 that would
  throw if ever started. Today the GMA offline-ping worker is not a foreground worker, so the path
  is unreachable. But bumping it is not free either: recent WorkManager adds a typed
  `dataSync` FGS and the matching `FOREGROUND_SERVICE_DATA_SYNC` permission, and Play's declaration
  form keys on the *typed* permissions — so the bump can turn a non-issue into an App content
  declaration requiring written justification for a service this game never starts. Pick one
  deliberately: **(a)** leave it at 2.7.0 and accept a latent, currently-unreachable risk, or
  **(b)** bump it, then diff the merged manifest and be ready to declare the use or strip the
  permission with `tools:node="remove"`.

- [ ] **Transcode the WAV effects, or fix the comment that claims `noCompress` does something** ⚪
  Decoded from the AAB: aapt2's default `uncompressed_glob` list already covers png/ogg/wav, so
  the `noCompress` line is an exact-effect duplicate and changes nothing — the comment overstates
  what it buys. The real lever is 1.06 MB of raw WAV in assets (`squelching_1.wav` alone is
  153 KB). SoundPool decodes OGG natively and these are short one-shots loaded at startup, so
  transcoding cuts ~0.8 MB off every download with no per-frame cost. Current APK is 8.85 MB, so
  this is optional either way.

- [ ] **Generate an app-specific baseline profile** ⚪
  The AAB already carries AGP-merged library profiles and `ProfileInstallReceiver` is in the
  manifest, so the delivery mechanism works — but the profile contains nothing for
  `tech.idct.whaaack`: not `GameAssets`' sprite decode, not the `GameRenderer` frame path, not the
  Compose menus, which are exactly what runs in the cold-start window. Add the
  `androidx.baselineprofile` plugin with a macrobenchmark generator that launches, waits for Home
  and plays ~10 seconds, and measure before/after with `StartupTimingMetric`.

---

## 3. Code — defects that block or damage the launch

**All twenty are implemented.** Found by audit and not covered by the earlier review in
[REVIEW.md](REVIEW.md) — anything that review marks Fixed is deliberately absent here.

Verified together: `:app:testDebugUnitTest` passes **20** tests (15 before, 5 new ones covering
the interrupted-run banking added for the process-death item), `:app:assembleDebug` and
`:app:assembleRelease` both build, R8 full mode emitted no `missing_rules.txt`, and
`:app:lintVitalRelease` is clean. The three manifest attributes were read back out of the
**merged** release manifest rather than assumed: `appCategory="game"`,
`resizeableActivity="false"` and `networkSecurityConfig`.

Four items were resolutions rather than implementations, and say so in place: the process-death
question, the release-logging question, the split-screen approach, and the OkHttp 5 evaluation.

Each description below is left in the tense it was written, as the original diagnosis — so a
line like "which this one does not" describes the state that was found, not the state today.
The **Resolved / Decided / Evaluated** notes record where the outcome differed from what the
item proposed.

What remains is the same for all of it: none of this has run on a device, because there is still
no signing config (§2). The R8 shakeout item there is what turns "compiles" into "works".

- [x] **Declare `android:appCategory="game"`**
  With `targetSdk = 36`, Android 16 **ignores** `screenOrientation`, `resizeableActivity` and
  aspect-ratio restrictions on displays whose smallest width is ≥ 600dp — every tablet, unfolded
  foldable and desktop-windowed session. Games are exempt **only when the manifest declares
  `android:appCategory`**, which this one does not. One attribute on `<application>`, permanent
  and free. Re-read Google's current large-screen compatibility page to confirm the exemption is
  still worded that way, since this is platform-policy state that moves. Two corrections to
  earlier analysis: `PROPERTY_COMPAT_ALLOW_RESTRICTED_ORIENTATION` **is not a real property name**
  (a `<property>` the platform does not recognise is silently a no-op); the documented opt-out is
  `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`. And the consequence on tablets is a shrunken
  board and untested menu aspect — a quality problem — not a vanishing board: `tileSize` only
  clamps to 0 below roughly a 280dp viewport, which tablets and foldables never reach.

- [x] **Handle split-screen and free-form, where the board genuinely can vanish**
  `resizeableActivity` is undeclared and therefore true, so any user can drag the game into a
  half-height window mid-run. Below ~280dp of usable height `tileSize` clamps to 0 and `drawBoard`
  returns early — while the clock keeps counting and strikes keep landing. The review fixed the
  *crash* here, not the *behaviour*. Either declare `resizeableActivity="false"` or pause the run
  with a "make the window bigger" overlay below a minimum board size.

  **Resolved:** `resizeableActivity="false"` on the activity, so the window cannot be made
  unplayable in the first place — with `appCategory="game"` above it, Android 16 honours that
  on large screens too. Belt and braces in the render loop as well, because free-form and
  desktop windowing do not always obey it: `GameRenderer.boardDrawable` is false whenever
  `tileSize` clamps to zero, and the loop then pauses the engine clock and idles instead of
  letting the run be lost behind a board it is not drawing.

- [x] **Initialise the Ads SDK from the entitlement collector and from Settings consent**
  `ads.initialize()` is called from exactly one place — the `gatherConsent` callback — and returns
  early while `adsRemoved` is still null, which is the deliberate paid-player guard. If UMP
  resolves before the DataStore read lands, `initialised` stays false and nothing calls it again;
  the entitlement collector only calls `preload()`. Separately, a player who declines consent and
  later accepts via Settings → Ad privacy options never initialises either, because
  `showPrivacyOptions` only calls `publishConsentState()`. Fix is three lines: make the entitlement
  collector call `if (removed == false) ads.initialize()` (initialize already preloads on ready),
  and add `if (consent.canRequestAds) ads.initialize()` to the `showPrivacyOptions` callback.
  **Calibration:** the consequence is milder than it first looks — the merged manifest declares
  `MobileAdsInitProvider`, so the SDK self-initialises at process start and loads still fill, and
  `showThen` preloads on every miss, so the Settings path costs at most one interstitial. This is a
  correctness fix (Google's documented precondition, and mediation adapters that would never
  initialise), not a revenue-outage fix.

  **Fixed as described, with the consequence stated correctly:** the merged manifest declares
  `MobileAdsInitProvider`, so the SDK self-initialises at process start and loads were still
  filling. The real exposure was Google's documented precondition being violated and mediation
  adapters never initialising — not the whole-session ad outage an earlier reading claimed.

- [x] **Stop telling paying players "AD MAY PLAY BEFORE NEXT SCREEN"**
  `UiState.adsAvailable` is assigned only from `consent.canRequestAds` and never consults
  `adsRemoved`, so a player who bought the ad-free unlock reads that caption after every single run
  forever, while no ad ever plays. Fix at the source —
  `adsAvailable = consent.canRequestAds && adsRemoved == false` — rather than at the call site, so
  no future caller repeats it.

- [x] **Fix the 60-second purchase settle window swallowing both recovery paths**
  The window exists to stop an `onResume` pass landing a revoke on top of a just-completed grant —
  a real race, correctly identified. But it guards the *whole* pass, and both recovery paths run
  through it: `ITEM_ALREADY_OWNED` calls `refresh("already-owned")`, which returns immediately
  without querying, so a reinstalled owner who taps "Remove ads" gets nothing granted; and
  `restore()` routes through `refresh("restore")`, so tapping Restore within 60 seconds answers
  "Couldn't reach Google Play" while Play is perfectly reachable. Move the elapsed check down to
  just before `bumpNotOwnedStreak()`/`revoke()`, or clear `purchaseLaunchedAtMs` at the top of both
  recovery paths. A grant-capable pass must never be suppressed.

- [x] **Give a PENDING purchase some feedback**
  Pending purchases are enabled correctly and PENDING rightly neither grants nor revokes — but on
  the purchase path there is no feedback at all: the listener calls `handlePurchase`, which returns
  silently for anything not PURCHASED. A player paying by cash, carrier billing or parental
  approval completes Play's sheet and sees nothing, with the Remove-ads button still sitting there.
  Surface it through `_lastCheck` or a small `SharedFlow` and reuse the existing "still being
  confirmed by Google Play" copy; ideally keep a row in Settings while `Check.Pending` stands, so
  it outlives a 3.5s toast.

- [x] **Add an OkHttp `callTimeout`**
  `SupabaseClient` sets `connectTimeout(15s)` and `readTimeout(20s)`, but `readTimeout` is
  per-read, not per-call — a trickling or captive-portal connection keeps a request alive far past
  20s, and the sign-in button sits on "Working…" with no cancellation path and no escape but the
  back gesture, which navigates away while the coroutine is still in flight. Add
  `.callTimeout(30, SECONDS)` and `.writeTimeout(15, SECONDS)`, and consider a visible cancel
  affordance on the auth and account sheets.

- [x] **Handle audio focus**
  Verified absent: no `requestAudioFocus`, no `AudioFocusRequest`, no listener anywhere. So the
  orchard loop plays at full volume over an incoming call or a maps prompt, and launching Whaaack!
  does not stop whatever the player already had running — both play at once. Add a focus request
  around music playback: pause on `LOSS_TRANSIENT`, duck to ~0.1f on `LOSS_TRANSIENT_CAN_DUCK`,
  stop and clear `desiredTrack` on `LOSS`, restore on `GAIN`, abandon in `release()`. All on the
  existing main-thread handler, and respecting the `lifecyclePaused`/`preparing` flags so it cannot
  reintroduce the illegal `start()` transition REVIEW.md #2 fixed. Leave SoundPool effects
  unfocused — short SFX are the standard exception.

- [x] **Stop the render thread at `ON_STOP` to keep the main-thread join short**
  `stopRendering()` joins with a deliberately unbounded loop and `surfaceDestroyed` then waits up
  to 48ms for the buffer drain — both on the main thread. That is correct, and `GameScreen`'s
  `DisposableEffect` normally makes the window elapse first. One path escapes it: **backgrounding
  during a run**, where the composition is not disposed, so `surfaceDestroyed` is first to call
  `stopRendering()` and the main thread blocks while the render thread may be inside
  `lockHardwareCanvas()`. Under GPU pressure that is an unbounded main-thread block on the most
  common lifecycle transition in the app — an ANR shape, and the most likely source of ANRs in this
  codebase. Register a lifecycle observer that calls `stopRendering()` on `ON_STOP`, the same trick
  `onDetachedFromWindow` already uses for the other path.

- [x] **Cap the render loop and raise the render thread's priority**
  The loop's only pacing is `lockHardwareCanvas()` blocking on the buffer queue, so it runs at the
  panel rate — 120fps, sometimes 144 — for a game whose fruit lives 430ms at its fastest. That is
  2–2.5× the GPU work and battery draw of 60fps for zero gameplay benefit, on top of roughly 3.5×
  full-screen overdraw per frame. Call `surface.setFrameRate(60f, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`
  (API 30+, guarded) plus a sleep-based limiter for older devices, and
  `Process.setThreadPriority(THREAD_PRIORITY_DISPLAY)` at the top of `RenderThread.run()`.
  Optionally honour `prefs.parallax` in the renderer too — today it only quiets the Compose menu
  backdrop, not the in-game orchard.

- [x] **Read the version from `BuildConfig` instead of hardcoding "1.0" twice**
  `SettingsScreen` renders "Whaaack! v1.0" and `AboutScreen` renders "Version 1.0", both literals.
  Bumping `versionName` will not change either, so the first hotfix ships as 1.0.1 while both
  screens still claim 1.0 — and with no crash reporter, the version a bug reporter reads off the
  About screen is your only way to know which build they are on. Use `BuildConfig.VERSION_NAME`,
  and add `VERSION_CODE` to About since that is what vitals key on.

- [x] **Replace the developer-facing string on the sign-in screen**
  `AuthScreen` shows end users the literal "Google sign-in needs its OAuth client configured — see
  docs/SETUP.md." No user can act on a repo path. Reword to something player-facing, e.g. "Google
  sign-in isn't available right now — use email instead."

- [x] **Decide what happens to an in-progress run on process death**
  Configuration changes and backgrounding are handled well — the engine shifts every deadline and
  resumes through a 2s countdown, covered by tests. Process death is unhandled: `WhaaackViewModel`
  holds all of `UiState` in a plain `MutableStateFlow` with no `SavedStateHandle`, and the engine's
  state lives on the render thread. A kill while backgrounded (routine on a 2 GB device holding
  ~4.6 MB of bitmaps) returns the player to a cold Home screen with the run *and the score* gone,
  because `recordLocalBest` only fires in `onRunFinished`. "A killed run is a lost run" is
  defensible for a session-length arcade game — but make it explicit, and at minimum persist
  `elapsedMs` on pause so the local best survives.

  **Decided: a killed run is a lost run — but not a lost score.** Restoring a half-finished
  reflex run across process death would be worse than starting over, so no `SavedStateHandle`
  work was done. What was unacceptable was losing the score too, since `recordLocalBest` was
  only ever reached from `onRunFinished`. `GameSurfaceView.stopRendering` now reports
  `Callbacks.onRunInterrupted(millis)` — after the render thread is joined, so the engine is
  quiescent — and the ViewModel banks it. Ranked scores are deliberately **not** posted from
  there: the run has not ended, and submitting would put a partial run on the board and burn a
  rate-limit slot. `GameEngine.survivedMillisIfLive()` carries the phase rule (OUTRO counts,
  OVER does not) and has five new unit tests.

- [x] **Refresh the renderer's density on a display-configuration change**
  `GameRenderer` captures `resources.displayMetrics.density` once in a field initialiser, and
  `density` is in the manifest's `configChanges` list — so changing Display size while the game
  screen is open leaves `dp()` stale and the whole board geometry proportionally wrong for the rest
  of the session. One line: re-read the density in `surfaceChanged`.

- [x] **Delete the state fields and APIs nothing calls**
  `UiState.backendConfigured` is computed and read by no screen; `UiState.assetsReady` is written
  and never read; `UiState.restoreNote` is never written or read at all;
  `BillingManager.lastCheck` and `isPurchasable()` have no callers; `ConsentManager.reset()` is
  documented as a test helper but is unreachable since no test covers it. Either delete each or
  wire it to the thing it was meant to drive (`backendConfigured` → a service-unavailable banner;
  `lastCheck` → the Settings restore explanation `restoreNote` was presumably for). Left as is,
  they read as unfinished work.

- [x] **Close the BillingClient in `onCleared()`**
  One `BillingClient` is built with auto-reconnection and held for the ViewModel's lifetime;
  `onCleared()` releases audio and drops assets but never touches billing. Add
  `fun close() { client.endConnection() }` and call it. Hygiene rather than a leak — the client
  uses applicationContext.

- [x] **Set an obfuscated account id on the purchase flow**
  Google recommends `setObfuscatedAccountId(...)` so Play's fraud detection can correlate purchases
  with in-app accounts. Pass a one-way hash of the Supabase user id — never the raw id, never an
  email — and omit it when signed out, since the Home upsell is offered to signed-out players too.

- [x] **Strip release logging, or drop the item**
  Eleven `android.util.Log` call sites ship: ten `Log.w` and one `Log.i`; there is no `v`, `d` or
  `e` anywhere. None leaks a token, email or key — the worst is Play Billing's `debugMessage` and
  GMA/UMP error text — so this is cosmetic. Note the obvious rule does not work:
  `-assumenosideeffects` on `v/d/i` would remove exactly one of the eleven. Either add `w` to the
  list (losing the diagnostics) or gate the noisy sites on `BuildConfig.DEBUG` individually.

  **Dropped, deliberately, with the reasoning recorded in `proguard-rules.pro`.** Eleven call
  sites ship and none leaks a token, email or key. Against that, there is no crash reporter, so
  a logcat capture on a bug report is the only diagnostic signal that exists for a failed
  purchase or a dead MediaPlayer. The obvious rule would not have worked anyway:
  `-assumenosideeffects` on `v/d/i` removes exactly one of the eleven, because there is no
  `Log.v`, `Log.d` or `Log.e` anywhere in the app.

- [x] **Adopt `PredictiveBackHandler`**
  With `targetSdk = 36` predictive back is on by default. Functionally the app is fine, but on
  every screen except Home the registered `BackHandler` consumes the gesture with no preview, so
  the transition is instant. **Correction:** this does *not* depend on bumping androidx —
  `PredictiveBackHandler` has shipped since activity-compose 1.8.0 and is present in the pinned
  1.9.3 (verified in the resolved artifact). Sequence it independently.

- [x] **Evaluate OkHttp 5 and add an explicit network security config**
  OkHttp 4.12.0 is two years old; the app uses only `Request`/`Response`/`Call` basics so the bump
  is low-risk, but re-verify it against the R8 shakeout since OkHttp is the one dependency the
  app's own rules try to influence. Separately, add
  `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"` to make the
  posture explicit rather than relying on a targetSdk-derived default. Do **not** pin certificates
  against a Supabase-hosted endpoint whose cert rotates.

  **Evaluated and taken: okhttp 4.12.0 → 5.1.0.** Checked against this app rather than its
  release notes — compiles, 20 tests pass, R8 full-mode release build with no missing keep
  rules, `lintVitalRelease` clean, and the `okhttp-android` artifact it now pulls transitively
  adds no permission to the merged manifest. `network_security_config.xml` is in place with
  `cleartextTrafficPermitted="false"`, and deliberately no certificate pinning: the endpoint
  is Supabase-hosted and its certificate rotates on Supabase's schedule.

---

## 4. Backend — Supabase

Four items in this section are already done: the migration
[20260814120000_write_grants_and_signup.sql](../supabase/migrations/20260814120000_write_grants_and_signup.sql)
is pushed to `pklrfcbyseitdbxkmsnw` and verified against it. They are kept, ticked, so the
reasoning survives.

- [x] **Fix the Supabase SMTP password** — **done, verified against the live project**
  Auth email does not send at all today; signup, password reset and email-change confirmation all
  fail with `Error sending confirmation email`. This gates the test account for Play's App access
  form, your internal testers, and every smoke test you want to run — so it is first among equals.
  Paste the Resend key directly at Supabase → Authentication → Emails → SMTP Settings
  (host `smtp.resend.com`, port 587, user `resend`, password = the key from `secrets/resend.txt`,
  sender `noreply@idct.tech`, name `Whaaack!`). Do **not** rely on `supabase config push` alone —
  [SETUP.md §2](SETUP.md) documents why a secret-only push is silently a no-op. Verify with the
  curl in that section, and cross-check Resend → Logs.

  **Verified 2026-08-14 against `pklrfcbyseitdbxkmsnw`**, after the key was rotated and pasted
  into the dashboard. Two paths exercised end to end: `POST /auth/v1/recover` for an existing
  account returned **200** in 2.1s — a real SMTP round trip, not a local failure — and
  `POST /auth/v1/signup` for a fresh address returned **200** with a user id, a stamped
  `confirmation_sent_at` and **no** `access_token`, which is exactly the shape meaning "account
  created, confirmation mail accepted, waiting on the click". That request also confirmed the
  rewritten `handle_new_user()` trigger on a real signup: the profile was created with the
  display name intact. The test account was deleted afterwards; the project is back to the two
  accounts it had. The Resend key answers **403** to a list request, so it is still send-only.

  Two things follow from this rather than being fixed by it. The `email_sent = 10` per hour
  **project-wide** limit is now the binding constraint rather than a broken mailer — see the next
  item. And `supabase config push` is a live hazard: `config.toml` still carries
  `pass = "env(RESEND_API_KEY)"`, so a push with that variable unset writes the literal string
  `env(RESEND_API_KEY)` over the password that was just fixed.

- [ ] **Push the Google provider and publish the OAuth consent screen to Production** 🔴
  `[auth.external.google] enabled = true` is set locally; the remote does not have it. Run, with
  all three exported in one shell:
  `GOOGLE_CLIENT_ID="<web>,<android>" GOOGLE_CLIENT_SECRET="<web secret>" RESEND_API_KEY="<key>"
  supabase config push`. Any omitted `env(...)` is pushed as its literal text, which is why the
  Resend key must be present even though it looks unrelated — **and SMTP is working now, so this
  push is the single most likely way to break it again.** Take the key from `secrets/resend.txt`,
  and re-run the signup check from the SMTP item straight afterwards to prove it survived.
  Then publish the consent screen in
  Google Cloud → APIs & Services: while it is in **Testing**, only explicitly listed test accounts
  can sign in, so every closed tester and every Play reviewer who taps "Continue with Google"
  fails. Authorized domains: `idct.tech` as bare eTLD+1, nothing Supabase-related. Upload the app
  logo — in the native Credential Manager flow it is what appears on the account-picker sheet, and
  a blank logo reads as a phishing prompt.

- [ ] **Raise the auth email rate limit before inviting anyone** 🔴
  `email_sent = 10` is **per hour for the whole project**, not per user, and below Supabase's own
  default of 30 for custom SMTP. Every confirmation, reset and email change shares that bucket, and
  `double_confirm_changes = true` makes one email change cost two sends. Inviting 12 closed testers
  in one evening puts testers 11 and 12 over the limit, and `AuthRepository.translate()` has no
  `over_email_send_rate_limit` case so they see "Something went wrong". Raise to 200–300/hour, add
  a 429 branch to `translate()`, and check Resend's own ceiling too — the free tier is 100/day and
  3,000/month, and a launch spike silently breaks account creation after the hundredth signup.

- [x] **Revoke client INSERT on `scores.created_at`** — **done, pushed and verified**
  Nothing constrained it. Supabase's default grants give `authenticated` INSERT on every column
  and the RLS `WITH CHECK` only tests `user_id`, so any player could POST a score with
  `"created_at":"1970-01-01T00:00:00Z"` — the app's own submit payload with one extra key. Both
  leaderboard functions order by `millis desc, created_at asc`, so a 1970 timestamp wins every tie
  permanently, and a far-future timestamp pins a score to the weekly board forever. Fixed in
  [20260814120000_write_grants_and_signup.sql](../supabase/migrations/20260814120000_write_grants_and_signup.sql):
  the table-level INSERT/UPDATE/DELETE grant is revoked and INSERT granted back on
  `(user_id, millis, hits, top_speed)` only — a column-level `REVOKE` cannot subtract from a
  table-level `GRANT`, which is why the table privilege had to go first. Verified the app sends
  exactly those columns. Pushed to `pklrfcbyseitdbxkmsnw` and verified against it: as the `authenticated` role, a
  score POST carrying `created_at` is refused with 42501, as are UPDATE and DELETE on scores,
  while the app's own `{millis, hits, top_speed}` payload still succeeds.

- [x] **Restrict the `profiles` UPDATE grant to `display_name`** — **done, pushed and verified**
  The policy authorises the whole row and `authenticated` held UPDATE on every column, so the
  30-day rename cooldown fell to two requests: first PATCH `{"display_name_changed_at": null}`
  (display_name unchanged, so the trigger's `if` never fires and the null is written), then PATCH
  the new name against a now-null old value. The same grant let a player set `provider` to
  `'google'` — which drives `Player.isGoogle` and therefore which credential controls Settings
  offers — and rewrite `created_at`. Same migration: table-level write grants revoked, `update
  (display_name)` granted back. The cooldown trigger still stamps `display_name_changed_at`,
  because a trigger's writes to `NEW` are not checked against the caller's column privileges.
  Pushed and verified: as `authenticated`, both
  `{display_name_changed_at: null}` and `{provider: "google"}` are refused with 42501, while a
  plain `{display_name}` rename still succeeds.

- [x] **Make `handle_new_user()` unable to fail** — **done, pushed and verified**
  Three ways it could abort a signup, one of which fires on ordinary names. **(1)** The sanitiser
  stripped characters outside `[A-Za-z0-9 ._-]`, but `display_name_shape` also demands the first
  *and last* characters be alphanumeric — so `-bartek-`, `_dev`, `x.` or `._.` produced a
  candidate the constraint rejected, the trigger raised, the `auth.users` insert rolled back, and
  signup returned 500. Truncating a long name to 24 could land on a `.` or `-` and do the same.
  **(2)** `while exists(...)` then INSERT is check-then-act, so two concurrent signups resolving
  to the same name both cleared the loop and one hit the unique index. **(3)** A null `email` on a
  provider sending no name made `split_part(null, …)` null, and the null reached a not-null
  violation. Same migration rewrites the function: truncate-then-trim-the-edges sanitising, a
  bounded try-and-catch insert loop that distinguishes `profiles_pkey` from
  `profiles_display_name_key`, and a `'Player ' || <12 hex of the uuid>` last resort. It now never
  raises — an odd display name is recoverable in Settings, a blocked signup is not. Both the SQL
  grammar and all 14 sanitiser cases were verified locally. Pushed and verified against the live database: all eight previously-fatal signups now
  provision a profile — `-bartek-`→`bartek`, `_dev`→`dev`, `._.`→`Player`, `x.`, `李雷`, a
  24-char name truncated onto a `.`, and a row with no metadata at all. Three deliberately
  colliding signups produced `Collide`, `Collide 2`, `Collide 3` — the retry path that used to
  throw 23505. All test rows were removed afterwards; the database is unchanged.

- [ ] **Trace the forgot-password flow end to end, and settle `secure_password_change`** 🔴
  Nobody has followed the whole path. `sendPasswordReset` posts `/auth/v1/recover?redirect_to=
  whaaack://auth`; the deep link resolves the user, saves the session, and for `type == "recovery"`
  navigates to Settings telling the player to set a new password there; Settings then calls
  `updatePassword`, which issues a bare `PUT /auth/v1/user` with **no nonce** while config.toml
  sets `secure_password_change = true`. `translate()` has no `reauthentication_needed` case, so a
  rejection surfaces as "Something went wrong" on the one screen the player was just told to use —
  and a locked-out player then has no route back except the website contact form.
  **Test before changing config:** GoTrue's reauthentication requirement carves out
  recently-created sessions (historically 24h), which is exactly the state you are in when you sign
  in and immediately try changing your password — so a naive test will make this look fine while
  the real defect only shows for a returning user. Verify with a deliberately aged session, then
  either implement the `POST /auth/v1/reauthenticate` + nonce round trip (which costs another
  email against the send budget) or set `secure_password_change = false` and push. Two fixes are
  unconditional either way: add the `reauthentication_needed` case to `translate()`, and stop
  `changePassword` toasting "Password updated" without confirming the server changed anything.
  Also check the recovery landing for a Google-provider account, since Settings gates the password
  control on provider.

- [ ] **Move to a paid Supabase plan** 🔴
  The project (`pklrfcbyseitdbxkmsnw`, Postgres 17.6, aws-1-eu-west-1 — good, the data stays in the
  EEA) is on Free: no self-serve restorable backup for a GDPR-controller database, **one day** of
  log retention, and automatic pausing after seven days of inactivity — a live risk in the window
  between now and launch. Pro brings daily backups with 7-day retention, no pausing, 7-day logs,
  and the `[auth.sessions]` controls config.toml already wants. The one-day retention also makes
  the Art. 33 breach procedure ([non-technical plan](GO-TO-PRODUCTION-NON-TECHNICAL.md) §4) unexecutable: an incident discovered Wednesday about something
  that happened Monday is uninvestigable. PITR is a separate add-on; daily backups are proportionate
  for a leaderboard — decide explicitly and write it down. Independently, set up an out-of-band
  `supabase db dump` to storage you own, so a closed or compromised Supabase account is not also
  the loss of the data.

- [ ] **Build the UGC moderation story** 🔴
  A publicly readable leaderboard of user-chosen names is user-generated content under Play's UGC
  policy the moment you answer "users can share content" on the IARC questionnaire — and that
  policy requires an in-app mechanism to report objectionable content and users, a way to remove
  it, and a stated moderation process. Today `display_name` is constrained only structurally
  (2–24 chars, must start and end alphanumeric), so a slur or a phone number passes the regex and
  is broadcast to every player, and there is no report UI anywhere. Three pieces: **(1)** a
  long-press or overflow "Report this name" on each leaderboard row posting to a new insert-only
  `reports` table (or at minimum deep-linking the contact page with the name prefilled);
  **(2)** a `BEFORE INSERT OR UPDATE` trigger on profiles rejecting a normalised match against a
  denylist and reserved words (`admin`, `moderator`, `whaaack`, `idct`, `google`, `support`),
  raising a distinct error the app maps to a new `AuthError`; **(3)** a tested admin runbook —
  delete the scores, neutralise the name, ban via `PUT /auth/v1/admin/users/<id>` with
  `ban_duration`. All three need the service key, so decide where it lives before you need it at
  2am. This rarely blocks first review; it is what gets an app suspended after one complaint.

- [ ] **Set a server-side password policy and enable leaked-password protection** 🟡
  `isStrongPassword` enforces 8 characters and a digit — but only inside the APK. GoTrue's default
  `minimum_password_length` is 6, so anyone hitting `/auth/v1/signup` directly (or an older APK
  still installed after you tighten the client) creates a weak account that RLS then trusts for
  life. Add `minimum_password_length = 8` and a `password_requirements` string to `[auth]` and
  push. Turn on the HaveIBeenPwned check at Authentication → Policies — it is a Pro feature, so
  fold it into the plan upgrade.

- [x] **Pin `search_path` on the two remaining unpinned functions** — **done, pushed and verified**
  Every `SECURITY DEFINER` function was correctly pinned already. `enforce_display_name_cooldown()`
  and `current_week_start()` were not — neither is SECURITY DEFINER so neither was a live
  escalation path, but both are flagged by Supabase's Security Advisor, and `current_week_start()`
  is called from inside a SECURITY DEFINER function, which is exactly the shape that becomes a
  real vulnerability the day someone adds `security definer` without noticing. Two `alter function
  … set search_path = public, pg_temp;` lines, folded into the same migration since it is the same
  privilege-hygiene pass. Make "every function in public is pinned" a review rule rather than a
  per-function judgement. Pushed and verified: all seven of the project's own functions report
  `search_path=public, pg_temp`, and `supabase db advisors --type security --level warn` now
  returns no findings.

- [ ] **Author branded email templates in the repo** 🟡
  There are no `[auth.email.template.*]` blocks, so every message goes out as Supabase's stock
  grey "Confirm your signup". Two problems: a brand-new sending domain emitting a generic template
  with one link and no plain-text alternative is a strong spam signal, on exactly the message
  players must click; and `supabase config push` sends the whole auth body it computes from
  config.toml, so any template authored in the dashboard is silently reset on the next unrelated
  push. Put them in `supabase/templates/` and reference them by `content_path`. Include the app
  name, the physical sender identity, and a privacy link.

- [ ] **Complete deliverability for `idct.tech`: SPF, DKIM, DMARC, bounces** 🟡
  Resend domain verification proves DKIM and the return-path exist; it says nothing about SPF,
  DMARC, or what happens to a bounce. Confirm all three records resolve, add DMARC at `p=none`
  with an `rua=` address and tighten once reports are clean, and decide the reply path —
  `noreply@idct.tech` must accept-and-discard or bounce cleanly, because a sender that hard-bounces
  on reply damages reputation and strands users. Then wire bounce visibility: Resend maintains a
  suppression list but nothing tells *you* that a signup's confirmation bounced — the user just
  never gets in. A webhook, or a scheduled check of Resend → Logs.

- [ ] **Harden the auth redirect** 🟡
  Three gaps. **(1)** `whaaack://` is a custom scheme any installed app can claim, and the recovery
  link carries `access_token` and `refresh_token` in the fragment — a malicious app registering the
  scheme silently harvests a full session. **(2)** `https://idct.tech/whaaack/auth` is already in
  `additional_redirect_urls` but **does not exist**, so anyone confirming from a desktop browser
  gets a 404. **(3)** The default `{{ .ConfirmationURL }}` is a single-use verify link, and
  corporate link-scanners prefetch URLs in email and burn the token — the classic "token expired"
  report from users who clicked once. The cleanest single move for a mobile-first app: switch the
  confirmation and recovery templates to emit `{{ .Token }}` (a 6-digit code) and POST it to
  `/auth/v1/verify` from the app, which removes the deep-link surface, the desktop 404 and the
  prefetch problem together. If you keep deep links instead, publish
  `website/.well-known/assetlinks.json` with both release SHA-256 fingerprints, add an
  `autoVerify="true"` filter for `https://idct.tech/whaaack/auth`, and create that page.

- [ ] **Fix the email-change copy for `double_confirm_changes`** 🟡
  With double confirmation on, GoTrue sends a link to the **old** address as well and the change
  completes only when both are clicked — but the app toasts "Confirmation link sent to <new>".
  Users who changed email *because* they lost the old inbox are permanently stuck with no error to
  explain it. Either keep the setting and say "We sent a link to both — open both to finish", or
  turn it off and push. Each change also costs two sends against the email budget.

- [ ] **Implement a real Art. 15/20 data export** 🟡
  Privacy §7 promises access with a copy and portability within one month, and nothing implements
  it — you would be hand-writing SQL under time pressure the first time someone asks. Add
  `public.export_my_data()`, SECURITY DEFINER, pinned, granted to `authenticated` only, returning
  one jsonb with the profile row, every score row, and the identity fields the user gave you
  (email, created_at, last_sign_in_at, provider) — deliberately not the password hash. Either
  surface it in Settings as "Download my data" or keep it operator-only and document the exact
  invocation. Separately verify the deletion is as complete as claimed: `auth.audit_log_entries`
  has no FK to `auth.users` and its payload carries the email and IP, so those rows survive —
  check whether your project prunes them, and if not either add the prune to the runbook or amend
  the "encrypted backups may hold traces" wording so it is honest.

- [ ] **Establish a staging project and a migration workflow** 🟡
  There is exactly one project, and every migration so far was developed against production and
  verified after the fact. That was fine with one test player; with real accounts on the board, a
  bad `create or replace` on `leaderboard()` is a user-visible outage with no rehearsal. Create a
  staging project in the same region, make "apply to staging, run the probes, then production" the
  rule, add a CI job that applies every migration to a throwaway local database on each PR touching
  `supabase/`, and take a `db dump` before every production push. Also note: `supabase config push`
  sends the whole auth body computed from config.toml, so any auth setting changed in the dashboard
  and not mirrored back into the file is silently reverted on the next push. Treat config.toml as
  the single source of truth.

- [ ] **Put something in front of the backend that will tell you when it breaks** 🟡
  `LeaderboardRepository.submit` wraps the whole insert in `runCatching { }.isSuccess` and returns
  a boolean nobody surfaces, so a constraint violation, the rate-limit trigger, an expired session
  and a total outage all look identical to the player and invisible to you.
  `refreshProfile` swallows IOException too. Minimum viable: connect Supabase's Slack/webhook
  alerts and enable project-health notifications; save Log Explorer queries for auth errors
  (`Error sending`, `429`, `invalid_grant`) and PostgREST 4xx/5xx; make `submit()` stop lying by
  logging the status and error code and telling the player the score was not recorded; and set the
  Resend bounce check from the deliverability item.

- [ ] **Write the key inventory, and record that the publishable key cannot be rotated** ⚪
  The project already uses the new `sb_publishable_…` format, so the 2026 legacy-key deprecation
  does not apply, and no secret has ever been committed (verified). What is missing is the
  operational note: the publishable key is compiled into every installed APK, so "rotate the key"
  is not an incident-response lever — it cuts off every user who does not update. Your only real
  controls are RLS and the rate limits, which is itself the argument for the column-grant fixes
  above. Write the inventory into SETUP.md: each key, where it lives, and what you would actually
  do if it leaked. Note that the Resend key is the one rotation that requires the dashboard rather
  than a config push. Plan the asymmetric JWT signing-key migration for a quiet period, not launch
  week.

- [ ] **Trim what the anon key can read, and check the PostgREST row ceiling** ⚪
  `leaderboard()` returns `user_id` for every ranked player to anyone with the anon key, and the
  client parses it but the UI never needs a uuid — `my_standing()` already answers "which row is
  mine". Dropping it removes a free enumeration of auth uuids for zero cost. **Correction on the
  second half:** config.toml having no `[api]` section does *not* mean `max_rows` is unbounded —
  hosted Supabase applies a project default (1000). Check Project Settings → API → Max rows first;
  if it already reads 1000 the guard is in place and lowering it to 200 is optional tightening.
  Also confirm your CLI actually pushes `[api]` settings before assuming a config.toml edit takes
  effect.

- [ ] **Get the account-gated paths working *before* the first tester install** 🔴
  Not a 14-day gate any more, but the dependency chain behind it is unchanged and it is still the
  thing that wastes a week. As of today, on a Play-installed build: email signup returns "Error
  sending confirmation email"; Google sign-in returns no credential for **every** installed copy,
  because the Play App Signing SHA-1 is not on the OAuth client; and Google sign-in additionally
  fails for anyone not on the consent screen's Testing allow-list. Ranked play, the leaderboard,
  my-standing, display-name changes and the entire billing test all sit behind an account, so
  until this chain is closed an internal build can only exercise casual play. Order it: SMTP →
  raise `email_sent` → `config push` → publish the consent screen to Production → keystore →
  a throwaway internal upload purely to mint the Play signing key → register both SHA-1s → verify
  signup and Google sign-in from a Play-installed build → **then** hand it to anyone.

---

## 5. Ads and billing — the code side

The console side of both — ad unit types, COPPA flags, creating the product — lives in the
[non-technical plan](GO-TO-PRODUCTION-NON-TECHNICAL.md). These are the parts that need a build.

- [x] **Set a maximum ad content rating** — **code side done; the console half is yours**
  `maxAdContentRating` is UNSPECIFIED, so AdMob may serve up to MA-rated creatives — gambling,
  dating, alcohol — inside a cartoon fruit game that will carry PEGI 3 / Everyone. Play's Ads
  policy requires ads appropriate to the declared content rating, so this is policy exposure, not
  taste. Set it in **both** places so a console change cannot be undone by a rebuild and vice
  versa: `MobileAds.setRequestConfiguration(…MAX_AD_CONTENT_RATING_G…)` before `initialize`, and
  AdMob → Blocking controls → Ad content rating. While there, block the categories that generate
  one-star reviews in a family-friendly game.

  **Done in code: pinned to `MAX_AD_CONTENT_RATING_G`.** G rather than PG because it matches the
  Everyone / PEGI 3 rating this game expects and the 13+ audience declared on Play, and because
  G is the setting that excludes gambling, dating and alcohol outright. It narrows the demand
  pool and costs some eCPM — the deliberate price of not gambling the rating. The value is a
  single named constant in `AdsManager`.

  Two implementation details that are load-bearing. It is applied in the **constructor**, not
  inside `initialize()`: the merged manifest declares `MobileAdsInitProvider`, so the SDK can
  already be up before anything in that class runs, and this must be in force before the first
  *request* — a different moment from initialisation. And it is built from
  `MobileAds.getRequestConfiguration().toBuilder()` rather than a fresh `Builder`, so it cannot
  silently clear something set elsewhere; test device ids are the obvious casualty of getting
  that wrong. The whole API surface was checked with `javap` against the shipped
  `play-services-ads-api-25.4.0.aar` rather than assumed.

  **Remaining, and it is yours: set the same ceiling in AdMob → Blocking controls**, plus the
  sensitive categories. Neither half is sufficient alone — the console can serve a rating the app
  never asked for, and a rebuild can override what the console says. Whichever is stricter wins,
  and the two drifting apart is how a family-friendly game ends up carrying a dating ad. Keep
  this constant, the console setting and the IARC answers in step.

- [ ] **Handle US state privacy** 🟡
  The UMP integration is GDPR-only in intent and wording: the KDoc says "GDPR / IAB TCF", the debug
  helper hardcodes `DEBUG_GEOGRAPHY_EEA` with no way to test another geography, and the Settings row
  is GDPR-phrased. The privacy policy has no US section at all. Serving personalised ads to a US
  user is "sharing for cross-context behavioural advertising" under CPRA and "targeted advertising"
  under the Colorado/Connecticut/Texas/Oregon/Montana/Delaware family, all of which require an
  opt-out. Three things: publish AdMob's **US states** message (no app code needed for the message
  itself); widen `ConsentManager.gather` so the debug geography is a parameter — UMP 4.0.0 exposes
  `DEBUG_GEOGRAPHY_REGULATED_US_STATE` and you cannot test the US flow without it; and reword the
  Settings row plus add a US/state-privacy section to the policy covering categories, the sharing
  disclosure and how to opt out.

- [ ] **Harden the interstitial trigger against accidental clicks and cap it server-side** 🟡
  The placement is already policy-clean in the ways that matter and each was verified: no ads
  during a run, no app-open format, the Back button on game-over routes home with no ad, music
  pauses because the ad's activity triggers `onPause`, a 120-second client cap, an honest "AD MAY
  PLAY" warning, and a 500ms button debounce. The residue is the tap-through: `show()` is called
  synchronously inside the click handler, so the ad's surface appears under a finger still moving,
  and the debounce only suppresses a second tap on the *Compose* button — once AdActivity is up, a
  fumbled tap lands on the creative and Google sees an accidental click. Disable both buttons on
  first tap and post `show()` ~500–750ms later behind a brief loading state. Separately add a
  frequency cap on the ad unit in AdMob: the client cap is in-memory only, so a player who
  force-quits between runs bypasses it entirely.

- [ ] **Move the consent prompt behind the first frame** ⚪
  `gatherConsent` runs in `onCreate` before `setContent`, so a first launch in the EEA opens with a
  210-partner TCF dialog over a blank window. Consent only has to precede the first ad *request*,
  and the first ad cannot be offered until a run finishes — at minimum thirty seconds away. Move it
  into a `LaunchedEffect(Unit)` so the orchard and home screen paint first. Costs nothing legally
  and meaningfully improves install-to-first-play on EEA traffic. Keep the existing single-shot
  guard.

- [ ] **Stop putting paying players through the consent flow** ⚪
  An EEA player who paid specifically to remove ads is still shown the full GDPR form on launch and
  then offered a permanent Settings row about personalising ads they will never see. Not a
  violation, but it undercuts the thing they bought. Gate `gatherConsent` on the entitlement
  resolving to `false` (the ViewModel already collects it) and hide the privacy row when
  `adsRemoved == true`, keeping null meaning "don't ask yet". Accept that on a fresh install the
  form still appears once before Play confirms ownership — unavoidable given the deliberate design.

- [ ] **Handle interstitial expiry and persist the cap** ⚪
  Load failure, no-fill and offline are all handled correctly — verified path by path. Three
  refinements: a cached interstitial expires after about an hour and `ad` is held indefinitely with
  no timestamp, so one preloaded at launch and offered after a long session always fails to show
  (handled gracefully, but the impression is wasted) — record `loadedAtMs` and discard past ~50
  minutes; call `preload()` from `onAppResumed()` so returning from background refills a slot that
  expired or was never filled offline; and persist `lastShownAtMs` so the two-minute cap survives
  process death. Also consider skipping the very first ad of a fresh install — a new player who
  finishes a thirty-second first run and immediately gets a full-screen ad is the churn case
  Google's own guidance warns about.

- [ ] **Pin `REMOVE_ADS_PRODUCT_ID` explicitly and document billing in SETUP.md** 🟡
  Add it to `local.properties` (and to CI) so the shipped id cannot silently diverge from the
  console. SETUP.md has no billing section at all and its checklist has zero billing items; add
  one covering the product id, the licence-tester list, and the refund/revoke runbook.

- [ ] **Run the whole purchase flow on the internal track with licence testers** 🔴
  Billing has never been exercised against real Play — every path below is unproven on a device,
  and Play only answers for a build signed with the key of a version published on a track. Add the
  tester accounts under Setup → Licence testing (they get free purchases plus the always-approves,
  always-declines and slow test cards), upload a signed AAB to Internal testing, opt in through the
  tester link, install **from Play**, then walk the matrix: (a) buy with always-approves → ad-free
  grants and Settings shows the crown row; (b) uninstall and reinstall → the cold-start pass
  re-grants with no user action; (c) tap Buy while already owning → exercises `ITEM_ALREADY_OWNED`
  (see the settle-window fix in §3); (d) buy with the slow card → PENDING (see the feedback fix);
  (e) refund **and revoke** in Order management, then foreground twice online → the entitlement
  drops only after two verified-online negatives; (f) airplane mode while holding it → stays
  ad-free. To re-test a purchase you must refund+revoke, since the app never consumes it.

- [ ] **Confirm the offer model matches `launchPurchase`** 🟡
  `launchPurchase` passes no `setOfferToken` and the price reads the singular, backwards-compatible
  `oneTimePurchaseOfferDetails`. Keep the product to a single plain "buy" purchase option so Play
  exposes a backwards-compatible offer. If the console model produces offers with no
  backwards-compatible entry, the singular accessor returns null and the button silently never
  appears — the fix then is to read `oneTimePurchaseOfferDetailsList`, take the intended offer, and
  pass its token.

- [ ] **Verify acknowledgement live** 🟡
  Verified in source: the code **does** acknowledge, from both paths that can see a purchase, with
  three retries and exponential backoff, deliberately leaving `isAcknowledged` false on failure so
  the next pass retries — and there is no `consumeAsync`, which is right for a permanent
  entitlement. The residual exposure is that the retry loop runs in `viewModelScope`, so recovery
  depends on the player reopening the app within Play's 3-day auto-refund window. Low risk since
  they just bought it in-app, but confirm no "acknowledge failed" line appears during the internal
  test, and stress it by force-stopping the app the instant Play's sheet closes.

- [ ] **Prove the reinstall / new-device restore** 🟡
  The entitlement is stored **only** locally, in its own DataStore file deliberately kept out of
  the settings store and out of the backup rules, so a cloud backup or device transfer cannot clone
  a purchase — durability comes from Play, via a cold-start pass and an onResume pass, plus an
  always-visible "Restore purchases" row. For a cosmetic unlock with no server cost that is the
  right trade, and it matches what the privacy policy already tells users. No code change expected;
  what remains is proof: reinstall and confirm ad-free returns with zero user action, and confirm a
  second device on a different Google account does **not** get it.

- [ ] **Re-check the Billing Library minimum each August** ⚪
  Pinned at 9.1.0 using the modern surface, comfortably above any current floor. Google raises the
  minimum on a rolling annual schedule with an end-of-August cut-off, so set a yearly reminder
  rather than treating this as done.

- [ ] **Put the entitlement decision table under unit test** ⚪
  `refresh()` holds the whole grant/revoke policy — probe-before, probe-after, PENDING,
  UNSPECIFIED_STATE, the two-confirmation streak, the offline grace, the settle window, the
  `seq < lastGrantSeq` clobber guard — and none of it is covered, because it talks to
  `BillingClient` directly. Extract the decision into a pure function over (setup code,
  online-before, query code, purchase state, holding, streak) and table-test it. This is the one
  piece of logic in the app where a silent regression takes a paid feature away from a paying
  customer — and the settle-window bug above is exactly the class of defect such a table catches.

---

## 6. The website — markup, CSS and CI

The *content* of the legal pages is in the [non-technical plan](GO-TO-PRODUCTION-NON-TECHNICAL.md);
these are the structural and delivery problems in `website/` and `.github/workflows/pages.yml`.

- [ ] **Verify the contact form end to end** — **tested: the captcha is NOT enforced; one dashboard toggle left** 🔴
  The form posts natively to Web3Forms with a honeypot and an hCaptcha widget, and the redirect is
  asserted in CI. But the sitekey is Web3Forms' **shared free-plan** hCaptcha key, and Web3Forms
  only validates the token server-side if hCaptcha is switched on for this form in their dashboard
  — until then the widget is decoration and the access key (necessarily public, with no domain
  allow-list on the free plan) can be POSTed from any origin. The page also loads hCaptcha's
  `api.js` directly instead of Web3Forms' documented script, so with a native form POST a failed
  challenge returns a raw JSON error page with no way back. Confirm the key still resolves to a
  monitored mailbox and that the mail is not being spam-filtered — a silently dead form means
  Play's data-deletion URL does not work. Test both the happy path and a bare `curl` POST.

  **Tested, and the suspicion was right: hCaptcha is not enforced.** A bare POST from this
  machine was refused, but not for the reason that matters — Web3Forms rejects server-shaped
  calls on the free plan (*"Use our API in client side"*), which is a weak heuristic, not a
  captcha check. Repeating it with browser-shaped headers (`Origin`, `Referer`, an ordinary
  user-agent) and **no captcha token at all** was **accepted**: Web3Forms answered 303 to
  `/whaaack/contact/thanks/`, which is its success path. So the widget on the page is
  decoration, and the form — the only out-of-app route for a GDPR or deletion request, and the
  address Play will check — takes scripted submissions from anything shaped like a browser.

  **Remaining, and only you can do it: switch hCaptcha on for this form in the Web3Forms
  dashboard**, then repeat the same browser-shaped POST and confirm it is refused. Two test
  messages from this check are in the inbox; both say so in the subject line. While there,
  confirm the access key still routes to a mailbox somebody reads and that Web3Forms mail is not
  being spam-filtered — a silently dead form fails Play's data-deletion requirement without ever
  announcing itself.

  **Done here:** the page no longer dead-ends on failure. It keeps a native POST so it still
  works with JS off, and the cost of that was the error path — the browser followed the POST to
  `api.web3forms.com` and rendered raw JSON, unstyled, with no way back, on the page somebody
  reaches when they want their data deleted. When JS is available (which it must be for the
  captcha to have rendered at all) the submit is intercepted, the same request made in the
  background, and failures reported inline; the browser never leaves the page. The captcha
  container also has a real label now instead of being an unlabelled box.

- [x] **Add a branded 404 page** — **done**
  There is no `website/404.html`; `https://idct.tech/whaaack/anything` returns GitHub's generic
  "Page not found" with no site chrome and no way back. The mechanism is already proven on this
  domain by sibling projects. Use the same header/footer/orchard chrome with **absolute** asset
  paths (`/whaaack/assets/…`), because the page is served for URLs at any depth and relative paths
  will 404 at every level but one. Add it to the required-files loop in `pages.yml`.

  **Done.** `website/404.html`, same chrome, every path absolute. The premise was checked rather
  than trusted: `/chromis/nope` returns GitHub's generic page, which made the claim look shaky —
  but `/helena/`, `/nuts/` and `/gentastic/` all return branded 404s, confirming a project-level
  `404.html` really is served for arbitrary depths under the path prefix. Marked `noindex`, and
  added to the required-files check.

- [x] **Publish `website/sitemap.xml`** — **done; the apex index entry is one line in another repo**
  The apex already has the machinery — `robots.txt` points at a sitemap **index** aggregating one
  per subsite — and it lists helena, gentastic, chromis and nuts, but **not whaaack**;
  `/whaaack/sitemap.xml` is a 404. Every page already carries a correct canonical and the thanks
  page is correctly noindex. Create the sitemap listing the five canonical URLs, add the
  `<sitemap>` entry plus the subsites data entry in the `ideaconnect.github.io` repo, and add the
  file to the workflow's required list.

  **Done here:** `website/sitemap.xml` listing the five canonical URLs, following the sibling
  convention (`/chromis/sitemap.xml`) down to the explanatory header. `contact/thanks/` and
  `404.html` are deliberately absent — both are `noindex` and neither means anything on its own.
  CI now asserts the sitemap lists exactly the set of canonicals, so a page added to the site and
  forgotten here fails the build instead of quietly never being indexed.

  **Remaining, in `ideaconnect.github.io`:** add
  `<sitemap><loc>https://idct.tech/whaaack/sitemap.xml</loc></sitemap>` to the index and the
  project entry to `_data/subsites.yml`. Confirmed today that the index still lists only helena,
  gentastic, chromis and nuts.

- [x] **Restore visible keyboard focus on the site** — **done**
  `site.css` sets `.field input:focus { outline: 0 }` and replaces the ring with a 1px border tint,
  and the only other focus rule in 335 lines is `.skip-link:focus`. Keyboard and switch users get
  **no visible focus indicator** on any nav link, footer link, button or the submit control — a
  WCAG 2.4.7 failure, and a 2.4.11 appearance failure on the 1px swap. Add a global
  `:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px }` and keep a real ring
  on the inputs. While there, label the hCaptcha container and make a rejected submit communicate
  something other than raw JSON.

  **Done.** A global `:focus-visible` ring in the accent colour, plus a real ring on the form
  inputs in place of the 1px border tint. `:focus-visible` rather than `:focus`, so a mouse click
  does not leave a ring behind it — the indicator is for keyboard and switch users, who are
  exactly who it was missing for. The captcha label and the submit-failure handling are covered
  in the contact-form item above.

- [x] **Page metadata and asset weight** — **done**
  Privacy, terms, delete-account and contact have no OpenGraph tags, so sharing a legal link in a
  chat renders as a bare URL. Every page loads the 165 KB `whaaack-512.png` as both favicon and the
  30px header logo — ship a 32px PNG and an `.ico` for the tab and a ~64px logo for the header.
  The four screenshots total ~310 KB and are all eager; add `loading="lazy" decoding="async"` to
  the `#shots` images. And `website/assets/img/bg/splat{03,11,19,27}.png` are referenced by no CSS,
  JS or HTML — delete them.

  **Done, all four parts.** OpenGraph and Twitter tags on privacy, terms, delete-account and
  contact, so a legal link pasted into a chat or a ticket renders as a card rather than a bare
  URL. The 165 KB 512px PNG was serving as both the tab icon and a 30px header logo on every
  page; it is now a 1.9 KB 32px PNG plus a 6 KB `.ico` for the tab, and a 5.7 KB 64px PNG for the
  logo (64 so it still holds up at 2x), with the 512 kept only for `apple-touch-icon` and
  OpenGraph — **about 160 KB off every page load**. The four screenshots (~310 KB, all eager, all
  below the fold) now carry `loading="lazy" decoding="async"`. And the four unused
  `assets/img/bg/splat*.png` are gone, confirmed referenced by no HTML, CSS or JS.

- [x] **Extend the Pages workflow to guard the new invariants** — **done, and every guard was proven to fail**
  The workflow is a good model already. Add `404.html` and `sitemap.xml` to the required-files
  loop; assert each page's canonical matches its own path so a copy-paste cannot point two pages at
  one canonical; run a link checker over the built tree (there are cross-page anchors nobody tests
  — `/whaaack/#adfree` from privacy, `#termination`/`#conduct`/`#account` within terms — and a
  renamed heading id fails silently); and assert no `http://` URL and no unexpected third-party
  host appears under `website/`, which would have caught the undisclosed hCaptcha script. Also add
  a website section to the README, which never mentions the site at all.

  **Done: four new steps, taking the build job from three checks to seven.** `404.html` and
  `sitemap.xml` added to the required-files loop; each page's canonical must match its own path
  (these `<head>` blocks were written by copying the previous page's, which is precisely how two
  pages end up claiming one canonical); the sitemap must list exactly the set of canonicals;
  every in-page anchor must resolve, including cross-page ones like `/whaaack/#adfree` from the
  privacy page, which fail silently today because the link still loads and simply scrolls
  nowhere; and no cleartext `http://` URL or undeclared third-party host may appear.

  **Every guard was verified by breaking the thing it guards.** All seven pass on a clean tree,
  and ten deliberate breakages are caught: a deleted page, a redirected contact form, a
  duplicated canonical, a sitemap entry dropped and a phantom one added, a renamed anchor id, a
  broken cross-page anchor, an undeclared host, a cleartext URL, and a stray CNAME.

  Getting there turned up two flaws worth recording. The host check first used `comm`, which
  needs both inputs sorted in the same collation and *silently misreports* when they are not — it
  warned and still exited 0, a guard passing for the wrong reason; it uses `grep -vxF` now. And
  the first harness ran the steps through Python's `subprocess`, which on this machine resolves
  to WSL bash and pre-expanded `$f` to empty, making two healthy guards look broken — these
  checks are only meaningful run under the same shell Actions uses. Re-running the fixed host
  check immediately caught a genuinely missing entry, `uokik.gov.pl`, linked from the terms.

  One thing to know about that allow-list: only **three** of its hosts actually receive data —
  `idct.tech`, `js.hcaptcha.com` (a script tag) and `api.web3forms.com` (on submit). Those are
  the three that must appear in the privacy policy's recipients list. The rest are plain outbound
  links, allow-listed anyway because a new host deserves one deliberate look, and because a link
  and a resource load are one attribute apart. `play.google.com` is pre-approved so the store
  badge landing at launch does not red-light CI for no reason.

  The README now has a website section covering the URL, the project-page-under-apex constraint
  (no CNAME, and why `app-ads.txt` cannot live here) and what CI enforces.

---

## 7. Testing and QA

`GameEngineTest` is the only test file in the repo, against 6,700 lines of main source.

- [ ] **Define and run a manual QA matrix** 🟡
  The README says the app is "working and verified on device" — on one device, informally. Write
  the matrix into `docs/` and run it per release candidate: **API 26** (the minSdk floor and where
  `lockHardwareCanvas` arrived — the software-canvas fallback has almost certainly never executed);
  **API 36 phone** (edge-to-edge is enforced at targetSdk 36 — confirm nothing sits under the
  status or gesture bars on any screen); **tablet and unfolded foldable on API 36**, including
  folding mid-run, which `configChanges` swallows without recreating the activity so only
  `surfaceChanged` reacts; **split-screen and free-form** (see §3); **a notch/hole-punch device**,
  since the game reads only `systemBars()` insets, not `displayCutout()`; **a low-end device**
  (2 GB RAM, entry-tier GPU) — the sprite set is ~4.6 MB of ARGB heap plus three full-screen
  backdrops, and there is no `onTrimMemory` handler anywhere, so under pressure the process is
  simply killed; **gesture navigation**, confirming the board edges and End-run pill do not fight
  the back and home swipe regions; and **200% font scale**, where the Compose menus use fixed `sp`
  throughout.

- [ ] **Add one instrumentation smoke test** 🟡
  There is no `androidTest` source set at all. The highest-value single test is launch-to-run,
  because the render-thread lifecycle is what a unit test structurally cannot reach: launch, tap
  "Play for fun", let the countdown run, background and foreground (exercising `surfaceDestroyed` →
  `stopRendering` → the unbounded join → the buffer drain, then `surfaceCreated` → resume
  countdown), tap "End run", assert game-over appears. Run it on a physical device and on an API 26
  emulator.

- [ ] **Cover the four untested modules that decide money, identity and rank** 🟡
  `GameEngineTest` is genuinely good work — 15 tests covering the pause/resume clock, taps,
  quit/loss reporting, splat expiry, the target ladder and the curve. It is also the only test file
  against 6,700 lines of main source. In order of blast radius: **BillingManager.refresh()** (see
  §5); **deep-link fragment parsing**, currently inline in the ViewModel and therefore untestable —
  extract `parseAuthFragment(String?)` and test percent-decoding, `error_description`, blank tokens,
  a missing `expires_in`, an `=` inside a value, and recovery vs confirmation; **leaderboard
  parsing**, which uses `mapNotNull` keyed on `rank` so an RPC shape change silently yields an empty
  board rather than an error; and **the 401-refresh-retry in `SupabaseClient`**, with mockwebserver,
  proving the single retry, that a 400–403 on refresh clears the session, and that a 5xx does not.

- [ ] **Make the Compose menus usable with TalkBack and meet the 48dp floor** 🟡
  There are exactly two `contentDescription` usages in the whole app, no `semantics` block, no
  `Role`, no `toggleable`/`selectable`, and no `minimumInteractiveComponentSize()`. Four concrete
  gaps: **icon-only controls announce their glyph** — `CircleIconButton` renders a bare `Text`
  symbol (`⚙`, `‹`), so TalkBack reads the character name or nothing; **toggles do not announce
  state** — `ToggleRow` is a clickable Row with a hand-drawn switch, so TalkBack says the label with
  no on/off; **touch targets below 48dp** — `CircleIconButton` at 40dp, the Home "Log out" chip at
  ~34dp, segmented tabs at ~39dp, sheet text buttons at ~37dp, and "Forgot your password?" at
  ~22dp; and **contrast** — secondary copy runs at 35–50% alpha over translucent panels over busy
  artwork. Note `./gradlew lint` finds none of this: Compose is not covered by the XML detectors,
  which is why lint is green today. Sweep with Accessibility Scanner and then navigate the whole app
  with TalkBack and no eyes on the screen.

- [ ] **State English-only as a deliberate v1 decision** 🟡
  `strings.xml` contains exactly one entry (`app_name`); every other player-facing string is a
  Kotlin literal, including the eight `AuthError` title/body pairs and the HUD text drawn on the
  render thread. `formatScore` pins `Locale.US`, so grouping is always `128,940`. English-only is a
  perfectly reasonable v1 call, but it has two consequences now: set English as the only listing
  language (a Polish listing on an English-only app is a reliable one-star complaint), and
  understand the migration cost before promising a translation — there is no `stringResource` call
  anywhere, so localising means moving ~150 literals and threading a `Context` into the `AuthError`
  sealed class and the renderer, both currently Android-free by design. Also add
  `androidResources { localeFilters += "en" }` to stop shipping every AndroidX/GMS translation.

- [ ] **Add ktlint or detekt** ⚪
  `gradle.properties` declares `kotlin.code.style=official` and the code genuinely follows it — but
  that setting only configures the IDE; nothing checks it at build time. Detekt is the better value
  here since it catches unused private members and swallowed exceptions (it would have surfaced the
  dead surface area in §3 without anyone grepping). Generate a baseline so existing code passes
  immediately, wire it into CI, and keep the threshold low enough not to become noise.

---

## 8. The game itself

> **Onboarding: deliberately out of scope.** An earlier draft proposed a first-run
> tutorial card. Rejected — whack-a-mole is self-evident, and a modal between the
> launcher icon and the first tap is a cost, not a feature. Recorded so it does not
> get re-proposed at the next review.

- [x] **Put a confirmation on "End run"** — **done: two presses, and whacking anything disarms it**
  The touch path is otherwise carefully built — `ACTION_POINTER_DOWN` is handled so a second thumb
  is not dropped, and `tileAt` deliberately treats the gutters as live so a near-miss is never
  stolen. But the End-run pill is 104×38dp, centred horizontally, and the board ends exactly
  **14dp** above it. So a 14dp line is all that separates a board the code goes out of its way to
  make forgiving from a control that ends the run instantly, with no confirmation, on the first
  `ACTION_DOWN` inside it — while at top speed the player is tapping several times a second across
  four slots including the bottom row. Make it two-step (first tap arms it for ~1.5s), or require a
  long-press, or move it away from the board's vertical axis. Either costs nothing and removes the
  worst possible way to lose a record run.

  **Done, as a two-press arm rather than a long-press.** The first press arms the control for
  `QUIT_ARM_MS` (1.6s) and does nothing else; a second press inside that window ends the run. A
  long-press was the alternative and was rejected — holding still is the one thing a player
  cannot do mid-run, and it would have made a deliberate quit harder than an accidental one.

  **The part that actually makes it strong: whacking any fruit disarms it.** An accidental press
  is cleared by the very next tap the player makes, and at the late levels that is milliseconds
  away — so ending a run by accident now needs two stray presses on a 104x38dp target *and* no
  fruit hit in between. It costs the deliberate path nothing, since someone quitting on purpose
  is not whacking fruit between the two presses. Being interrupted (backgrounded, or the window
  shrinking below a playable size) disarms it too, so nobody returns to a half-armed quit button.

  Armed state is loud rather than silent, because the failure mode is a player who did not mean
  to press it: the pill fills with a warning tint, the label changes to "End run?", the remaining
  window drains left to right so it reads as having a deadline rather than being stuck, and there
  is a blip plus a short haptic. Silence there would read as an unresponsive button, which is its
  own bug report. (The label is "End run?" and not "Tap again to end" for a dull reason — sixteen
  characters at 12dp overruns a 104dp pill.)

  The state lives in `GameEngine` rather than the view: it is run control, it sits next to
  `requestQuit`, the renderer already reads engine state, and putting it there makes it testable.
  `requestQuit(nowNs)` now returns `ARMED` / `CONFIRMED` / `IGNORED`. Six new tests cover it —
  one press does not end a run, a second inside the window does, the arm lapses, a fruit tap
  disarms, an interruption disarms, and a press outside a run is ignored. The draining bar reuses
  a preallocated `Path`, so the render loop's no-allocation-per-frame rule still holds. 35 unit
  tests pass, debug and R8 release build, `lintVitalRelease` clean.

- [x] **Decide what happens past 40 seconds** — **done: the curve no longer flattens**
  The curve is well built and documented, and its shape has a consequence nobody drew out:
  `spawnIntervalMs` bottoms out at level 10 and `fruitLifeMs` at level 9, `LEVEL_STEP_MS` is 4,000,
  so **level 10 arrives at 40 seconds and after that nothing changes** — same four slots, same
  200ms interval, same 430ms life, forever. Past 40 seconds the score stops measuring skill and
  starts measuring stamina, battery and how long someone will hold a phone. The all-time #1 will be
  set by whoever is most stubborn, will be minutes long, and will be effectively unbeatable
  permanently. Decide: a gentle ramp past level 10 so runs terminate, a fixed-length run scored on
  hits-per-second, or accept it and say so. Two attached details either way — `scores.millis`
  carries `check (millis <= 86_400_000)`, so a 24-hour run is silently rejected, and *every*
  constraint failure surfaces to the player as "Couldn't post that score — check your connection",
  which is the wrong explanation for all of them.

  **Decided: no hard cap. The ladder keeps climbing to one fruit per tile.** The 4x4 board is a
  real physical ceiling — a seventeenth concurrent fruit has nowhere to stand — so `MAX_TARGETS`
  is now `TILE_COUNT`, and a slot opens every three levels once the tuned opening is past. The
  pace tracks no longer flatten either: they ramp linearly exactly as tuned down to a knee (the
  old flat floors), then keep tightening geometrically toward a hard minimum, so the pace always
  increases but a fruit life can never decay to zero, which would be broken rather than hard.

  | Level | Time | Fruit | Life | Interval | Arrivals/s |
  | --- | --- | --- | --- | --- | --- |
  | 0 | 0s | 2 | 1550ms | 900ms | 1.1 |
  | 8 | 32s | 4 | 470ms | 324ms | 6.9 |
  | 9 | 36s | 5 | 430ms | 252ms | 9.6 |
  | 12 | 48s | 6 | 418ms | 195ms | 12.3 |
  | 18 | 72s | 8 | 398ms | 182ms | 17.3 |
  | 42 | 168s | 16 | 347ms | 150ms | 40.1 |

  Levels 0–8 are **byte-identical** to the tuned curve, guarded by a regression test — the
  change is strictly additive. `TOP_SPEED_LEVEL` was redefined from "both pace tracks bottomed
  out" (an idea that stopped existing) to "the last slot opened", which is level 42; the HUD's
  number, bar and parallax still clamp there, and `displaySpeed` now tops out at 43, safely
  under the `scores.top_speed <= 64` constraint, so no migration was needed.

  **Expect top runs of roughly one to two minutes rather than unbounded.** The old plateau sat
  at about 8 arrivals/second, which is right at the edge of sustained two-thumb tapping — so
  whoever could just barely hold that pace ran for ever, which was the whole complaint. The
  ladder now walks past that limit and keeps going. Nobody will actually reach level 42; that
  is the safety margin, not a target. `TARGET_STEP_LEVELS` is a single named constant if three
  levels turns out to be too brisk.

  **A latent bug had to be fixed first, and it was the real risk in this change.** `spawn` fell
  back to placing a fruit on an already-occupied tile when its search for a free one ran dry.
  The code's own comment explains why that is bad — one tap clears one slot, so the other fruit
  expires into a strike nothing could have prevented. That was a rare edge case at four fruit
  and would have been *guaranteed* at sixteen. `spawn` now returns false and the slot simply
  waits for a later frame, which also means the board self-limits regardless of what the ladder
  asks for. Covered by a test that drives a perfect player to a full board and asserts no tile
  ever holds two fruit.

  **Both attached details are closed too.** The `millis <= 86_400_000` check is now unreachable
  — runs terminate in minutes, not days. And the submit path no longer blames the network for
  everything: `LeaderboardRepository.submit` stops swallowing failures into a boolean, and the
  ViewModel distinguishes a `SupabaseException` (the server looked at the score and refused it —
  a plausibility constraint, the rate-limit trigger, a stale session) from an `IOException` (the
  request never arrived). Telling someone to check a connection that is fine, about a retry that
  will not help, is worse than saying nothing.

  Verified: 25 unit tests pass (5 new for the curve and saturation), debug and R8 release build,
  `lintVitalRelease` clean.

- [x] **Give the weekly leaderboard a visible reset** — **done; the "payoff" half declined**
  `current_week_start()` is Monday 00:00 UTC and the UI exposes Weekly as a tab with an honest
  empty state. But nothing captures a weekly result and nothing tells the player the week is a
  cycle: at 00:00 UTC on Monday every weekly rank silently becomes nothing — no winner recorded
  anywhere in the schema, no notification, no hall of fame, no "resets in 2d 4h" label, and no
  explanation of a boundary that falls at 02:00 local for a Polish player in summer. A weekly board
  that never resolves is a mechanic with no reward loop — and on day one it is the *only* board
  with a reachable #1, so it is the one worth making work. Minimum: a "Resets Monday 00:00 UTC"
  line under the tab. Better: a `weekly_winners` table written at rollover with last week's top
  three surfaced above the live board. Decide before launch — retro-fitting a winners table cannot
  recover weeks that have already rolled over.

  **Decided: keep the same data subset, just say what it is.** No `weekly_winners` table, no
  scheduled rollover job, no hall of fame, no notifications — those were the audit's suggestion,
  not a requirement, and they add a moving part that has to be right for ever in exchange for a
  reward loop this game does not otherwise have. The weekly board stays exactly what it is: the
  same rows as all-time, cut to one week.

  **The window was already correct — it was only ever undocumented.** `current_week_start()` is
  `date_trunc('week', now() at time zone 'utc') at time zone 'utc'`, and Postgres truncates to
  the *ISO* week, so that is Monday 00:00 UTC; the filter runs to the next Monday 00:00 UTC
  exclusive, i.e. Monday through Sunday inclusive. Verified against the live database rather than
  reasoned about, including by recomputing it under deliberately hostile session timezones
  (`Pacific/Kiritimati` at +14 and `Pacific/Niue` at −11): the returned instant is identical in
  every case and only its rendering differs, so the boundary is genuinely timezone-independent.
  **No SQL change was needed.**

  **What was missing was telling the player.** The Weekly tab now carries a caption naming the
  exact window — *"Mon 10 - Sun 16 Aug, UTC"* — computed in UTC, not the device zone, because
  using the device zone would name a different week for anyone east of London on a Sunday night.
  That matters more than it sounds: the cut falls at 02:00 Monday for a Polish player in summer
  and on *Sunday evening* across most of the Americas, so a board that silently emptied overnight
  read as a bug rather than a reset.

  The label logic is split from the clock so it is testable, and four tests cover the dates that
  break this kind of format: every day of one week naming the same window and the next Monday
  rolling over; the month printed once when the week does not straddle one (`10 - 16 Aug`) and
  twice when it does (`27 Jul - 2 Aug`); a week spanning new year; and a leap day. 29 unit tests
  pass, debug and R8 release build, `lintVitalRelease` clean.

  Not done, and deliberately: a live "resets in 2d 4h" countdown. It needs a ticking clock in
  composition for a number nobody acts on, and the date range already answers the question.

---

## 9. Repo hygiene

Small, and one of them has a deadline attached: the audio track is being offered for public
redistribution under the repo's licence right now.

- [x] **Remove the uncredited 4.2 MB audio track from the repo** — **done; history purge declined**
  `assets/audio/05 - Battle 1.ogg` (4,245,227 bytes, dated Jan 2025) was added in `80950ad` and is
  still tracked. It is **not shipped** — `app/src/main/assets/audio/` does not contain it — and it
  is **not credited**: the credits name DavidKBD's "Calypso and Surf Rock" from Tropical Dreams,
  and nothing accounts for a track called "Battle 1". A numbered-track filename is the shape of an
  extracted OST. Establish provenance; if it cannot be established, treat it as unlicensed —
  it is currently being offered for public redistribution under the repo's BSD licence, which is
  exactly the scenario that draws a DMCA notice against a live listing. `git rm` it, purge it with
  `git filter-repo`, force-push, and ask GitHub Support to garbage-collect so the blob is
  unreachable. Do this before the app attracts attention.

  **Provenance established, and it changes the urgency.** The Ogg Vorbis comment block names it
  outright: `ARTIST=xDeviruchi`, `ALBUM=16-bit Fantasy & Adventure`, `TITLE=Battle 1`,
  `TRACKNUMBER=5`, `Composer=Marllon Silva (xDeviruchi)`, `DATE=2025`. So it is not an
  unattributable orphan — it is an identifiable track from a known chiptune pack, by an artist
  none of the four credited vendors covers. That downgrades this from "possible DMCA exposure"
  to "someone else's music sitting in a public repo under our BSD licence", which is still
  wrong but is not a fire. **Check xDeviruchi's pack terms** before concluding anything further;
  they are commonly free-for-commercial-use-with-credit, in which case the only real fault was
  the missing credit — and since the file is not shipped, deleting it settles that too.

  **Done:** `git rm`'d. It was referenced by nothing — no Kotlin, Gradle, Python, workflow or
  markdown mentions it.

  **Also removed, same class of problem:** the whole of `assets/audio/` — 4.26 MB across 14
  files that are **byte-identical** to `app/src/main/assets/audio/` and read by nothing.
  `tools/generate_screenshots.py` sources from `app/src/main/assets`, and only `assets/icon/`
  is live (the launcher-icon generator). So this was pure duplication, not a set of masters —
  identical hashes mean no quality or information was lost. `assets/` now holds only the four
  icon files. Build, unit tests and both tools' input paths re-verified afterwards.

  **History purge: declined, deliberately — do not re-propose.** Rewriting all five commits and
  force-pushing a public repo would have cost every SHA, every clone and every fork, plus a
  GitHub Support ticket to make the detached blobs actually unreachable, in exchange for ~8 MB
  of `.git` and the removal of a blob whose artist is now identified and whose pack terms are
  very likely permissive. Not worth it. The blobs stay reachable in history at
  `80950ad` and are no longer present at `HEAD`.

  What that leaves is a licensing question rather than a storage one, and it is already tracked
  elsewhere: the root `LICENSE` is BSD-3-Clause over the whole tree, so history still nominally
  offers third-party art and audio for redistribution. **Scoping the licence to source code and
  naming the asset directories as excluded is what actually closes this** — see *Fix the
  LICENSE / third-party-asset contradiction* in the
  [non-technical plan](GO-TO-PRODUCTION-NON-TECHNICAL.md) §3, which is now the mitigating action
  for the residual exposure rather than merely a filing tidy-up. Credit xDeviruchi there too if
  the pack terms ask for it, even though the track no longer ships.

- [x] **Enable GitHub secret scanning and push protection** — **done**
  The history is **verifiably clean** — `git log --all --diff-filter=A` over `secrets/*`,
  `local.properties`, `*.keystore` and `*.jks` returns empty, neither `secrets/` nor
  `local.properties` was ever tracked, and pickaxes for `sb_secret`, `service_role`,
  `BEGIN PRIVATE` and `BEGIN RSA` return zero commits each (the one `sb_publishable` hit is the
  literal placeholder in SETUP.md, and the one `GOCSPX-` hit is a shell glob validating a secret's
  *shape*). No history rewrite is required. Keep it that way, because the release adds a keystore
  and its passwords to the local environment — a materially higher-value secret than anything this
  repo has handled. Turn on secret scanning and push protection (free on public repos), and
  consider gitleaks in CI.

  **Done** on `ideaconnect/whaaack` via the API — `secret_scanning: enabled` and
  `secret_scanning_push_protection: enabled`, confirmed by reading the settings back. Push
  protection is the one that matters here: it rejects a matching secret at `git push` time
  rather than reporting it after it is already public, which is the difference between a
  near-miss and a rotation.

  Two adjacent toggles would **not** enable — `secret_scanning_non_provider_patterns` and
  `secret_scanning_validity_checks`. The API accepts the PATCH and silently leaves them
  disabled; they are Advanced Security features that a free public repo does not get. Nothing
  to fix, but do not read a green API response as proof they are on. Gitleaks in CI remains
  the way to cover custom patterns, and it pairs with the Android CI workflow in §2.

- [x] **Fold `OAUTH.md` into `docs/SETUP.md`** — **done**
  `OAUTH.md` sits at the repo root as an unformatted plain-text dump duplicating SETUP.md §3, and
  it has already diverged — it cites line numbers into files that have since changed and restates
  the same findings as a "Gap 1 / Gap 2" narrative. Merge the one genuinely useful thing it has
  that SETUP.md only partly covers (why the CLI's anchored matcher forces the comma-joined
  variable), then `git rm` it. Adopt the rule that `docs/` holds documentation and the root holds
  README, LICENSE and CHANGELOG — so a reader landing on a public repo is not choosing between two
  overlapping OAuth guides.

  **Done.** Three things OAuth.md had that SETUP.md did not are now in §3 of SETUP.md: the
  anchored matcher stated as the actual pattern (`^env\((.*)\)$`, which is *why* the two client
  ids have to be comma-joined into one variable); the warning that putting the **Android** id in
  `GOOGLE_WEB_CLIENT_ID` fails as an opaque `aud` mismatch rather than a legible error; and the
  dashboard route (Authentication → Sign In / Providers → Google) as an alternative to
  `config push` — which is now the *safer* route, because a push rewrites the whole auth config
  from `config.toml`, SMTP password included. Everything else in the file duplicated SETUP.md or
  cited line numbers that had already drifted. `git rm`'d; the repo root is down to `README.md`
  and `LICENSE`, and the README's layout block already pointed at `docs/SETUP.md`.

---

## Already done — do not re-litigate

Verified during the audit, recorded so nobody spends time on them twice.

- **16 KB page-size compliance is met.** All eight shipped `.so` files have `p_align = 0x4000` on
  every PT_LOAD segment, every entry is 16384-aligned and stored uncompressed, and the AAB declares
  `PAGE_ALIGNMENT_16K`.
- **The R8 mapping rides along in the AAB**, so Play deobfuscates vitals crashes with no upload
  step, and `mapping.txt` carries PC-based line numbers.
- **Purchase acknowledgement is implemented correctly**, from both paths that can see a purchase,
  with retries — and there is deliberately no `consumeAsync`.
- **The entitlement is correctly excluded from cloud backup and device transfer**, in its own
  DataStore file, so a purchase cannot be cloned.
- **Secret history is clean** — nothing sensitive was ever committed, on any ref.
- **`targetSdk = 36` already satisfies** the 31 August 2026 requirement.
- **Billing Library 9.1.0** is comfortably ahead of Play's deprecation deadlines and uses the
  modern surface throughout.
- **Every `SECURITY DEFINER` function pins `search_path`**, grants are tight, and the hardening
  migration closed the profiles enumeration hole (verified by probe: anon `GET /profiles` went from
  leaking names to `[]` while `rpc/leaderboard` still returns rows).
- **The ad placement is policy-clean** in the ways that matter: no ads during a run, no app-open
  format, no ad on a back press, music pauses, a 120-second cap, and an honest warning on the
  game-over screen.
- **`PredictiveBackHandler` is available on the pinned activity-compose 1.9.3** — the predictive-back
  work does not depend on the androidx bump.
- **The Pages workflow already asserts** that the required legal pages exist, that the contact
  redirect matches the thanks page, and that no project CNAME can claim the apex.
