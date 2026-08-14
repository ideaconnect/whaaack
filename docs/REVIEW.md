# Deep review — 2026-08-14

A pass over stability, code quality, performance, crash exposure, fairness, the external
services and the graphics. Everything below marked **Fixed** is done and building.

`./gradlew :app:testDebugUnitTest` passes (15 tests, 4 of them new). `:app:assembleDebug`,
`:app:assembleRelease` (R8 + resource shrinking, no missing keep rules) and `:app:lintDebug`
are all clean — lint's only error is the un-escaped SDK path in the git-ignored
`local.properties`, on this machine.

Everything raised in this review is now resolved. Two items are done in code but still need a
human action outside the repo before release:

- the AdMob unit `…/2703686934` must be of type **Interstitial** in the console (#18), and
- the ads SDK upgrade adds five transitive permissions, so the Play **Data safety**
  declaration wants a look (#19a).

---

## Crashes and stability

### 1. Recycled bitmaps could be drawn — **Fixed**

`WhaaackViewModel.onCleared()` called `assets.recycle()`. Those same `Bitmap`s are also held
by Compose, wrapped in `ImageBitmap`s for the menu backdrop and the home-screen fruit chips,
and `setContent`'s composition is disposed when the **window detaches** — which happens
*after* `ViewModelStore.clear()` runs at `ON_DESTROY`. A frame still in flight then draws
freed pixels and the process dies with `Canvas: trying to use a recycled bitmap`.

The whole sprite set is ~4.6 MB of ordinary Java-heap objects, so manual recycling bought
nothing. `GameAssets.recycle()` is gone entirely rather than merely unused, so it cannot be
reintroduced by someone who does not know about the ordering; `onCleared` just drops the
reference. ([GameAssets.kt](../app/src/main/java/tech/idct/whaaack/game/GameAssets.kt),
[WhaaackViewModel.kt](../app/src/main/java/tech/idct/whaaack/WhaaackViewModel.kt))

### 2. MediaPlayer driven into its Error state, killing the music — **Fixed**

`applyTrack` called `start()` on the current player whenever the requested track already
matched, including while that player was still inside `prepareAsync`. `start()` from the
Preparing state is an illegal transition: the engine reports an error, the object moves to
Error, `onPrepared` never fires, and the track is silently dead for the rest of the session.

This was reachable on essentially every navigation, because the track was being set from two
places at once — `WhaaackViewModel.navigate()` posted one `playTrack` and
`MainActivity`'s `LaunchedEffect(state.screen)` posted another, back to back.

Three parts to the fix: a `preparing` flag that keeps `applyTrack` off a loading player; an
error listener that tears the failed player down (posted, because an error can arrive
synchronously from `prepareAsync` before the field it must clear has been assigned) so the
next `playTrack` builds a fresh one; and a single owner for the track — the `LaunchedEffect`,
moved above the game screen's early return so it is reached whatever is on screen.
`playTrack` now has exactly one caller.
([AudioEngine.kt](../app/src/main/java/tech/idct/whaaack/audio/AudioEngine.kt),
[MainActivity.kt](../app/src/main/java/tech/idct/whaaack/MainActivity.kt))

### 3. Engine state could be rewritten from the UI thread — **Fixed**

`GameSurfaceView.startRun` called `engine.start()` directly whenever a render thread already
existed. `start()` does `slots.fill(null)` and `splats.clear()`, both of which the render
thread walks every frame. Unreachable today only because the `AndroidView` factory happens to
run before `surfaceCreated` — a latent trap rather than a live bug, and the same one
`quitRun()` already exists to avoid. `startRun` now only sets a `@Volatile` request that the
render thread applies at the top of a frame, which also removes the special-casing for
"surface does not exist yet".
([GameSurfaceView.kt](../app/src/main/java/tech/idct/whaaack/game/GameSurfaceView.kt))

### 4. Taps accumulated in a queue nothing drained — **Fixed**

`postTap` accepted taps in every phase but `drainTaps` runs only from `stepRun`. Taps made
during the outro or after the run ended were never consumed, and taps made during the
countdown were *banked* and applied in one burst on the first live frame. `postTap` now
ignores anything outside `RUNNING`, and the countdown clears the queue as it hands over.
([GameEngine.kt](../app/src/main/java/tech/idct/whaaack/game/GameEngine.kt))

### 5. Degenerate board geometry — **Fixed**

On a viewport too short to hold the score card, the board and the End-run pill,
`maxBoardHeight` goes negative and `tileSize` with it, feeding inverted `RectF`s to every
draw call and an inverted destination to every `drawBitmap`. `tileSize` is clamped at zero
and `drawBoard` returns early.
([GameRenderer.kt](../app/src/main/java/tech/idct/whaaack/game/GameRenderer.kt))

### 6. `TOP_SPEED_LEVEL` derivation was an unbounded loop — **Fixed**

It is computed at class-init by walking the curve until both tracks reach their floor. A
retune where either track never saturates would hang class initialisation rather than fail
somewhere legible. Bounded at 1000, and `speedFraction` now guards its divisor.

---

## Fairness

### 7. Coming back from the background dropped you into a live board — **Fixed**

This was the worst of them. Backgrounding destroys the surface, which pauses the engine
correctly — but `surfaceCreated` resumed straight back into `RUNNING`. Whatever was on the
board when you left is by then most of the way through its life, so the first thing you get
on return is a strike you had no way to prevent. At the late levels, where fruit lives 430 ms,
that is frequently the run.

A resumed run now re-enters `COUNTDOWN` for `RESUME_COUNTDOWN_MS` (2 s, shorter than the
opening 3 s — you already know what you are looking at). The countdown is folded into the
same deadline shift the pause already performed, so the clock, the fruit and the spawn
schedule all come back exactly where they were left, and neither the time away nor the
countdown itself counts as time survived. Covered by
`an interrupted run comes back through a countdown, not mid-air`.

### 8. Quitting during that countdown would have zeroed the score — **Fixed**

`finish()` zeroed `elapsedMs` for any non-`RUNNING` phase, which was right when the only
countdown was the opening one. With #7 it would have thrown away a long run's score if the
player pressed End run while the board was frozen. It now keeps the score for a resume
countdown and zeroes only for the opening one. Covered by
`quitting during the resume countdown keeps the score`.

### 9. The second finger was ignored — **Fixed**

`onTouchEvent` handled only `ACTION_DOWN`, so a second thumb landing while the first was
still down produced `ACTION_POINTER_DOWN` and was dropped on the floor. Four fruit are up at
once from the seventh gear; two-thumb play is not an edge case, it is how the game is meant
to be played, and every one of those taps became a strike. Both down actions are handled now,
and the coordinates come from `event.actionIndex` rather than from whichever pointer happens
to sit at index 0.

### 10. The gutters between tiles swallowed taps — **Fixed**

`tileAt` rejected the 10 dp gaps so a near-miss registered as nothing. But tapping bare board
costs the player nothing, so refusing a near-miss could never help them — it could only turn
a whack they meant into a strike they did not. Every point inside the board card now maps to
the nearest tile.

### 11. Client-authored scores had no server-side floor — **Open → Fixed in SQL**

Anything holding the anon key can `POST /rest/v1/scores`; the only server check was
`0 ≤ millis ≤ 24 h`. The new migration adds two `NOT VALID` check constraints (`NOT VALID` so
they bind every future insert without risking failure against existing rows) and a
rate-limit trigger:

- `hits ≤ 40 × (millis / 1000) + 20`. Four slots cycle at ~200 ms once the curve bottoms out,
  so a flawless player tops out near 35 fruit/second and only in the late game, while the
  budget accrues across the slow early levels too.
- `top_speed ≤ 64` — the HUD's readout saturates; no run can report a gear the curve lacks.
- No more than 20 inserts per user per minute. A run opens with a three-second countdown it
  cannot even be lost during, so this is a flood stop that legitimate play never approaches.

These are deliberately loose. They exist to stop a forged score owning the board, not to
referee a good one. A tight check is available if you want it — `top_speed` is exactly
`min(⌊millis / 4000⌋, 10) + 1` — but it would couple the schema to the client's difficulty
constants, and those have already been retuned once.

### 12. `my_standing` was blind past rank 200 — **Fixed in SQL**

It read its answer out of `leaderboard(p_scope, 200)`, and `leaderboard` clamps its own limit
to 200 rows. Every player outside the top 200 therefore got **no row at all** — the
leaderboard footer showed "—", and the Home card quietly fell back to
`prefs.localBestMillis`, presenting the device's *casual* best as the player's ranked
standing. It now ranks over the whole set; same scan, without the truncation.

---

## External services

### 13. Auth tokens were included in cloud backup and device transfer — **Fixed**

`android:allowBackup="true"` with no rules, and `SessionStore` writes a Supabase access
*and* refresh token into a plain DataStore file. Both were being uploaded to Google Drive
backup and copied by device-to-device transfer; a refresh token landing on another device is
a working key to the account. Added `backup_rules.xml` and `data_extraction_rules.xml` that
carry only `whaaack_settings.preferences_pb` (naming any `<include>` makes the set
exhaustive, which is exactly what keeps the session out), and wired both into the manifest.

### 14. The auth deep link persisted a broken session — **Fixed**

`handleAuthDeepLink` saved the tokens with `userId = ""` and *then* asked the server who they
belonged to. If that second call failed — offline mid-flow is the obvious way — the app kept
a session with an empty user id, after which every profile read and write became
`?id=eq.`, which PostgREST answers with a 400. `refreshProfile` only signs out on a 401, so
the player stayed signed in and permanently broken with no way back short of clearing data.
The account is now resolved *before* anything is written (via a new `SupabaseClient.getAs`,
a one-shot authorized GET against a token that is not yet the stored session), and the
session is saved once, complete. A link that cannot be verified says so and writes nothing.

### 15. Deep-link fragment values were not percent-decoded — **Fixed**

`error_description` in particular read as gibberish. Decoding is safe for the tokens too:
they are base64url, whose alphabet contains neither `%` nor `+`.

### 16. `profiles` was readable by everyone — **Fixed in SQL**

The leaderboard is served through a `SECURITY DEFINER` function specifically so that raw
score rows stay unreadable — but `profiles` carried `using (true)`, handing every display
name straight back through PostgREST to anyone with the anon key. The app only ever reads its
*own* profile row (verified: both call sites are `?id=eq.${session.userId}`), and the board
function bypasses RLS as its owner, so the policy is now `auth.uid() = id`.

### 17. An ad after every single run — **Fixed**

Both routes off the game-over screen asked for one, so a player alternating "Play again" with
a thirty-second run saw an ad between every attempt. `AdsManager` now caps this at one shown
ad every two minutes. A failed show does not count against the cap. The ad object is also
retired *before* `show()` rather than in the callbacks — an ad is single-use, and leaving it
in the field let a second `showThen` re-point its content callback at a new closure,
stranding the first caller whose continuation would then never run.

### 18. The rewarded format granted no reward — **Fixed**

The placement is now a plain **Interstitial** on unit
`ca-app-pub-6904561240517963/2703686934`, which is what it always behaved like. That removes
the policy exposure outright: the rewarded formats expect an explicit value exchange, and
there was none to offer.

`RewardedInterstitialAd` → `InterstitialAd`, `RewardedInterstitialAdLoadCallback` →
`InterstitialAdLoadCallback`, and `show(activity) { reward -> }` → `show(activity)`. The
`BuildConfig` field and the `local.properties` / CI key are renamed
`ADMOB_REWARDED_AD_UNIT_ID` → `ADMOB_INTERSTITIAL_AD_UNIT_ID`, and the debug override now
uses Google's reserved **interstitial** test id `ca-app-pub-3940256099942544/1033173712` —
the test id's format has to match the format the code loads or it will never fill either.

To be clear about the reason: `RewardedInterstitialAd` still exists in 25.4.0 (verified with
`javap` against the AAR). This was a product decision, not a forced migration.

> **Release gate:** unit `…/2703686934` must be of type **Interstitial** in the AdMob
> console. That is the one thing that cannot be verified from here, and a load against a
> mistyped unit simply never fills.

### 19. Ads and UMP SDKs were two majors behind — **Fixed**

`play-services-ads` 23.6.0 → **25.4.0** and `user-messaging-platform` 3.1.0 → **4.0.0**.
Debug, release (R8 + resource shrinking) and the unit tests all build clean, and R8 emitted
no `missing_rules.txt`, so no new keep rules were needed.

The upgrade was verified against the published artifacts rather than the release notes,
because the two disagree in places:

- **minSdk.** All three AARs (`play-services-ads`, `play-services-ads-api`,
  `user-messaging-platform`) declare `minSdkVersion="23"`. This app is 26 — clear by three
  levels. That was the only hard requirement either major introduced.
- **No removed API.** Every symbol the app uses survives in 25.4.0 / 4.0.0, confirmed by
  `javap` against the AARs' `classes.jar` — including all of `ConsentManager`'s surface.
  `ConsentManager.kt` needed **zero** changes.
- **UMP arrives transitively.** `play-services-ads:25.4.0` depends on `play-services-ads-api`
  at strict `[25.4.0]`, which declares `user-messaging-platform:4.0.0` at compile scope. The
  explicit pin in the catalog is for readability, not control — leaving it at 3.1.0 would
  *not* have held the app on 3.1.0.
- **The `DebugGeography` rework was in 3.1.0, not 4.0.0**, so this project had already
  absorbed it. `DEBUG_GEOGRAPHY_EEA` is present and not deprecated.

### 19a. The upgrade changes the permissions users see on Play — **Needs a Play Console pass**

Read out of the merged manifest, not predicted. Five permissions are new, all pulled in
transitively — the app itself requests nothing extra:

| New permission | Source |
| --- | --- |
| `ACCESS_ADSERVICES_AD_ID` | `play-services-ads-api` (Privacy Sandbox) |
| `ACCESS_ADSERVICES_ATTRIBUTION` | `play-services-ads-api` (Privacy Sandbox) |
| `ACCESS_ADSERVICES_TOPICS` | `play-services-ads-api` (Privacy Sandbox) |
| `WAKE_LOCK` | `androidx.work:work-runtime` (new transitive) |
| `FOREGROUND_SERVICE` | `androidx.work:work-runtime` (new transitive) |

Nothing to change in code, but the **Data safety** declaration and the permissions listing
should be reviewed before the next upload. The release APK is 8.7 MB (4.4 MB of that is
bundled assets), so the added surface has not cost anything meaningful in size.

### 19b. `preload()` could issue an Ads SDK call off the main thread — **Fixed**

Found while auditing the upgrade, and it predates it. `MobileAds.initialize` is the one GMA
call documented as safe off the main thread, and `initialize()` deliberately makes it from
its own `ads-init` thread — but the completion listener then called straight back into
`preload()`, so the first `InterstitialAd.load` of a session could be issued from that
thread. `ad` and `loading` were also written from the load callback and read from the main
thread with no synchronisation. `preload()` now hops to the main thread, which both fixes the
SDK-threading contract and makes those two fields main-thread-confined, so they need no
synchronisation at all.

---

## Graphics

### 20. Splats were drawn unfiltered while rotated — **Fixed**

`Paint(int flags)` sets *exactly* the flags given, so `Paint(ANTI_ALIAS_FLAG)` leaves
`FILTER_BITMAP` off — unlike the no-arg `Paint()`, which has had it on by default since API
24. The splat masks are 256 px organic blobs drawn at an arbitrary rotation and scaled down,
not pixel art at 1:1, and every one was coming out with a visibly stair-stepped rotated edge.
Filtering is now on for splats only; the fruit and the outro burst keep nearest-neighbour,
which is correct for them.

### 21. Fruit sprites at fractional scale — **No action needed**

Re-examined, and the original note overstated it. The 32 px fruit are drawn
nearest-neighbour at roughly 4.9× on a 1080-wide device, so pixel rows land unevenly (some
5 px, some 4) — but that artifact is **static, not shimmering**, which is the part that
matters. `drawFruit` computes `rise = min(1f, ageMs / 150f)` and
`scale = 0.72f + rise * 0.28f`, so the scale is pinned at exactly 1.0 after the first 150 ms
and the sprite then holds one fixed size for the whole rest of its life. The only moment the
scale changes at all is during that 150 ms rise, when the sprite is also translating
upward — motion that hides it completely.

Uneven pixel rows on a resting sprite is simply what upscaled pixel art looks like, and it is
the intended aesthetic here.

Snapping the draw size to an integer multiple of 32 would give perfectly uniform pixels, and
on a 1080×2400 device it is nearly free (158 px → 160 px, a 1.1 % change). But `tileSize`
varies with screen size, so on a device that lands at 4.4× the same snap is a 19 % shrink —
trading a barely visible artifact for fruit that are noticeably different sizes across
devices. **Leaving this alone is the correct call.**

---

## Performance

The render loop is in good shape and I did not find much worth changing. Worth recording what
was checked and found sound: `lockHardwareCanvas`/`unlockCanvasAndPost` paces the loop
against the buffer queue, so there is no busy spin and no need for explicit frame pacing;
`update` and `expireSplats` are allocation-free per frame; the shaders and gradients are all
built in unit space and re-aimed with a local matrix rather than reallocated; `OkHttpClient`
is a single shared instance; and the `Mutex` around token refresh is not re-entered on any
path (`refreshSession` issues its request with `authorized = false`, so it never recurses
into `validAccessToken`).

Two real items:

- **Music work was posted on every unrelated preference write** — **Fixed.** The settings
  flow re-publishes all preferences on every write, so `audio.musicEnabled = prefs.music` was
  assigned again whenever sound, haptics, parallax *or the local best* changed, and each
  assignment posted an `applyTrack` to the main thread. The setter now ignores a no-op write.
  Since the local best is written at the end of every run, this fired constantly.
- **Ad object lifetime** — **Fixed**, see #17.

---

## Code quality

- Dead code removed: `ScoreNumberStyle` (Components.kt) and `AuthBusyOverlay` (AuthScreen.kt)
  had no callers.
- Fully-qualified names replaced with imports: `android.graphics.RadialGradient`,
  `tech.idct.whaaack.data.Session`, `VisualTransformation`, and four `Stroke` references in
  `GoogleGlyph` that were 60 characters wide apiece.
- `android.os.SystemClock` was wedged into the middle of the androidx import block.
- The music track had two owners; it now has one (#2).

---

## The database changes — **applied**

`supabase/migrations/20260814000000_hardening.sql` covers #11, #12 and #16, and has been
pushed to the linked project (`pklrfcbyseitdbxkmsnw`). `supabase migration list` now shows
both migrations present locally and remotely.

`supabase db dump` needs Docker, which is not available here, so no backup was taken — but
none was needed: every statement is DDL that touches no rows (two `NOT VALID` check
constraints, one index, one `create or replace` on each of two functions, one trigger, and a
policy swap), and each is reversible from definitions already in the repo.

The three post-push checks from the review were run against the live REST API using the app's
own anon key, i.e. exactly the path the client takes:

| Check | Before | After |
| --- | --- | --- |
| Anon `rpc/leaderboard`, both scopes | 200, 1 row | **200, 1 row** — unchanged |
| Anon direct `GET /rest/v1/profiles` | 200, **2 names leaked** | **200, `[]`** |
| Anon `rpc/my_standing` | — | 200, `[]` (no `auth.uid()`) |

The first row is the one that mattered: it proves `leaderboard()` still bypasses the
tightened `profiles` policy as its owner, which was the single risk in the change. The second
is the privacy hole closing — anon really could enumerate every display name before.

The rank-past-200 fix (#12) cannot be observed on this dataset, which has one ranked player;
it is a correctness change that only shows up once the board is deeper than 200.
