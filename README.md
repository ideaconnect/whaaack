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

## Layout

```
app/src/main/java/tech/idct/whaaack/
  game/     GameEngine, GameSurfaceView, GameRenderer, GameAssets, Fruit
  ui/       Compose screens + shared components
  data/     Supabase client, auth, leaderboard, settings
  audio/    SoundPool effects + MediaPlayer music
  ads/      AdMob rewarded interstitial, UMP consent
supabase/
  migrations/   schema, RLS, leaderboard functions
  config.toml   remote auth settings
docs/SETUP.md   credentials, Google OAuth, AdMob consent, release checklist
```

## Building

Put your Android SDK path and Supabase keys in `local.properties`
(see [docs/SETUP.md](docs/SETUP.md)), then:

```bash
./gradlew :app:assembleDebug
```

Debug builds use Google's test ad unit, so local runs never touch live inventory.

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
