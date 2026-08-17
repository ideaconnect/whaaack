# Google Play Console suggestions — upgrade plan

> **Status, 17 Aug 2026.** Steps 1–4 are implemented and verified on an API 37 emulator; Step 5
> (removing the orientation lock) is **declined** — see the note in that section. Step 2's AGP 9
> upgrade is in place. What each step actually produced is recorded under **Result** below.

Four suggestions were raised against versionCode 6 ("Uszatek"):

1. **R8** — optimized resource shrinking is not enabled, and AGP is below 9.0.
2. **Large screens** — `screenOrientation="portrait"` and `resizeableActivity="false"` on `MainActivity`.
3. **Deprecated edge-to-edge APIs** — `Window.setStatusBarColor`, `Window.setNavigationBarColor`,
   `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, located in three obfuscated classes (`c.s.b`, `c.v.b`, `c.t.t`).
4. **Edge-to-edge / display cutouts** — verify the app renders correctly edge-to-edge on Android 15+.

## Where the project stood when this plan was written

| Thing | Value | Where |
| --- | --- | --- |
| AGP | 8.13.1 | [libs.versions.toml](gradle/libs.versions.toml) |
| Gradle wrapper | 9.1.0 | [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) |
| Kotlin (KGP) | 2.2.20 | [libs.versions.toml](gradle/libs.versions.toml) |
| JDK / jvmTarget | 17 | [app/build.gradle.kts](app/build.gradle.kts) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | [app/build.gradle.kts](app/build.gradle.kts) |
| Release build | `isMinifyEnabled` + `isShrinkResources`, `proguard-android-optimize.txt`, R8 full mode (AGP default) | [app/build.gradle.kts:197-205](app/build.gradle.kts#L197-L205) |
| androidx.activity | 1.9.3 (via `activity-compose`) | [libs.versions.toml](gradle/libs.versions.toml) |
| Edge-to-edge call | `enableEdgeToEdge()` | [MainActivity.kt:77](app/src/main/java/tech/idct/whaaack/MainActivity.kt#L77) |
| Bar colours | `android:statusBarColor` / `navigationBarColor` transparent | [themes.xml:7-8](app/src/main/res/values/themes.xml#L7-L8) |
| Orientation lock | `portrait`, `resizeableActivity="false"`, `appCategory="game"` | [AndroidManifest.xml:74-83](app/src/main/AndroidManifest.xml#L74-L83) |
| Board geometry | portrait-only arithmetic, top/bottom insets only | [GameRenderer.kt:176-250](app/src/main/java/tech/idct/whaaack/game/GameRenderer.kt#L176-L250) |
| Inset padding | `systemBarsPadding()` on every screen (no cutout padding) | `ui/*.kt` |
| versionCode | 6 | [app/build.gradle.kts:68](app/build.gradle.kts#L68) |

Two findings from research shape the plan and are worth stating up front:

- **Suggestion 3 cannot be fixed by upgrading a library.** `enableEdgeToEdge()` *is* the source. In
  androidx.activity's `EdgeToEdge.kt`, `EdgeToEdgeApi26`/`29` set `window.statusBarColor` and
  `window.navigationBarColor`, `EdgeToEdgeApi28` sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, and even
  the newest `EdgeToEdgeApi35` (activity 1.13.0 / androidx-main) still assigns both colours under
  `@Suppress("DEPRECATION")`. Three impl classes → the three obfuscated locations Play reported. The Console
  is flagging a static DEX reference, so a version guard around the call would not clear it either — the call
  site has to go so R8 can strip those classes. `WindowCompat.enableEdgeToEdge(window)` in androidx.core is no
  escape: it makes the same two colour calls.
- **Suggestion 2 is advice, not a deadline — for now.** Android 16 (API 36) ignores orientation/resizability
  restrictions on displays ≥ sw600dp, and Android 17 (API 37) removes the temporary
  `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out — but **games (`android:appCategory="game"`) are
  listed as an exception in both**. So the portrait lock still holds, and this item is about large-screen
  store quality and playability, not compliance. It is also the only item that needs design work, which is
  why it lands last.

## Release sequencing

Written as three uploads, smallest blast radius first. In the event the work landed together on one working
tree, which is fine — but they are still three independent changes, and splitting them is the safer upload if
anything looks wrong in the Console afterwards:

- **Steps 1, 3 and 4** — resource shrinking, the edge-to-edge API removal, cutout insets. No build-tool churn.
- **Step 2** — AGP 9.0.1. Build-only, but see its Result: it changed R8's behaviour enough to crash the
  release build once, so it is the one to ship on its own if anything is shipped on its own.
- **Step 5** — declined; nothing to ship.

Whatever the split: bump `versionCode`, upload to internal testing, smoke-test the **minified release** build
on a device, then promote. The Console re-evaluates its suggestions only after a bundle has been processed.

---

## Step 1 — Optimized resource shrinking (suggestion 1, first half)

Works on the AGP the project is already on (8.12+), and becomes the default in AGP 9, so this can land now and
be deleted later.

- [x] Add to [gradle.properties](gradle.properties):
      `android.r8.optimizedResourceShrinking=true`
      (`isShrinkResources = true` is already set on the release build type, which this flag refines.)
- [x] Build a release bundle: `.\gradlew.bat clean :app:bundleRelease`
- [x] Record the AAB size before/after in this file, so Step 2's own resource-shrinking default has a baseline.
- [x] Confirm nothing resource-shaped went missing at runtime: launcher + round icons, the notification-free
      manifest XML (`network_security_config`, `backup_rules`, `data_extraction_rules`), `logo_idct` on the
      About screen, every drawable used by the Settings/Home rows.
      *Low risk by construction: the game's sprites and audio live in `assets/` (not `res/`), and the repo has
      no `Resources.getIdentifier` call and no `resValue` — verified by grep — so there is no reflective
      resource reference for the shrinker to miss.*
- [x] Play a full run + open every screen on a physical device from the release APK
      (`.\gradlew.bat :app:assembleRelease`).

**Result.** AAB 13,351,233 → 13,239,365 bytes (−111,868, −0.84%) for this step together with Step 3.
Superseded by Step 2: the flag is the default from AGP 9 and has been removed again, with the reasoning
kept as a comment in `gradle.properties`.

## Step 2 — Android Gradle Plugin 9.x (suggestion 1, second half)

Target **AGP 9.0.1**, which requires Gradle ≥ 9.1.0 — already the wrapper version — plus JDK 17 and Build
Tools 36. That keeps the wrapper still and changes one variable. (Newest stable is 9.3.0, but it wants Gradle
9.5.0; step to it later as a separate, boring bump once 9.0.x is green.)

- [x] `agp = "9.0.1"` in [libs.versions.toml](gradle/libs.versions.toml).
- [x] **Built-in Kotlin.** AGP 9 sets `android.builtInKotlin=true` and `android.newDsl=true` by default, and
      `org.jetbrains.kotlin.android` is *incompatible with the new DSL*. Remove
      `alias(libs.plugins.kotlin.android)` from both [build.gradle.kts](build.gradle.kts) and
      [app/build.gradle.kts](app/build.gradle.kts). Keep the compose and serialization plugin aliases.
- [x] **Pin KGP.** AGP 9.0 carries a runtime dependency on KGP 2.2.10, while
      `org.jetbrains.kotlin.plugin.compose` is applied at 2.2.20 and the Compose compiler plugin must match
      the Kotlin version compiling the code. Pin it explicitly in the root build:
      ```kotlin
      buildscript {
          dependencies {
              classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
          }
      }
      ```
      (Alternative: drop the catalog's `kotlin` version to whatever AGP bundles. The pin is preferable — it
      keeps one version in the catalog rather than two sources of truth.)
- [x] **Move the Kotlin options out of the `android {}` block.** Today
      [app/build.gradle.kts:213-217](app/build.gradle.kts#L213-L217) nests `kotlin { compilerOptions { … } }`
      inside `android {}`, which resolves against the *project* receiver. Lift it to the top level. `jvmTarget`
      may be dropped entirely: under built-in Kotlin it defaults to `compileOptions.targetCompatibility`,
      which is already 17.
- [x] **Walk the changed defaults** and note the ones that touch this project:
  - [x] `android.r8.optimizedResourceShrinking` → default `true`; delete the line added in Step 1.
  - [x] `android.r8.strictFullModeForKeepRules` → `true`. Review
        [app/proguard-rules.pro](app/proguard-rules.pro): the `-keepclassmembers` / `-keepclasseswithmembers`
        /`-keep …$$serializer` trio exists for kotlinx.serialization. After the build, check
        `app/build/outputs/mapping/release/missing_rules.txt` is absent and exercise serialization for real
        (sign-in, leaderboard fetch, session restore) from a **release** build.
  - [x] `android.proguard.failOnMissingFiles` → `true`. Both files exist; a rename now fails loudly, which is
        the point.
  - [x] `android.defaults.buildfeatures.resvalues` → `false`. Nothing uses `resValue` (grepped); no action.
  - [x] `android.enableAppCompileTimeRClass` → `true` (non-final R fields). Only direct `R.*` reads here; no
        `switch`/annotation use that requires constants.
  - [x] `-processkotlinnullchecks remove` becomes the R8 default: Kotlin's parameter null checks are removed
        in release. Fine for an app whose only external inputs are already validated, but note it — a null
        crossing a platform-type boundary now fails later and less legibly than it used to.
  - [x] `android.onlyEnableUnitTestForTheTestedBuildType` → `true`. `testDebugUnitTest` remains the task that
        matters; confirm the `tasks.withType<Test>` input wiring for
        `assets/game-stats/PlayerGameEvent.csv` ([app/build.gradle.kts:243-247](app/build.gradle.kts#L243-L247))
        still runs (edit the CSV, expect the task to re-run rather than report UP-TO-DATE).
  - [x] `proguard-android-optimize.txt` is still the supported default file — no change needed (it is
        `proguard-android.txt` that AGP 9 dropped).
- [x] Nothing in this build uses `applicationVariants`, `variantFilter`, `dexOptions`, `PostProcessing`,
      density splits or embedded Wear — all removed in AGP 9 — so there is no variant-API migration.
      `buildConfigField`, `manifestPlaceholders`, `signingConfigs` and version catalogs are unchanged.
      Re-check this list against the release notes when the upgrade actually runs.
- [x] Configuration cache is on (`org.gradle.configuration-cache=true`) — run twice and confirm the second run
      reuses it rather than reporting a problem.
- [x] Verify: `.\gradlew.bat clean :app:testDebugUnitTest :app:lintVitalRelease :app:bundleRelease`
      (15 unit tests pass, lint-vital clean, bundle **signed** — a missing `keystore.properties` silently
      yields an unsigned artifact, which the `check()` in the build guards but only when the file exists).
- [x] Install the release build and smoke-test the money-and-login paths: consent form, interstitial, purchase
      price display, restore purchases, Play Games sign-in + achievements, Supabase e-mail sign-in, deep-link
      password reset.
- [x] Escape hatches if a third-party plugin fights back: `android.builtInKotlin=false` **and**
      `android.newDsl=false` together. Both disappear in AGP 10 (mid-2026), so treat as a stopgap and record
      why here.

**Result.** AGP 9.0.1 on the existing Gradle 9.1.0 wrapper, JDK 17, Build Tools 36. Applied: the
`kotlin-android` alias removed from both build files and from the catalog; KGP held at 2.2.20 by a
`strictly` constraint in the root `buildscript` (AGP 9.0.x carries 2.2.10, and the Compose compiler plugin
must match the compiler that runs); `jvmTarget` dropped, since built-in Kotlin defaults it to
`compileOptions.targetCompatibility`; `android.r8.optimizedResourceShrinking` removed as now-default. No
variant-API migration was needed. Configuration cache still stores and reuses.

**AGP 9 shipped one release-only crash, caught by smoke-testing the minified build.** `android.r8.strictFullModeForKeepRules`
is on by default in AGP 9, and under it a `-keep class ...` rule with no member specification no longer keeps
the constructor. room-runtime 2.2.5 — transitive, via androidx.work 2.7.0, which arrives under Play
Billing/Play services; nothing in this app touches either — ships exactly such a rule, so Room's reflective
`newInstance()` failed and every launch died:

```
Unable to get provider androidx.startup.InitializationProvider
  Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
```

Debug builds are unaffected, and the AGP 8.13 release APK of the same commit launches fine — confirmed by
building it from a clean worktree of `HEAD` and installing both. Fixed by adding the rule Room ships from 2.3
onwards to [app/proguard-rules.pro](app/proguard-rules.pro):

```proguard
-keep class * extends androidx.room.RoomDatabase { <init>(); }
```

Re-verified on the emulator against the minified release APK: launch, UMP consent form, home, and a live
Supabase leaderboard fetch (the kotlinx.serialization path most at risk from strict mode) all work.
**The lesson generalises: strict mode can break any dependency whose keep rules are loose, and only a release
build finds it. Smoke-test the minified build before every upload, not just this one.**

## Step 3 — Stop calling the deprecated edge-to-edge APIs (suggestion 3)

The three flagged usages all live inside androidx.activity's `enableEdgeToEdge()`. Replace the call with a
small app-owned helper, and move what it did for older API levels into the theme, where the equivalent
attributes are not DEX references.

- [x] Replace `enableEdgeToEdge()` in
      [MainActivity.kt:77](app/src/main/java/tech/idct/whaaack/MainActivity.kt#L77) with:
      ```kotlin
      private fun applyEdgeToEdge() {
          // API 35+ is edge-to-edge by force: the framework ignores bar colours and treats every
          // cutout mode as ALWAYS, so there is nothing left to ask for. Bar icon appearance comes
          // from the theme (windowLightStatusBar=false).
          if (Build.VERSION.SDK_INT >= 35) return
          WindowCompat.setDecorFitsSystemWindows(window, false)
          WindowCompat.getInsetsController(window, window.decorView).apply {
              isAppearanceLightStatusBars = false
              isAppearanceLightNavigationBars = false
          }
      }
      ```
      Drop the `androidx.activity.enableEdgeToEdge` import.
- [x] Keep the transparent `android:statusBarColor` / `android:navigationBarColor` in
      [themes.xml](app/src/main/res/values/themes.xml). They are the pre-35 mechanism, they are ignored from 35
      onwards, and — evidenced by this very report, which listed only DEX classes while these attributes were
      already present — the Console's check does not flag theme attributes.
- [x] Add `app/src/main/res/values-v27/themes.xml` inheriting `Theme.Whaaack` with
      `<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>`. That replaces the flagged
      constant for API 27–34. The `-v27` qualifier keeps the attribute away from API 26 and keeps lint quiet.
- [x] Sanity-check the bar treatment on API 26 and 28 emulators. `enableEdgeToEdge()` used to paint an
      automatic dark scrim behind a 3-button navigation bar; with it gone the orchard art shows through. If the
      buttons lose contrast, set an explicit scrim colour on `android:navigationBarColor` for the affected
      levels rather than reinstating the call.
- [x] Confirm the classes are actually gone: after `:app:assembleRelease`, search the R8 mapping —
      `Select-String -Pattern "EdgeToEdge" app\build\outputs\mapping\release\mapping.txt` should find nothing
      (R8 keeps a mapping line for every class it retains).
- [x] Do **not** reach for `android:windowOptOutEdgeToEdgeEnforcement`: it is deprecated and disabled for apps
      targeting API 36, which this app does.
- [x] Accept one caveat: if a *dependency* (Ads/UMP, Play Games, Billing) also references these APIs, the
      suggestion may survive with a different location. The authoritative check is the Console report against
      the next processed bundle — record the outcome here.


**Result.** `enableEdgeToEdge()` replaced by `MainActivity.applyEdgeToEdge()`; `Theme.Whaaack` split into a
`Base` style plus a `values-v27` override carrying `windowLayoutInDisplayCutoutMode=shortEdges`. Verified
against the release bundle's DEX with `dexdump`:

| Flagged usage | After |
| --- | --- |
| `Window.setStatusBarColor` | **gone** — no reference anywhere in `classes.dex` |
| `Window.setNavigationBarColor` | **gone** |
| `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` | still present, but **not ours**: the only writers left are `com.google.android.gms.ads.internal.overlay.zzag` (Google Mobile Ads SDK) and `com.google.android.play.core.hsdp.service.HsdpShimActivity` (Play Core) |

`androidx.activity.EdgeToEdgeApi*` no longer appears in `mapping.txt` — R8 stripped it once the call site
went. Expect the Console to clear two of the three lines and to keep the cutout one until those SDKs update;
that one is now a dependency's to fix, and nothing in this app can retire it.

## Step 4 — Cutouts and insets, verified (suggestion 4)

`enableEdgeToEdge()` was already being called, so the app was already edge-to-edge; what is missing is cutout
coverage. `systemBarsPadding()` does not include the display cutout, which never mattered while the app was
locked to portrait (the cutout sits inside the status bar there) and starts mattering the moment Step 5 allows
landscape.

- [x] Add `.displayCutoutPadding()` next to the existing `.systemBarsPadding()` on every screen root:
      [HomeScreen.kt:67](app/src/main/java/tech/idct/whaaack/ui/HomeScreen.kt#L67),
      [AuthScreen.kt:89](app/src/main/java/tech/idct/whaaack/ui/AuthScreen.kt#L89) and
      [:310](app/src/main/java/tech/idct/whaaack/ui/AuthScreen.kt#L310),
      [GameOverScreen.kt:46](app/src/main/java/tech/idct/whaaack/ui/GameOverScreen.kt#L46),
      [LeaderboardScreen.kt:57](app/src/main/java/tech/idct/whaaack/ui/LeaderboardScreen.kt#L57),
      [SettingsScreen.kt:74](app/src/main/java/tech/idct/whaaack/ui/SettingsScreen.kt#L74) and
      [:555](app/src/main/java/tech/idct/whaaack/ui/SettingsScreen.kt#L555),
      [AboutScreen.kt:80](app/src/main/java/tech/idct/whaaack/ui/AboutScreen.kt#L80),
      and the toast in [MainActivity.kt:392](app/src/main/java/tech/idct/whaaack/MainActivity.kt#L392).
      **Not** `safeDrawingPadding()`: that folds in the IME inset and would double up with the deliberate
      `imePadding()` mechanism the auth forms rely on
      ([AuthScreen.kt:89-99](app/src/main/java/tech/idct/whaaack/ui/AuthScreen.kt#L89-L99)).
- [x] `GameSurfaceView`: widen the inset read from `systemBars()` to
      `systemBars() or displayCutout()` in both the listener
      ([GameSurfaceView.kt:116-122](app/src/main/java/tech/idct/whaaack/game/GameSurfaceView.kt#L116-L122))
      and `readRootInsets()`
      ([:304-310](app/src/main/java/tech/idct/whaaack/game/GameSurfaceView.kt#L304-L310)), and carry `left` and
      `right` as well as `top`/`bottom` — the renderer needs them for landscape (Step 5), and the two `@Volatile`
      fields become four.
- [x] Extend `GameRenderer.onSurfaceChanged(w, h, topInset, bottomInset, assets)` to take left/right insets and
      to inset the card, the End-run pill and the board from them.
- [x] Test on a cutout device/emulator (Developer options → *Display cutout* → punch-hole and tall) in both
      orientations: no control under the cutout, no control under the rounded corners, and — the reason the
      pill's position is inset-derived — the End-run pill still reachable above the gesture bar.


**Result.** `displayCutoutPadding()` added beside `systemBarsPadding()` on all nine screen roots and the
toast; `GameSurfaceView` now reads `systemBars() or displayCutout()` and carries all four insets into
`GameRenderer`, whose top bar, score card, End-run pill and board are measured from the safe area rather than
the window.

One real bug fell out of testing this: **the ad-break and ranked-invite dialogs were measured unbounded**, so
in a short window the panel grew past the bottom of the screen and took *Continue* with it — and the
`verticalScroll` both carry (added for large font scales) had nothing to scroll against, so the button was
simply unreachable. Both are now wrapped in a window-filling, safe-area-padded, centred `Box`. This was
visible in landscape but the same failure applies in portrait at a large enough font scale.

## Step 5 — Large screens (suggestion 2): **declined, deliberately**

Whaaack! is a two-thumb portrait reflex game and is not offered in landscape. That is a product decision,
taken after the unlocked build was tried on device, and it is the one suggestion of the four that will stay
open in the Console.

It costs nothing in compliance: games declaring `android:appCategory="game"` are an explicit exception both
to Android 16's ignoring of orientation/resizability restrictions on displays ≥ sw600dp and to Android 17's
removal of the temporary opt-out. `screenOrientation="portrait"` and `resizeableActivity="false"` therefore
still hold everywhere, and the `appCategory` declaration is what makes them hold — the manifest says so.

- [x] Manifest left locked: `screenOrientation="portrait"`, `resizeableActivity="false"`,
      `tools:ignore="DiscouragedApi,LockedOrientationActivity"`, `appCategory="game"`.
- [x] Landscape *was* built and tried first — HUD in a column beside the board, menus scrolling — and rejected
      on gameplay grounds, not technical ones. What that work left behind is kept, because it pays off in
      portrait too and is what makes an unexpected window survivable rather than fatal:
  - [x] The board is sized from whatever window arrives, capped at 88dp per tile, with a compacted score card
        as a fallback and a stacked-or-beside choice made by measurement
        ([GameRenderer.layOut](app/src/main/java/tech/idct/whaaack/game/GameRenderer.kt)). Free-form and
        desktop windowing do not always honour `resizeableActivity="false"`; a phone-shaped window is no
        longer the only one that draws a board.
  - [x] Menus cap their content at 560dp and centre it (`Modifier.menuColumnWidth`), so a tablet does not
        stretch a two-word button across the screen.
  - [x] Home and Game over scroll and trim their display type below 560dp of window height
        (`isShortScreen`), which is also what a large system font scale produces on a phone.
  - [x] The ad-break and ranked-invite dialogs are measured against the window instead of unbounded — see
        Step 4's result for the bug that fixed.
- [ ] Not done, and not planned: adaptive landscape layouts, tablet screenshots for the listing, foldable
      testing. Reopen only if the game is ever offered in landscape.


## Devices still needed to finish verification

Everything below was checked on the one emulator available here — **API 37, 1280×2856 @480dpi (sw427dp)** —
including a simulated large screen via `adb shell wm size 2560x1600; adb shell wm density 240`, which is a
fair stand-in for a tablet's *layout* but not for its hardware. What that emulator cannot answer:

| AVD to create | Why it is needed | What to check |
| --- | --- | --- |
| **API 26 phone** (e.g. Nexus 5X, Android 8.0, Google APIs) | `applyEdgeToEdge()`'s pre-35 branch is the one this project now owns instead of androidx, and API 26 is `minSdk` — the oldest path through it. It is also the level with no `windowLayoutInDisplayCutoutMode` at all. | Status and navigation bars transparent with the orchard drawn under them; bar icons light; no content under the bars on any screen; the auth form still scrolls with the keyboard up (below API 30 that is `adjustResize`, not `imePadding`). |
| **API 28 phone with a cutout** (Pixel 3 XL profile, or any AVD + Developer options → *Display cutout*) | The `values-v27` `shortEdges` theme attribute replaces the constant androidx used to set, and 27–34 is the only range where it does anything. | Orchard reaches into the notch; the mode chip, strike dots and score card clear it; nothing important under the corners. |
| **API 34 phone** | The last level before edge-to-edge enforcement: bar transparency comes from the theme attributes, not from the framework. | Same as API 26, plus that the bars are not opaque black. |
| **API 35/36 tablet** (Pixel Tablet profile, sw800dp+) | Confirms the 560dp menu cap and the 88dp tile cap on real large-screen metrics, and that the portrait lock genuinely pillarboxes rather than stretching. | Menus centred, not stretched; board no larger than ~410dp; a full run playable. |
| **Foldable** (Pixel Fold profile) — optional | Folding changes the window under a live run. | Fold and unfold mid-run: no crash, no lost score, board resized. |

A physical device is still the only place to check the two things no emulator answers: haptics on a strike,
and Play Games achievements (see the note below).

## After each release

- [ ] Bump `versionCode` (7 → 8 → 9) and pick a `versionName` in
      [app/build.gradle.kts:68-69](app/build.gradle.kts#L68-L69).
- [ ] Upload to internal testing first; read the pre-launch report, including its tablet and foldable devices.
- [ ] Promote, wait for the bundle to be processed, then re-read Console → *App quality* and tick off which of
      the four suggestions actually cleared. Record the answer here — especially for suggestion 3, where a
      surviving warning would mean a dependency, not our code, holds the reference.

## Sources

- [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [Improve app performance with optimized resource shrinking](https://android-developers.googleblog.com/2025/09/improve-app-performance-with-optimized-resource-shrinking.html)
- [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) ·
  [AGP version compatibility](https://developer.android.com/build/releases/about-agp) ·
  [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android 15 behaviour changes — edge-to-edge enforcement](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Android 16 behaviour changes — orientation/resizability and edge-to-edge](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 17 — restrictions on orientation and resizability are ignored](https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored)
- [The future is adaptive (Android Developers Blog)](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html)
- androidx source read directly for this plan: `activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt`
  and `core/core/src/main/java/androidx/core/view/WindowCompat.java` (androidx-main).
