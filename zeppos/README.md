# Whaaack! for Zepp OS

The wrist edition. Nine tiles instead of sixteen, one finger instead of two thumbs, and a
leaderboard of its own.

Zepp OS 3.0 (API level 3.0) · round screens · built with `zeus`

---

## Why it is a separate game

The phone game is a 4x4 board tapped with two thumbs on a screen the size of a paperback.
This is a 3x3 board tapped with one finger on a 46mm circle, where the finger doing the
tapping also hides the tile it is travelling to. The two produce completely different
numbers, so:

- **the boards are separate.** `zepp_scores` is its own table with its own RPCs
  (`zepp_leaderboard`, `zepp_my_standing`) and its own plausibility constraints, derived
  from this engine's curve. Nothing sorts a watch millisecond against a phone one.
- **the profile is shared.** One account, one display name, two scores. Signing in here
  uses the account created in the phone app.

## What is on the watch

```
page/home    Play · Leaderboard · the sound toggle · who is signed in
page/game    the run, and the result it ends on
page/board   the Zepp board, all-time or this week, and your standing
```

Three physical programs, not one — which is the thing about Zepp OS worth internalising
before reading the code:

| where | what runs there | what it may do |
| --- | --- | --- |
| `page/`, `app.js` | the watch app | draw, take taps, keep a local best |
| `app-side/` | a service inside the Zepp app on the phone | **all** networking; holds the session |
| `setting/` | a page rendered by the Zepp app | sign in, or create an account |

A watch has no internet of its own, so every Supabase call goes out through the phone. That
is a constraint, but it puts the credentials in the right place: the watch asks for a
leaderboard and gets rows back, and a token never crosses to the wrist.

The three share no runtime and can only reach each other through message method names and
`settingsStorage` keys — plain strings that fail silently when they disagree — so all of
them live in [`shared/protocol.js`](shared/protocol.js).

## Signing in, and signing up

Login and password (and changing or resetting one), on the phone, under **Zepp → Profile → (your device) → Whaaack! →
Settings**. Both halves are there: sign in with an existing account, or create one — the
same account the phone game uses, so a player who starts on the watch can carry on there
and vice versa. No on-glass keyboard: typing a password on a watch with a character picker
is a chore people abandon.

The signup form asks for a display name as well, because that is the name that appears on
the board, and getting it from the player beats letting the signup trigger derive one from
the email address. It is validated before the request goes out — the rules in
[`shared/credentials.js`](shared/credentials.js) are `display_name_shape` and
`display_name_length` transcribed, plus the password rules from `supabase/config.toml`.
Left to the database, `-nick-` comes back as *"new row for relation profiles violates check
constraint display_name_shape"*, which names our schema and tells the player nothing they
can act on.

The project has **email confirmation on**, so signing up does not sign anybody in: GoTrue
creates the user, mails a link, and the page says so. Confirm the address, come back, sign
in. The one case that needs care is an address that already has a confirmed account — GoTrue
deliberately answers that with a 200 and an empty `identities` array rather than an error,
because saying otherwise would confirm the address exists to anyone who asks. Read naively
it is indistinguishable from success, and the player would be told to watch an inbox nothing
was ever going to arrive in.

### Where an emailed link lands

Not in the Android app. `site_url` is `whaaack://auth`, a deep link only the phone game can
answer, and that is the right destination *for the phone game* — the app is by definition
installed. It is the wrong one here. An account can be created, confirmed and recovered
start to finish from this settings page by somebody who has never installed the Android
version and has no reason to, and handing them a link their phone cannot open is a dead end
at the exact moment they are trying to get in.

So both of the watch's mails ask for [`website/auth/`](../website/auth/index.html) instead —
a page that needs nothing installed. Confirmation is the easy half: the answer is a
sentence. Recovery is the half that needs somewhere to *type* a new password, so the page
carries a form; the link arrives already verified by GoTrue, with a real short-lived session
in the fragment, and the form spends it on one `PUT /auth/v1/user`.

Which flow a link belongs to is decided per request, by the `redirect_to` on the call that
sends the mail, so the phone game is untouched — its own resets still open the app — and
**no project setting changes**: `https://idct.tech/whaaack/auth` is already on
`additional_redirect_urls`.

The exact spelling of that string matters more than it looks. GoTrue matches `redirect_to`
against the allow list and *silently substitutes `site_url`* when it does not match — no
error, no warning, the mail goes out and the only symptom is a player tapping a link that
opens nothing. A trailing slash would be enough to do it. That is why
`tools/check-backend.mjs` asserts the outgoing URL rather than only the reply: it is the
only place the mistake is visible.

The page is told what happened in the URL *fragment* (`#access_token=…&type=signup`, or
`#error=…&error_code=otp_expired`), which never reaches a server — so only its script can
read it, and the markup starts out claiming neither outcome, with a `<noscript>` that says
so honestly. The tokens are read and discarded: confirming an address needs no session, and
the fragment is stripped out of the address bar as soon as it has been read so it is not
left in history or handed onward in a `Referer`.

The settings page never calls Supabase itself. It writes `{action, email, password, name?}`
into `settingsStorage`; the side service is woken by that change, spends the password,
deletes the request, and writes back a status the page renders. So the password is never at
rest, and the page always shows what the service actually managed to do.

One thing worth saying out loud: **the Zepp Settings API has no masked input**, so the
password is legible while it is being typed. It is cleared from the form the moment the
button is pressed. There is no way around that from inside the API today.

## Passwords

Two things the page can do to a password, and they live on opposite sides of being signed
in.

**Changing it** sits under the account card, one field and a button. No current-password
box: the project runs with `secure_password_change = false` — `supabase/config.toml`
explains at length why, and the phone game's `AuthRepository.updatePassword` relies on the
same thing — so GoTrue accepts a bare `PUT /auth/v1/user` on any live session, and asking
for a password nothing checks would be theatre. On a page with no masked input it would be
theatre performed in the clear. There is no type-it-twice field either: that guards against
a typo you cannot see, and this one is legible the whole time.

The status stays `signed_in` throughout, which is why it carries `notice`, `problem` and
`busy` alongside the name. The page picks its whole layout off `state`, so reporting
progress as `working` or a refusal as `error` — the way sign-in does — would replace the
account card with a sign-in form over a session that never stopped being valid, and the
player would think that trying to change their password had signed them out.

**Resetting it** sits under the sign-in button and uses the address already in the form,
because somebody who wants a reset has almost always just failed to sign in with it. It
posts to `/auth/v1/recover?redirect_to=https://idct.tech/whaaack/auth`, so the link lands on
the form described above rather than on the phone game's deep link. (`AuthRepository`
`.sendPasswordReset` still asks for `whaaack://auth`, which is right for a caller that *is*
the app.)

The wording of what comes back is deliberate. GoTrue answers a recovery request for an
address it has never seen exactly as it answers one for an address it knows, which is what
stops this form being a way to ask whether somebody has an account — so the card says a
link is on its way *if that address has an account*, and never claims a mail was sent.

> **What is still untested.** The form on `website/auth/` has been exercised against the
> real project with a dead token — `node tools/check-auth-page.mjs` runs the page's own
> script against a stub DOM and checks that every fragment shape picks the right branch, and
> that a token GoTrue rejects is reported as an expired link rather than as a mystery. What
> that cannot cover is the one path that needs a real recovery mail to a real account:
> a live token being *accepted*. The request it makes is the same `PUT /auth/v1/user` the
> settings page's own change-password already makes and `check-backend.mjs` already proves
> against a live session, so the untested part is the token's provenance rather than the
> call — but it is untested, and worth ten minutes with a throwaway account before anyone
> relies on it.

## The rules

Same shape as the phone game, retuned around one finger:

- two fruit at once, a third at level 4, a fourth at 10, a fifth at 18 — five of nine tiles
  at the top, which is what makes runs end on their own;
- a fruit that is not whacked before its life expires costs a strike, three strikes end it;
- a strike buys the rest of the board a beat, so one lapse costs one strike;
- the score is milliseconds survived;
- both pace tracks ramp linearly to a knee at level ~13 and then decay geometrically toward
  a floor, so the pace never stops tightening and never reaches zero.

The whole simulation is in [`shared/engine.js`](shared/engine.js) and imports nothing —
no `@zos/*`, no widget, no timer. Time arrives as an argument. That is what lets the curve
be checked without a watch:

```bash
node tools/simulate.mjs 40
```

```
grade    median      p10       p90      max   hits/s  capped
casual     36.7s   33.3s   39.1s   40.4s    1.96       0
decent     59.8s   57.8s   64.8s   68.7s    2.92       0
good      105.7s   96.4s  111.2s  113.6s    4.09       0
expert    244.4s  241.7s  250.6s  254.0s    5.65       0
```

Four grades of synthetic player, separated by better than six to one, each landing within a
few percent of the tap rate it is supposed to hold, and none of them reaching the 15-minute
cap. Those are the properties the curve has to have: a board that ranks skill rather than
patience, and runs that terminate.

Alongside it, `node tools/check-engine.mjs` asserts the things a play-through would not
catch — chiefly what happens when the clock steps, which on a watch it does: `Date.now()`
is resynced from the phone and there is no monotonic counter to use instead.

## How it draws

Widgets, not a canvas — Zepp OS has no equivalent of the phone game's render thread, and it
does not need one for nine tiles.

Every widget is created once in `build` and then only ever has a property rewritten, and
only when the value it shows has actually changed. A quiet frame at 25fps costs nine
comparisons and no draw calls. A `createWidget`/`deleteWidget` per fruit would churn the
heap on the device that has the least of it.

Touch is nine `BUTTON` widgets over the board, each drawing a 4px transparent bitmap so
nothing of it shows. The tidier design — one listener on the background, mapping
coordinates to a tile — simply does not work: `addEventListener` on a `FILL_RECT` under the
board never fires. That cost a while to find, because the symptom is a run that plays
perfectly and scores zero hits. The buttons are a tile *pitch* wide rather than a tile
wide, so they tile the grid with no dead gutter: a tap between two tiles goes to the nearer
one instead of to nothing, which at five fruit a second is the difference between a near
miss and a strike.

Layout is [`shared/layout.js`](shared/layout.js), in design pixels for a 480px screen, with
`px()` rescaling for anything else. The constraint that decides everything is that a round
screen has no corners: a square inscribed in a 480px circle is only 339px on a side, so the
grid is centred slightly low and every corner is checked against the rim rather than
eyeballed.

## Black, and what it costs

Every watch this targets has an AMOLED panel, where a black pixel is an *unlit* pixel. The
phone game's orchard-night purple would light all 230,000 of them for the whole of a run
and buy nothing, so the ground here is `#000000` and the colour is spent where it earns
something — the fruit, the splats, the accent.

That inverts the board. Against purple an empty tile could be a *hole*, darker than its
surroundings; against black there is nothing darker, so the tile states run the other way:
an empty tile is the faintest thing that still reads as a target, an occupied one lifts
into violet, and one about to expire goes amber. The dim creams are pre-composited against
black rather than against purple, because Zepp OS fills are opaque and there is no
per-widget alpha on a fill.

**How faint "faintest" can be is set by the dimmest the screen gets, not by how it looks on
a desk.** The first values here were picked on a simulator pinned at full brightness, and
at a tenth of that the board went to black and disappeared out from under the fruit. Screen
brightness scales light, so a colour has to survive being multiplied by 0.1 and still be
told apart from the ground — which puts the floor a good deal higher than it looks like it
needs to be. The tiles, the strike pips and the menu buttons were all raised for it. The
check is cheap and worth repeating after any palette change: capture a frame, convert to
linear light, multiply, convert back, and look at it at 30% and 10%.

There is no hit state left. A whacked tile used to flash green for 220ms, and the splat now
says the same thing in the fruit's own colour and says it for three times as long.

## Splats

The phone keeps a splat as an `ALPHA_8` mask and colours it at draw time with a gradient
shader, picking one of thirty-six masks and a random angle per hit. A watch has no shader
and no canvas, only bitmap widgets — so both the colour and the shape have to be baked, and
every pair that could appear on screen has to exist as a file.

That turns variety into a bundle-size budget. The thirty-six masks are dealt out one each
across twelve fruits by three variants, so every sprite is a silhouette no other sprite
uses, at a twelfth of what the full cross product would weigh. The palette is read out of
the phone's `Fruits.kt` rather than copied, so the two cannot drift apart without the
generator failing loudly.

On the board it is one `IMG` per tile, created up front like everything else, sitting above
the tiles and below the fruit — which is what lets a fresh fruit land on the splat of the
one before it, and a splat spill across the gutter onto its neighbours without covering
what is growing there. It holds full opacity for the first 45% of its 700ms and then fades
on the alpha property. Seven hundred rather than the phone's 1100 because a watch is a
third of the size and the fruit arrive about as fast: at the top of the curve, the phone's
number leaves every tile permanently smeared and the thing that marks a hit stops marking
anything.

## What a run is worth

The result screen ends on a row of badges: one for each of 30, 60, 90 and 120 seconds
survived, and a trophy for beating the watch's own previous best.

The four are not new art. They are the phone game's Play Games achievement icons, which
already exist for exactly those four thresholds — cut to their inscribed circle and brought
down to 50px by `tools/generate_zepp_assets.py`. What survives that reduction is the ring
around each, which is a clock: a quarter lit at thirty seconds, closed at a hundred and
twenty. That is what tells the tiers apart at a glance, and it is why every tier cleared is
shown rather than only the highest — the four read as a ladder, and a ladder with one rung
showing is just a picture. The trophy is drawn to match, because Play Games has no
"beat your own record" achievement to borrow from: only the watch keeps a previous best.

The trophy needs a best to beat, not merely to set. On a watch that has never finished a
run the previous best is zero, every score is a record, and a trophy for clearing nothing
would be the first thing a new player was ever awarded and the last time it meant anything.

Whether those four are the right four for *this* curve is a fair question, since they were
chosen for a 4x4 board tapped with two thumbs. `node tools/simulate.mjs` now answers it:

```
badges earned, share of runs
grade        30s     60s     90s    120s
casual      100%      0%      0%      0%
decent      100%     50%      0%      0%
good        100%    100%    100%      0%
expert      100%    100%    100%    100%
```

A staircase, which is the property they need — the sixty is a coin flip for a decent
player, which is the one worth chasing, and nobody but an expert closes the ring. A badge
everybody earns is decoration; one nobody earns is a bug nobody will ever report.

Making room cost the raw millisecond count, which said the same thing as the seconds above
it in a unit nobody compares runs in; the hits it shared a line with moved up beside the
best. The bottom of the screen is set by the glass rather than by taste: Play again ends at
y=412, where its far corner is 235px from the centre and the rim is at 240.

## Sound

`@zos/media` arrived in Zepp OS 3.0 and takes MP3. It is a *media player*, not a sound
bank: one source at a time, `setSource` then an asynchronous `prepare()` before anything
will `start()`. So the watch gets two players — one per sound, because switching a player
between them means re-preparing and a run whacks fruit five times a second — and one splat
sound rather than the phone's nine, with the variety moved into the sprites instead.

A player is not guaranteed, and there are two different ways to be refused. A watch with no
speaker throws out of `create`, and there is nothing more to try. A watch that has audio
but will only hand out one player at a time returns `undefined` from the second `create` —
no throw, no message — which must not be read as "this watch has no sound", because the
first player it gave out is working perfectly. The simulator does exactly this, which is
how it was found.

So the splat is claimed **before** the music. If a watch only has one player, the sound
worth spending it on is the one that answers a tap: music is atmosphere and its absence is
a quiet game, where a hit that makes no sound is a hit that feels like it missed.

There is no loop flag in the API either, so the music loops by starting itself again on
`COMPLETE` — a hard cut, with whatever gap a restart costs. The file is therefore cut to a
whole number of musical phrases and its seam crossfaded closed
([`../tools/generate_zepp_audio.py`](../tools/generate_zepp_audio.py)), so the wrap lands
somewhere the music was going to breathe anyway.

All of it is behind one toggle on the home screen, kept in the watch's own local storage
rather than in the phone's settings page — the moment you want the music off is usually the
moment somebody walked in, and a setting that needed the Zepp app would be no use then.

**Two ways to hear nothing, and both are guarded now.**

The `PREPARE` event is documented twice and the two disagree: one reference hands the
callback an object and tests `result.isReady`, and the platform's own current example tests
the argument itself for truth and calls `start()` on that. A guard written for only the
first reads a bare `true` as a failure and never starts anything — total silence, on every
sound, with nothing in the log but a line claiming the file would not prepare. That was the
first version of this file, and it is why the first build to reach a real watch made no
noise at all. Anything truthy now counts, unless it is an object that explicitly says
otherwise.

And the event may simply never come. The simulator never sends it, so a run there used to
sit at "loaded but not ready" for ever. A watch that loads the file and stays quiet for
that reason is indistinguishable from one that cannot play at all, so after
`PREPARE_GRACE_MS` the sound is armed and played anyway — of the two possible mistakes,
starting a sound that was not quite ready is much the smaller.

**Still unverified on hardware.** The simulator has no audio device, so what can be checked
there is the *path*: the splat player is created, the music player is correctly refused, the
fallback arms it, and `start()` is accepted without throwing. Whether a speaker then moves
is not a question a simulator can answer. When a watch will not play, the home screen's
toggle says **No sound** rather than going on offering something the watch has already
refused — the outcome is remembered across pages in local storage, because it is only
discoverable during a run.

## Building it

```bash
python ../tools/sync_zepp_secrets.py   # writes shared/secrets.js from ../local.properties
npm install
zeus build                             # dist/*.zab, every round resolution
zeus dev -t "Amazfit Balance"          # or any target, onto the simulator
zeus preview                           # onto a paired watch
```

`shared/secrets.js` is generated and not committed, the same way `local.properties` is not
— the anon key is a public client key and row-level security is the real boundary, but
keeping it out of git means rotating it does not mean rewriting history. See
[`shared/secrets.example.js`](shared/secrets.example.js).

The rule that ignores `shared/secrets.js` is in the **repository root** `.gitignore`, not in this directory's,
and that is not tidiness: `zeus dev` rewrites `zeppos/.gitignore` with its own defaults on
every run. Silently, and on a *watch* run — so it happens minutes after anyone last looked at
the file. Anything you add here will be gone by the next build; put it in the root.

Bitmaps and sounds both come from what the phone game already ships:

```bash
python ../tools/generate_zepp_assets.py --preview   # fruit, 36 splats, the icon
python ../tools/generate_zepp_audio.py              # music.mp3, splat.mp3 (needs ffmpeg)
```

## Checking the backend

The client is [`shared/supabase.js`](shared/supabase.js), and it imports nothing from Zepp
OS — it is handed a `fetch` and a key/value store. That is what makes the sign-in path
testable at all, because a settings page accepts input from a person and from nothing else:
no synthetic keystroke reaches it, on a simulator or on a phone.

```bash
node tools/check-backend.mjs                       # board, refusals, bad password, signup rules
node tools/check-backend.mjs you@example.com pass  # the signed-in path, end to end
```

The second form posts one real (tiny) score, because "does a finished run reach the board"
is the question worth answering, and tries to sign the same address up again, because that
is the case GoTrue makes look like success. It also sets the account's password to the one
it was given — the password it already has. That is the only way to prove an authenticated
`PUT /auth/v1/user` really reaches GoTrue without leaving the account with a password a
script chose: GoTrue either accepts the no-op or refuses it as `same_password`, and both
answers prove the same thing.

The first form fires one real `recover` at `nobody@example.invalid`. It mails nobody, there
being no such account, but it exercises the request, the redirect and the client's reading
of the reply — the path that would otherwise only ever run in front of somebody who had
just forgotten their password.

The one screen no automated check reaches is the "check your inbox" card, because producing
it means creating a real account and sending a real mail. It is built from the same
primitives as the rest of the page, and the branch that produces it is covered.

## The footer

Under every state of the settings page — signed out, awaiting a confirmation mail, signed
in — there is a row asking for a coffee, a row pointing at the Android game, and the IDCT
mark over a copyright line. Under *every* state deliberately: this is the only screen the
Zepp edition has that is big enough to read, and all three of those are states a player can
sit in for days, so a footer on one of them is a footer most people never see.

The three pictures are base64 in
[`setting/assets.js`](setting/assets.js), which is generated:

```bash
python ../tools/generate_zepp_settings_assets.py --preview
```

They have to be inlined. The settings page is not part of the watch package and has no
assets directory of its own — its `Image` takes a URL or a base64 string and nothing else —
and a URL would leave the page blank whenever the phone was offline, which is a state a
watch companion app is in fairly often.

The IDCT mark had no raster in this repository, only the Android `VectorDrawable`. That
turned out not to matter: the mark is flat geometry and every one of its twelve paths uses
only `m`, `l`, `h`, `v` and `z`, so it rasterises exactly by filling polygons, with none of
the flattening error a Bezier would bring. It is filled by the non-zero winding rule, which
is what `VectorDrawable` defaults to and what this mark needs — the eyes, the acorn and the
whisker are holes wound against the body.

Two things about laying it out, both found by rendering the real page rather than by
reading the docs. `Text` writes `text-align: left` into its own style, so centring has to
be said on the text and not on the block around it. And `Link` really does emit an anchor
with `target="_blank"`, so the rows open a browser rather than doing nothing.

## Devices

API level 3.0 with a round 480px design width, which covers everything from Amazfit Balance
(the reason for 3.0 — it tops out at API 3.7) up through the T-Rex 3, T-Rex 3 Pro, T-Rex
Ultra 2, Balance 2/3 and Balance Ultra. `zeus build` emits a zpk per round resolution and
Zepp OS rescales both the coordinates and the bitmaps, so the 466px 44mm T-Rex 3 Pro needs
no separate layout.

Sound needs a speaker, which not every one of them has, and the game is written to be
played silently on the ones that do not.

One trap worth knowing when running against the simulator: a build is tied to the *device*
it was made for, and the simulator refuses — silently, in v2.1.2, because its own notice
helper throws — to launch a package built for a different one. `zeus dev -t "Amazfit T-Rex
3 Pro (44mm)"` installs an app that a 48mm emulator will list and never start.

## The backend

[`supabase/migrations/20260825000000_zepp_edition.sql`](../supabase/migrations/20260825000000_zepp_edition.sql)
adds the table, the two RPCs, the RLS policies, the column grants and the flood trigger. It
touches nothing the phone game uses. Apply it with `supabase db push` before the board can
answer anything.
