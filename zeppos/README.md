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
page/home    Play · Leaderboard · who is signed in
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

Login and password, on the phone, under **Zepp → Profile → (your device) → Whaaack! →
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

The settings page never calls Supabase itself. It writes `{action, email, password, name?}`
into `settingsStorage`; the side service is woken by that change, spends the password,
deletes the request, and writes back a status the page renders. So the password is never at
rest, and the page always shows what the service actually managed to do.

One thing worth saying out loud: **the Zepp Settings API has no masked input**, so the
password is legible while it is being typed. It is cleared from the form the moment the
button is pressed. There is no way around that from inside the API today.

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
casual     37.6s   34.6s   40.0s   41.7s    1.96       0
decent     61.3s   58.4s   65.1s   70.6s    2.92       0
good      102.9s   95.0s  108.2s  110.2s    4.09       0
expert    245.4s  241.9s  251.8s  255.6s    5.65       0
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

The rule that ignores it is in the **repository root** `.gitignore`, not in this directory's,
and that is not tidiness: `zeus dev` rewrites `zeppos/.gitignore` with its own defaults on
every run. Silently, and on a *watch* run — so it happens minutes after anyone last looked at
the file. Anything you add here will be gone by the next build; put it in the root.

Bitmaps come from the phone game's own sprite pack:

```bash
python ../tools/generate_zepp_assets.py --preview
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
is the case GoTrue makes look like success.

The one screen no automated check reaches is the "check your inbox" card, because producing
it means creating a real account and sending a real mail. It is built from the same
primitives as the rest of the page, and the branch that produces it is covered.

## Devices

API level 3.0 with a round 480px design width, which covers everything from Amazfit Balance
(the reason for 3.0 — it tops out at API 3.7) up through the T-Rex 3, T-Rex 3 Pro, T-Rex
Ultra 2, Balance 2/3 and Balance Ultra. `zeus build` emits a zpk per round resolution and
Zepp OS rescales both the coordinates and the bitmaps, so the 466px 44mm T-Rex 3 Pro needs
no separate layout.

## The backend

[`supabase/migrations/20260825000000_zepp_edition.sql`](../supabase/migrations/20260825000000_zepp_edition.sql)
adds the table, the two RPCs, the RLS policies, the column grants and the flood trigger. It
touches nothing the phone game uses. Apply it with `supabase db push` before the board can
answer anything.
