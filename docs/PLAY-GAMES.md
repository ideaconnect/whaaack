# Play Games Services

Achievements, and the sign-in they hang off. The app is complete and the four achievements
exist in the console with their ids wired in, so the only thing standing between this and a
working unlock is a signing question — see [Which certificate](#2-which-certificate), which
is the part most likely to waste an afternoon.

**Project ID: `964578061899`** — in `app/src/main/res/values/strings.xml` as
`game_services_project_id`, referenced from the manifest. Not a secret; it ships in the APK.

---

## 1. What the app already does

| Piece | Where |
| --- | --- |
| SDK | `play-services-games-v2:22.0.0`, `gradle/libs.versions.toml` |
| App id | `game_services_project_id` → `com.google.android.gms.games.APP_ID` meta-data |
| Init | `PlayGamesSdk.initialize(this)` in [`WhaaackApp`](../app/src/main/java/tech/idct/whaaack/WhaaackApp.kt) |
| Sign-in, unlocks, UI | [`PlayGamesManager`](../app/src/main/java/tech/idct/whaaack/games/PlayGamesManager.kt) |
| What a run earns | [`Achievement`](../app/src/main/java/tech/idct/whaaack/games/Achievement.kt), tested in `AchievementTest` |
| Achievement ids | Committed defaults in `app/build.gradle.kts`, see [§5](#5-the-achievement-ids) |
| Game Stats | [`GameStats`](../app/src/main/java/tech/idct/whaaack/games/GameStats.kt) + `assets/game-stats/`, see [§6](#6-game-stats) |
| Player-facing entries | Settings → *Play Games*; Home → *Achievements* chip |

v2 has no sign-in flow to drive: `initialize()` attempts one automatically at launch, so the
app only ever *asks* whether that worked (`isAuthenticated()`, on every `onResume`) and offers
a button that calls `signIn()` if it did not.

**Signing into Play Games is not signing into Whaaack!** That distinction still holds: Play
Games owns achievements, a Whaaack! account owns your leaderboard score, and `signIn()`
creates no account.

What changed is that the first can now *produce* the second. A Play Games leaderboard can only
hold Play Games players and ours holds Whaaack! accounts, so a player with only the former had
no way to be ranked at all. Since [§8](#8-accounts-minted-from-play-games) there are two ways
in, both minting the same account:

| Where | What raises it |
| --- | --- |
| Home → **Play ranked** | The invitation dialog, then the account is minted behind it |
| Auth screen → **Continue with Play Games** | Nothing — the button press is the intent |

So Play Games is a third way to sign in, beside email and Google, and all three can log in,
log out, play ranked and put scores in Supabase. It is styled as its own provider rather than
as a second Google pill, because the two are only interchangeable on that one screen.

Runs are recorded to the device's personal best whether or not anything is signed in, and
`syncPlayGames` replays that best on every resume. So a player who has been playing signed
out, offline, or on a device with no Play Games gets every milestone they already earned the
moment they do sign in. Nothing needs re-earning.

---

## 2. Which certificate

Play Games identifies the app by **package name + signing certificate**, matched against an
OAuth *Android* client. There is nothing to paste into the code — which is also why a build
signed with the wrong key fails to authenticate and can only tell you `isAuthenticated()`
said false.

The fingerprint currently on `964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1` is

```
B0:83:0D:DA:DD:E3:54:16:61:3B:B9:D1:53:30:E2:B9:D9:1A:25:0C
```

That is **neither of the certificates on this machine**:

| Certificate | SHA-1 |
| --- | --- |
| Upload key (`whaaack-upload.jks`) | `50:1F:E5:E0:75:FA:5A:93:3F:35:50:05:58:25:D2:DE:26:8B:13:4B` |
| Debug key (`~/.android/debug.keystore`) | `A8:87:91:6F:1B:42:11:BA:82:4F:C8:5B:0A:8A:83:DC:BD:9B:07:EB` |

So `B0:83:…` is almost certainly the **Play App Signing** certificate — the key Google
re-signs with after upload, from Play Console → Test and release → App integrity. That is the
correct fingerprint for what players install, and the wrong one for anything you build here:
a Google OAuth Android client holds exactly one SHA-1, so with `B0:83:…` on it, **no locally
built APK will authenticate** — not a debug build, not even one signed with the upload key.

Two consequences, both worth acting on before testing:

1. **Add a second Android OAuth client** carrying the debug SHA-1 above, and register it in
   Play Games Services → Configuration → Credentials as another Android credential. PGS
   accepts several; Google Cloud's OAuth clients only hold one fingerprint each.
2. **Check `GOOGLE_WEB_CLIENT_ID` still works.** [SETUP.md §3](SETUP.md#3-google-sign-in)
   records the debug SHA-1 as living on this same client. If setting `B0:83:…` replaced it
   rather than being added to a new client, Google *sign-in* on debug builds is broken too,
   and the symptom is the same silence.

The alternative to all of this is to test from an internal-testing track build, which Play
has re-signed with `B0:83:…` and which therefore matches — worth knowing, but a slow loop to
develop against.

---

## 3. Console setup, in order

1. ~~**Play Games Services → Configuration**~~ — done, project `964578061899`.
2. **Credentials** — one Android credential per signing certificate you need to work. See
   §2. Each links to an OAuth client with a matching package name and SHA-1. **Outstanding**:
   nothing built on this machine can authenticate until a client carries the debug SHA-1.
3. ~~**Achievements**~~ — done, all four created; ids in §5.
4. **Testers** — Play Games Services → Testers. Until the PGS configuration is *published*,
   only listed accounts can authenticate at all; a tester-less setup looks exactly like a
   broken one.
5. **Publish** the Play Games Services configuration. Separate from publishing the app, and
   easy to forget.

---

## 4. The four achievements

Icons: `assets/achievements/survive-{30,60,90,120}.png` — 512×512 PNG, ~83 KB each, built by
`tools/generate_achievement_icons.py` from the game's own sprites. Re-run that rather than
retouching them.

For every one of the four:

- **Incremental**: no. Unchecked. This is permanent once published, and it has to be
  unchecked — an incremental achievement accumulates across runs, which would let someone
  reach "two minutes" in four half-minute attempts. The claim is about a *single* run.
- **Initial state**: Revealed. These are goals to aim at, not surprises.
- **List order**: 30 → 60 → 90 → 120.

The numbers in the descriptions are the game's real ramp (speed steps up every 4 s; the board
is 16 tiles), so they stay true unless `GameEngine`'s ladder is retuned.

### 30 seconds — icon `survive-30.png`

| Field | Value |
| --- | --- |
| Name | `Warmed Up` |
| Description | `Survive 30 seconds in a single run — 30,000 on the clock. By then the orchard is throwing three fruit at you at once.` |
| Points | 50 |

### 60 seconds — icon `survive-60.png`

| Field | Value |
| --- | --- |
| Name | `Minute Made` |
| Description | `Survive a full minute in a single run — 60,000 on the clock, four fruit in the air, and only ever three strikes to spend.` |
| Points | 100 |

### 90 seconds — icon `survive-90.png`

| Field | Value |
| --- | --- |
| Name | `Still Standing` |
| Description | `Survive 90 seconds in a single run — 90,000 on the clock. Six fruit at a time now, and the speed is still climbing.` |
| Points | 150 |

### 120 seconds — icon `survive-120.png`

| Field | Value |
| --- | --- |
| Name | `Orchard Legend` |
| Description | `Survive two whole minutes in a single run — 120,000 on the clock, with eight fruit on the board at once. Half the orchard, all at you.` |
| Points | 200 |

500 of Play's 1,000 achievement points, leaving room for four more of the same weight later.

---

## 5. The achievement ids

Published, and committed as defaults in `app/build.gradle.kts`:

| Milestone | Id |
| --- | --- |
| 30 seconds | `CgkIy9zUqokcEAIQAQ` |
| 60 seconds | `CgkIy9zUqokcEAIQAg` |
| 90 seconds | `CgkIy9zUqokcEAIQAw` |
| 120 seconds | `CgkIy9zUqokcEAIQBA` |

They are public and permanent — they ship in every APK, and the console will not re-issue one
after deletion — so they are committed for the same reason the AdMob application id and
`no.ads.forever` are. A fresh clone and a CI runner with no secrets both award achievements
rather than silently awarding nothing.

Each base64url-decodes to a protobuf carrying project `964578061899` — the same id the
manifest carries — followed by the achievement's index in creation order, 1 to 4. That is
what ties this list to this console and no other, and it is worth re-checking if these are
ever regenerated:

```bash
python -c "import base64,sys; print(base64.urlsafe_b64decode(sys.argv[1]+'==').hex())" CgkIy9zUqokcEAIQAQ
# 0a0908cbdcd4aa891c10021001
#       ^^^^^^^^^^^^^^ varint 964578061899          ^^^^ index 1
```

`local.properties` (or an environment variable of the same name) still overrides each one,
which is the way to point a build at a different console project:

```properties
PGS_ACHIEVEMENT_SURVIVE_30=CgkI…
```

`PlayGamesManager.CONFIGURED_IDS` reads them once. `AchievementTest` asserts all four are
present, non-blank and distinct — the four differ only in their final character, so pasting
one twice is the mistake that actually happens, and its symptom is a player earning the wrong
badge. What no test can check is that a given id is the *30-second* achievement rather than
the 120-second one; only the console knows that, which is what the decode above is for.

A blank id is skipped before it is ever sent, and an id that does not resolve is not a crash —
Play Games rejects the unlock and `PlayGamesManager` logs which milestone and which id under
the `PlayGames` tag.

---

## 6. Game Stats

Cumulative figures on the player's Gamer profile — runs played, time survived, fruit whacked
— built by the server from events the game sends. Separate from achievements: an achievement
is a badge you earn once, a stat is a number that keeps moving.

`GameStatsClient` ships in the same `play-services-games-v2:22.0.0` already on the dependency
list, so this needed no new dependency and no manifest change.

**Timing.** Google's own page says the API "will be Generally Available (GA) starting August
2026", with players seeing stats on their Gamer profile from September 2026. So events sent
now are being recorded ahead of a surface that has not appeared yet — which is fine, and worth
knowing before anyone goes looking for the numbers in the Play Games app and concludes this is
broken.

### What the game sends

One event, `run_completed`, once per finished run — plus the console's predefined
`progressUpdate` carrying the personal best. A run is the only thing this game does, so every
countable fact is a fact about one: runs played is that event's count, time survived is a sum,
fruit whacked is a sum. The console allows 20 event names; using one is the right shape rather
than thrift.

| Property | Type | From |
| --- | --- | --- |
| `survived_ms` | INT64 | `result.millisSurvived` — the game's own currency |
| `survived_seconds` | DOUBLE | the same number, so a stat can carry the `SECOND` unit and render as a duration |
| `fruit_hit` | INT64 | `result.hits` |
| `fruit_missed` | INT64 | `result.strikes` — a strike *is* an escaped fruit, so 0–3 |
| `top_speed` | INT64 | `GameEngine.displaySpeed(...)`, the number the HUD showed |
| `ranked` | BOOL | whether the run counted for the leaderboard |

`quit` is deliberately absent: three strikes ends a run, so `fruit_missed == 3` already
identifies a lost one. A redundant property is a second thing to keep in step with the console.

### The one thing that cannot be back-filled

A run played while Play Games is signed out is **gone**. Unlike an achievement — a fact about
a personal best still sitting on the device — an event is a fact about a moment that has
passed, and the only way to send it later would be to invent a timestamp the server would then
have to trust.

The progression stat is what repairs that. `currentProgress` is the personal best in whole
seconds, read from the same DataStore value the achievements are back-filled from, and sent on
every launch once authentication resolves as well as after each run. So a player who has been
playing signed out still arrives with their best intact — which is the number they care about
— even though the individual runs behind it never reach the server.

### Files, and the tools that build them

| File | Uploaded at |
| --- | --- |
| `assets/game-stats/PlayerGameEvent.csv` | **Set up events** — on its own, not in the ZIP |
| `assets/game-stats/GameStats.zip` | **Set up stats** |

The ZIP is built from `RepetitiveStatsConfig.csv` (six stats), `ProgressionStatConfig.csv`
(one — Best Run, which heads the profile) and `icons/`:

```bash
python tools/generate_stat_icons.py --preview   # 512x512 icons, one per stat
python tools/build_game_stats_zip.py            # validate, then write GameStats.zip
python tools/build_game_stats_zip.py --check    # validate only
```

The builder enforces every documented rule before the console gets a chance to: name charsets
and lengths, reserved property names, the four case-sensitive types, aggregation and direction
enums, filter completeness, icons exactly 512×512 and under 1 MB, every icon referenced and
every reference resolved, and the ZIP's own file-count and size caps. That is worth having
because console validation is a slow round trip that reports one problem at a time — and
because **published stats cannot be deleted**, so a stat aggregating the wrong property is not
a mistake you get to take back.

The icons are deliberately *not* a family the way the achievement badges are: Play flags
visual duplicates, and seven variations on one ring would be exactly that. Each stat gets its
own silhouette and its own hue from the game's palette.

### Code and CSV must agree, or events vanish

Every uploaded event is validated against `PlayerGameEvent.csv` and one that does not match is
**discarded** — no exception, no failed `Task`, nothing in logcat from our side. A renamed
property or an `INT64` sent as a `DOUBLE` means a stat that reads zero for every player until
somebody eventually wonders why.

There is no runtime signal to test against, so `GameStatsTest` is the signal: it reads the
committed CSV and fails when it and `GameStats.SCHEMA` have drifted. `app/build.gradle.kts`
declares that CSV as an input of the test task — without it Gradle considers the task up to
date after the CSV changes and the guard passes by not looking.

Note also that all three client methods return `void`. Nothing about a rejected event is
observable in the app; the API response is only visible server-side.

### The column-order trap, learned from a rejected upload

**The console reads these CSVs positionally.** The header row is skipped, not consulted — so
a file whose columns are in the wrong order is not rejected as malformed, it is *misread*, and
the error names a symptom several columns away from the cause.

`ProgressionStatConfig.csv` is where this bites, because Google's own page contradicts itself.
Its "in the following order" line and its worked example both give **seven** columns:

```
Stat Id,Event Property Name,Stat Display Name,Stat Description,Icon File Name,Good value direction,Unit
```

The field table immediately below it documents an eighth, `Event Name`, between `Stat Id` and
`Event Property Name`. **That table is wrong.** Including the column shifts everything after it
by one, and the console then reads the description as the icon filename and reports:

> Brak pliku ikony – w archiwum ZIP brakuje pliku ikony „The longest you have ever survived in
> a single run…"

There is no `Event Name` column because a progression stat can only ever be built on the
predefined `progressUpdate` event. `RepetitiveStatsConfig.csv` *does* name its event and is
15 columns exactly as documented.

The same upload turned up a second asymmetry: **`Stat Description` is capped at 500 characters
in the repetitive file and 50 in the progression file.** Both limits are documented; they are
just easy to read as one rule.

`build_game_stats_zip.py` now checks each header against the expected order, checks per-row
cell counts, applies the two description limits separately, and flags an icon cell that does
not end in an image extension — which is the cheap canary for a shifted column, since prose in
that position is what the console chokes on. Both faults above were replayed against it and
both are now caught locally.

### There is no time unit

The second rejected upload was `Nieprawidłowa jednostka` on the two rows carrying
`Unit=SECOND`. The complete supported list, from the "Unit specification" table on
[/games/pgs/gamestats](https://developer.android.com/games/pgs/gamestats), is:

| Kind | Values |
| --- | --- |
| none | *(empty)*, `UNITLESS`, `NONE` |
| ratio | `%`, `PERCENTAGE`, `PERCENT` |
| distance | `CENTIMETER`, `METER`, `KILOMETER`, `FOOT`, `INCH`, `MILE`, `YARD`, `MILLIMETER`, `NANOMETER` |
| speed | `KILOMETER_PER_HOUR`, `METER_PER_SECOND`, `MILE_PER_HOUR` |

**That is all of it — there is no unit for time.** The prose beside the `Unit` column on the
same page offers "For example, KILOMETER, SECOND, PERCENTAGE, UNITLESS", and the worked
example row uses `seconds`. Neither is accepted. For a game scored in survival time this is
the single most inconvenient gap in the feature, and it is contradicted by Google's own page
twice over.

Two consequences, both now baked in:

- **Durations display as bare numbers**, so the stat names carry the unit instead: "Best Run
  (seconds)", "Time Survived (seconds)". Not elegant, but the alternative is a profile row
  reading `4210` with nothing to say what it counts.
- **`survived_seconds` is `INT64`, not `DOUBLE`.** With no unit to format it, a summed
  `DOUBLE` would show a fringe of decimals. Truncating each run before summing costs under a
  second per run and keeps the `>= 60` filter behind "Minute Runs" exactly right — 59.9
  seconds truncates to 59 and correctly is not a minute.

`build_game_stats_zip.py` carries the full unit set and rejects anything outside it, so this
particular round trip does not need making twice.

### Other choices worth knowing if an upload is rejected

- **Header rows** are included on all four CSVs. The events file is documented as having one;
  the stats files show one in their examples but never state it. Accepted in practice.
- **Every repetitive row carries all 15 columns**, empty where unused. Google's own example
  rows show 13 cells against a 15-column header. Empty-but-present is accepted.
- **Enums are upper case** (`INT64`, `SUM`, `INCREASING`, `SECOND`, `TRUE`/`FALSE`), matching
  the specification tables. Their examples mix cases freely; upper case is accepted.
- **`Is Competitive` is FALSE everywhere.** The flag exists for PvP leagues and social
  challenges and forces min/max hourly limits; this game uses neither, and guessing thresholds
  on an irreversible config is not worth it.
- **No `StatLocalizations.csv`.** The file is optional, and a locale has to already exist in
  the Play Console's translations before it can be referenced.

### Testing it

Per the docs, Game Stats testing needs an internal test track, a test account added to the
game project, and a Play Games profile created for that account. Expect the same signing
constraint as everything else here — see §2.

## 7. Testing achievements

```bash
./gradlew :app:installDebug
```

- Settings should show a **Play Games** section. Which row depends on whether the SDK's
  automatic sign-in landed; neither row appears until it has answered, by design.
- Play a run past 30 seconds. Play Games shows its own unlock toast; the app shows nothing,
  which is correct — the SDK owns that notification.
- Home's **Achievements** chip appears once authenticated *and* signed in to a Whaaack!
  account — signed out, that slot belongs to *Create account*. Settings always has it.
- `adb logcat -s PlayGames` names any id that was rejected.
- Then check the back-fill, which is the half that is easy to leave broken: sign out of Play
  Games, play a run past 30 seconds, sign back in from Settings. The milestone should unlock
  without replaying it — the stored personal best is replayed on every resume and on sign-in.

If nothing authenticates at all, it is §2 — not this section.

---

## 8. Accounts minted from Play Games

A Play Games leaderboard can only ever hold Play Games players, and ours holds Whaaack!
accounts — there is no API, client or server, that writes a Play Games score for an arbitrary
player id, and no way to put a Whaaack!-only player on a Play Games board. So the two
populations cannot be merged from the leaderboard end. They are merged from the *account*
end instead: a Play Games player is given a Whaaack! account made out of the identity they
already have.

### What happens, in order

1. Home shows the ranked pair of buttons to a Play Games player with no account
   (`UiState.canMintPlayGamesAccount`), and the auth screen shows **Continue with Play Games**
   to anyone Play Games has authenticated.
2. Tapping **Play ranked** raises `RankedInviteDialog` rather than starting a run. This is the
   consent moment, and the reason accounts are not minted at Play Games sign-in: ranked play
   publishes a name, and that should follow from something the player did on purpose. The auth
   screen has no dialog — pressing a button that says "Continue with Play Games" *is* that
   intent, and it reports failures in the error banner the other two providers use.
3. On accept, `PlayGamesManager.serverAuthCode` asks Play Games for a one-time server auth
   code. Both entry points share `WhaaackViewModel.adoptPlayGamesAccount` from here down, so
   they cannot drift.
4. `AuthRepository.signInWithPlayGames` POSTs it to the `play-games-auth` Edge Function.
5. The function exchanges the code with Google — which needs the web client *secret*, which is
   why this cannot happen in the app — reads the player id back from `games/v1/players/me`,
   finds or creates the account, and returns an ordinary GoTrue token response.
6. The run starts, ranked.

### Why the exchange is server-side

The app cannot be trusted to say who it is. The anon key ships inside the APK, so if the
client simply posted a player id, anybody could claim any id and own the board under someone
else's name. The auth code is worthless without the secret, and the player id is read out of
*Google's* answer rather than out of the request body. That is the whole security argument.

### What the account looks like

- **Address**: `pgs-<playerId>@pgs.whaaack.invalid` — a derived identifier, not a mailbox.
  `.invalid` is reserved by RFC 2606 and can never resolve, so nothing can be sent there and
  no password reset can be attempted. Settings therefore shows "Play Games account" rather
  than the address, and hides the Email and Password rows (`Player.hasPassword`).
- **Provider**: `play_games`, stamped onto `app_metadata` *after* creation, because GoTrue
  writes its own `provider` when it creates the email identity and would otherwise leave the
  account looking like an email signup.
- **Display name**: the gamer tag, run through the existing `handle_new_user()` trigger, which
  sanitises it to the `profiles` constraints and de-duplicates it against the board. No new
  SQL was needed; the trigger already did this for Google.
- **Deterministic**: the same player on a new device, or after a log out, resolves to the same
  account. Minting is idempotent.

### What it deliberately does not do

- **No back-fill.** `prefs.localBestMillis` is not posted on creation. It has no `hits` or
  `top_speed` behind it, so a back-fill would have to invent both to satisfy
  `scores_hits_plausible` and `scores_top_speed_plausible` — fabricating data specifically to
  pass our own fraud checks. A new account starts at "No ranked run yet" and their next run
  counts, which is seconds away now that there is no sign-up in between.
- **No linking.** A player with an email account *and* Play Games can end up with two
  identities and two rows on the board. Nothing merges them today. The minted account carries
  `pgs_player_id` in `app_metadata` precisely so a linking flow could be written later.

### Configuration

Blank is a supported state: with no `PGS_SERVER_CLIENT_ID`, `playGamesRankingAvailable` is
false, Home shows the signed-out pair, and ranked play needs a sign-up exactly as before. The
same reasoning as a blank achievement id — a fresh clone still builds and plays.

See `docs/SETUP.md` §9 for the Game server credential and the two function secrets.

### Testing it

1. Sign out of the Whaaack! account, stay signed in to Play Games.
2. Home should show **Play ranked** / **Play for fun**.
3. Tap Play ranked → the invitation appears → accept → the button reads "Setting up…" and a
   ranked run starts.
4. Settings should read "Play Games account" with no Email or Password row, and the
   leaderboard footer should read **No ranked run yet** until the run finishes.
5. Now the other door, which is the one a returning player uses: log out, tap **Sign in**, and
   press **Continue with Play Games**. It should land back on Home signed in as the *same*
   player — same display name, same rank — because the account is keyed to the player id
   rather than created afresh. A second row on the board means the deterministic address is
   not resolving, which is the failure worth catching here.
6. `supabase functions logs play-games-auth` names the failure on anything that goes wrong —
   `code_rejected` is almost always the wrong client id or a stale secret.

Failures are always survivable: the player is left on Home with the ordinary sign-in still
available, and nothing half-created is left behind, because the account is created by one
backend request or not at all.
