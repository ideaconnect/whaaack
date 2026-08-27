# Zepp console listing

Everything the developer console asks for, kept next to the app it describes so that a
change to the app and a change to what we say about it are one commit rather than two. The
last version of this lived in a scratch file and went stale the moment the sound came out:
it went on promising a sound setting the watch no longer had, in the text a reviewer reads.

**If you change what the app stores, sends, or asks permission for, change this file in the
same commit**, and check [`../website/privacy/index.html`](../website/privacy/index.html)
too — the policy has its own watch section and the console links to it.

Facts as of the 1.0.0 submission, all read from the source rather than remembered:

| | |
|---|---|
| appId | `1124865` (`app.json`) |
| version | 1.0.0, code 1 |
| watch permissions | `data:os.device.info`, `device:os.local_storage` — and nothing else |
| bundled libraries | `@zeppos/zml` only (Zepp's own; `@zeppos/device-types` is dev-only types) |
| stored on the watch | the best time. That is the whole list |
| audio | none at all |

---

## App name

```
Whaaack!
```

## App introduction (max 40 characters)

```
Whack the fruit before it gets away.
```

## App details (max 600 characters)

```
Nine tiles. Fruit surfaces, and you have a moment to whack it before it gets away. Miss three and the run ends - your score is how long you lasted.

It tightens the whole way: fruit arrives faster, stays for less time, and comes two at a time to begin with and five at the top. A whack taps the vibration motor and an escape gives a heavier one.

Sign in from the Zepp app's settings to rank on the Whaaack! watch leaderboard - a board of its own, since a 3x3 grid tapped with one finger is not the phone's 4x4, but the same account. Or just play: no account needed.

Feedback: whaaack@idct.tech
```

## App icon

`../assets/zepp/app-icon-240.png` — 240x240, circular, transparent outside the circle.

## Screenshots

360x360 PNG with a transparent background, three or more, built by
[`../tools/make_zepp_screenshots.py`](../tools/make_zepp_screenshots.py) from raw simulator
window captures. The console's rule differs by shape and the tool does both:

* **square** — fitted by *height*, so 390x450 becomes 312x360 with 24px of transparency
  down each side and none top or bottom. Nothing is cropped.
* **round** — fills the box, with the corners punched out to transparent, because the
  corners are not display.

| | |
|---|---|
| square | `../assets/zepp/screenshots/square/` — play, result, menu, leaderboard |
| round | not built yet |

Every one is a real run. The result shot is a genuine 58.0s with 300 hits, which is what
earns the thirty-second badge and the trophy beside it; none of it is staged, and none of
it should be. A screenshot of a score the game did not produce is a promise it cannot keep.

---

## Call Permission

Tick **Connect to the network**. Nothing else.

Not "None": the companion service inside the Zepp app makes HTTPS calls to our backend for
sign-in and for the leaderboard. The watch app itself holds no network permission — every
request is made phone-side — but the installation package contains that service, so the
honest answer is that the package connects.

Leave the rest clear, and each for a reason worth being able to state:

* **Heart Rate** — never read. The app requests no health, activity or sleep data.
* **Positioning** — never read.
* **Run in background** — nothing runs when the app is closed. The companion service answers
  the watch while the game is open and does nothing otherwise.
* **Others** — the two declared permissions are the screen size (`data:os.device.info`, to
  lay out a round or square board) and local storage (`device:os.local_storage`, for the
  best time). Neither is a call permission.

## Whether the installation package includes SDK

**No.**

The only bundled dependency is `@zeppos/zml`, Zepp's own mini-library for talking between
the watch and the companion service. `@zeppos/device-types` is TypeScript types, used at
build time and not shipped. No analytics, advertising, crash-reporting or tracking SDK of
any kind is present.

## Full music playback

**No.**

The app plays no audio whatsoever — there is no media player in it and no sound file in the
package. Feedback is the vibration motor only.

---

## Privacy Statement

```
Whaaack! is playable with no account and collects nothing about you unless you choose to sign in.

On the watch, the app stores one thing: your best time. Nothing else is kept on the device.

If you sign in from the Whaaack! settings page in the Zepp app, the following is sent to our backend, which runs on Supabase:

- your email address and password, to sign in or create an account;
- the display name you choose, which is shown on the leaderboard;
- for each finished run, how long it lasted and how many fruit you hit, so it can be ranked.

That is the whole list. Your sign-in tokens are held by the companion service inside the Zepp app on your phone and never reach the watch.

Opening the leaderboard fetches the public rankings - display names and times - whether or not you are signed in. That request asks for data rather than sending any about you.

The app reads your watch's screen size so it can lay the board out correctly. It does not read or collect health, heart-rate, activity, sleep, location or any other sensor data. The vibration motor is the only hardware it drives, and driving it sends nothing. There is no advertising, no analytics and no tracking of any kind.

You can sign out at any time from the same settings page, which removes the tokens from your phone. To delete your account and everything attached to it, including your watch scores, see https://idct.tech/whaaack/delete-account/

Questions or requests: whaaack@idct.tech
Full privacy policy: https://idct.tech/whaaack/privacy/
```

## Features Descriptions

```
Whaaack! is a single-player reaction game for the watch. A three-by-three board of tiles; fruit appears on them and disappears after a moment, and you tap the tiles that have fruit on them. Three escapes end the run, and your score is how long you survived. A whack answers with a short tap of the vibration motor and an escape with a heavier one, so the run can be felt as well as watched.

An account is optional and the game is complete without one. Signing in is done on the Whaaack! settings page inside the Zepp app on the phone - the watch has no keyboard - and it puts finished runs on a leaderboard of their own, separate from the Android version's, because a 3x3 board tapped with one finger does not produce comparable times to a 4x4 tapped with two thumbs.

Why the package needs network access: the companion service inside the Zepp app makes HTTPS requests to our backend to sign in, to submit a finished run, and to read the public leaderboard. The watch application itself declares no network permission; it asks the service and receives rows back. Sign-in tokens stay on the phone and never reach the watch.

The two permissions the watch app declares are the screen size, so the board can be laid out for a round or a square display, and local storage, which holds the best time. The app reads no health, heart-rate, activity or location data, runs nothing in the background, plays no audio, and contains no advertising, analytics or third-party SDK.
```
