# Whaaack!

Whack-a-mole, except the moles are fruit and they are getting away.

Two fruits are on the board at any moment. Miss one and it escapes — three escapes end the
run. Your score is how many **milliseconds** you survived, and the orchard speeds up the
longer you last.

Android · Kotlin · Jetpack Compose · Supabase · AdMob

---

## How it renders

The brief asked for hardware-accelerated rendering that never draws on the CPU on the main
thread. The game therefore does not use Compose for gameplay at all:

- **`GameSurfaceView`** owns a dedicated `whaaack-render` thread. Each frame is composed
  through `SurfaceHolder.lockHardwareCanvas()`, which returns a GPU-backed Canvas. The main
  thread's only job is forwarding touch coordinates, which it hands over through a
  lock-free queue.
- **Everything in a run is drawn on that thread** — parallax orchard, board, fruit, splats,
  the millisecond counter, the speed bar, strike flash, countdown and the game-over burst.
  Nothing about a live run causes a recomposition.
- **`GameEngine`** is pure simulation with no Android types, stepped from the render thread.
- Menus are Compose (hardware-accelerated, and static between taps).

The subtlety worth knowing is shutdown. Destroying a surface while a buffer is still
dequeued segfaults inside EGL, and the framework tears the buffer queue down the moment
`surfaceDestroyed` returns. Three things keep that from happening:

- the run does not announce "game over" until the outro burst has finished playing, so the
  UI never swaps screens — and never disposes the surface — mid-animation;
- `stopRendering()` joins the render thread with no timeout, and is called from
  `onDetachedFromWindow` as well as `surfaceDestroyed`;
- it then waits a few vsyncs, because `unlockCanvasAndPost` hands the frame to HWUI's
  shared render thread, which finishes it asynchronously — joining our own thread alone
  does not mean the last buffer has been returned.

Sprites are decoded once, off the main thread. Splat silhouettes are kept as `ALPHA_8`
masks — a quarter of the memory of ARGB, and drawing them takes colour from the `Paint`,
which is exactly the per-fruit gradient tint the design calls for.

## The icon

Every icon the app ships is generated from one master, `assets/icon/icon-2.png`:

```bash
python tools/generate_launcher_icons.py --preview
```

Each surface gets its own framing rather than one bitmap reused everywhere, because the
surfaces disagree about what they will do to it:

- **Adaptive foreground** — the artwork's *minimum enclosing circle* is pinned to 71dp on
  the 108dp canvas. Launcher masks are inscribed in the middle 72dp, so a circle that size
  cannot be clipped by a circle, squircle, teardrop or rounded-square mask. (The previous
  icon was sized to its bounding box, which is why its corners were cut off.)
- **Monochrome** — its own bitmap, not the foreground. Android 13+ themed icons keep only
  the alpha channel, so colour artwork renders as one featureless blob; this layer knocks
  the flesh out as negative space and adds a gap ring, so the slice still reads.
- **Legacy 48dp** — nothing masks these, so the background is baked in and the artwork is
  inset to leave its own margin.
- **In-app logo** (`R.drawable.logo_whaaack`, the About screen) — no mask and no
  background, so the *bounding box* is fitted instead and the art fills the space.
- **`assets/icon/play-store-512.png`** — Google re-masks it, so content stays inside 80%.

`--preview` writes `tools/icon-preview.png`: every mask shape at every launcher size, plus
the themed icon in light and dark. Worth a look before shipping an icon change.

The gold crown badge on the ad-break dialog has its own master and its own one-job script:

```bash
python tools/generate_pro_badge.py --preview
```

`assets/icon/pro_remove_ads_crown_only.png` is painted on an *opaque* dark ground, which
would show as a square tile in the middle of a translucent panel. The script solves the
composite that produced it — every lit pixel is one flat gold at some opacity — and puts the
crown *and its glow* back on transparency, rather than cutting it out at a threshold and
throwing the glow away.

## Layout

```
app/src/main/java/tech/idct/whaaack/
  game/     GameEngine, GameSurfaceView, GameRenderer, GameAssets, Fruit
  ui/       Compose screens + shared components
  data/     Supabase client, auth, leaderboard, settings
  audio/    SoundPool effects + MediaPlayer music
  ads/      AdMob interstitial, UMP consent
  billing/  the one-time ad-free unlock (Play Billing)
supabase/
  migrations/   schema, RLS, leaderboard functions
  config.toml   remote auth settings
website/        the project site, published to idct.tech/whaaack/
tools/          launcher-icon generator (Pillow + numpy)
docs/SETUP.md   credentials, Google OAuth, AdMob consent, release checklist
docs/GO-TO-PRODUCTION-TECHNICAL.md      what is left in the repo before release
docs/GO-TO-PRODUCTION-NON-TECHNICAL.md  what is left in a console or a contract
docs/REVIEW-MONEY-AND-LOGIN.md          the purchase and account paths, reviewed
```

## The website

[idct.tech/whaaack/](https://idct.tech/whaaack/) — the landing page plus the privacy policy,
terms, account-deletion page and contact form. Plain static HTML, no framework and no build
step, in [website/](website/).

Everything the site shows of the game is **the game**, not a mockup: the two screens floating
in the hero and the four in the fan are frames from one real run.

```bash
python tools/capture_gameplay.py
```

It drives the run itself — reads the screen, finds the fullest hole, taps it — because a
capture of an idle board is worse than none, and writes a contact sheet so the good moments
can be picked by eye. Re-run it whenever the game's look changes. The script's header lists
the three traps worth knowing first, the main one being that the board is a fixed 4x4 grid,
so finding fruit is sixteen means rather than blob detection — the version that labelled
connected components took 23 seconds a frame against fruit that live for two.

The only third-party thing on the page is nothing at all: no fonts, no icon CDN, no
analytics. The Google Play mark is an inline SVG for that reason — every host the site
touches is a recipient of the visitor's data and has to be named in the privacy policy,
which is why the deploy workflow fails the build on an undeclared one.

It is a GitHub Pages **project** page served under the org's apex domain, which is the one
thing worth knowing before editing it: the site must never ship a `CNAME` (that would claim
`idct.tech` at its root and collide with the org page that owns it), and `app-ads.txt` cannot
live here either — AdMob reads it from the domain root, so it belongs to the apex repo.

[.github/workflows/pages.yml](.github/workflows/pages.yml) deploys it and refuses to publish a
tree that would break quietly: the legal pages must exist (Play requires a reachable policy URL
and the app deep-links it), each page's canonical must match its own path, the sitemap must list
exactly those canonicals, every in-page anchor must resolve, and no cleartext URL or undeclared
third-party host may appear — that last one exists because a script tag is a recipient of the
visitor's data and has to be named in the privacy policy.

## Building

Put your Android SDK path and Supabase keys in `local.properties`
(see [docs/SETUP.md](docs/SETUP.md)), then:

```bash
./gradlew :app:assembleDebug
```

Debug builds use Google's test ad unit, so local runs never touch live inventory.

Play refuses to price an in-app product for a build it did not install, so on a sideloaded
debug APK there is nothing to sell and every ad-free upsell hides itself — correctly, but
unhelpfully if you are working on those screens. Set `REMOVE_ADS_PLACEHOLDER_PRICE` in
`local.properties` to stand a price in; the purchase itself still goes to Play and still gets
declined. See [docs/SETUP.md](docs/SETUP.md) §5.

## Status

Working and verified on device: gameplay, scoring, audio, GDPR consent, email sign-up and
sign-in, ranked score submission, all-time and weekly leaderboards, own-standing lookup,
settings, and account deletion (with cascade).

Two things need credentials that only you can create — both are documented with exact
steps in [docs/SETUP.md](docs/SETUP.md):

- **SMTP** — auth emails do not send yet. The Resend key and `idct.tech` domain are both
  verified good; the Supabase CLI is not transmitting the password. Paste it in the
  dashboard.
- **Google sign-in** — implemented and wired, disabled until the OAuth clients exist.

## Credits

Fruit sprites by [JennPixel](https://jennpixel.itch.io/fruits-pack-12) ·
splats by [Kenney](https://www.kenney.nl) ·
backgrounds from [CraftPix](https://craftpix.net/file-licenses/) ·
music by [DavidKBD](https://davidkbd.itch.io/tropical-dreams-spring-and-summer-music-pack).

No moles were involved.
