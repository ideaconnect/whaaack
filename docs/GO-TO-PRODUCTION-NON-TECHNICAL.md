# Whaaack! — go to production: non-technical

Everything that happens in a console, a contract or a document: Play, AdMob, legal, IP, tax and launch.

Derived from a full audit of the tree on **2026-08-14**, split from a single plan so the two
kinds of work can be picked up independently. Items are verbatim from that audit; nothing was
dropped in the split.

The Play developer account, the AdMob account and both payments profiles already exist and are
verified — IDCT has published apps — so the multi-week external queues that normally dominate a
first launch do not apply, and neither does the 12-testers-for-14-days gate (production access is
a one-time account-level unlock). **Realistic timeline: about a week**, most of it your own
testing and the staged rollout.

See also: **[technical plan](GO-TO-PRODUCTION-TECHNICAL.md)**.

**Severity**

| Tag | Meaning |
| --- | --- |
| 🔴 | **Blocker** — the release is impossible, will be rejected, or ships broken |
| 🟡 | **Pre-launch** — not a submission gate, but shipping without it costs money, users or sleep |
| ⚪ | **Nice to have** — do it when there is room |

---

## 1. Accounts — what is already yours, and what is still per-app

The multi-week queues that dominate a first launch are behind you. What is left is per-app and
short.

- [x] ~~Play developer account, identity verification, payments profile and tax forms~~ —
  already in place from previously published apps. Production access is a one-time account-level
  unlock, so the 12-testers-for-14-days closed-testing gate does not apply to this app.

- [x] ~~AdMob account and payments profile, including address verification~~ — already
  established; the publisher id `pub-6904561240517963` is live and `app-ads.txt` is already
  serving it at the apex.

- [ ] **Create the app entry and confirm the two per-app settings that are not inherited** 🔴
  An established account carries the account-level work forward, but three things are per-app and
  start empty: the **app entry** itself in Play Console, the **in-app product** (§7 — done,
  `no.ads.forever` is created and Active), and the **AdMob app record** with its ad unit and
  its store link (§6). Also re-confirm one account-level item that is per-app in practice: the EU
  **trader status** declaration is account-level, but country availability per app still shows
  "blocked" if it has lapsed — check it reads Trader with a green badge before you set
  availability.

- [ ] **Publish a support address on the Whaaack! surfaces** 🔴
  You almost certainly already have a monitored inbox for the other published apps — the gap is
  that none of it reaches *this* app. `grep -rn mailto: website/` returns nothing: every support,
  GDPR and account-deletion route on the Whaaack! site funnels into one Web3Forms form, and a
  form URL is not accepted in Play's store-listing contact field or in the DSA trader block.
  Reuse the existing address (or add `whaaack@idct.tech` as an alias) and put it on privacy §1,
  terms §1, the delete-account page, the contact page beside the form, and a new Contact row in
  the About screen — which today offers only privacy and terms, so a player who cannot reach the
  website has no support path from inside the app at all. Note `idct.tech` being a verified Resend
  *sending* domain proves nothing about inbound; confirm MX or forwarding actually delivers.

---

## 2. Business decisions

Both have consequences that are expensive to reverse later.

- [ ] **Confirm the app name and accept that `tech.idct.whaaack` is permanent** 🔴
  A package name can never be changed once published — a rename means a new listing with zero
  installs, zero reviews and a new AdMob app. Before the first upload: search Play for "Whaaack"
  and "whack a mole" and look at what you are landing next to (the category is crowded with
  near-identical titles); run a trademark search in the EUIPO eSearch and the Polish UPRP register
  for "Whaaack" and near marks in Nice classes 9 and 41 — you are not obliged to register, but you
  are obliged not to infringe, and a Play trademark complaint takes a listing down. Decide whether
  to file a mark yourself. Record the outcome in `docs/STORE.md` with an explicit line stating the
  package name is final.

- [ ] **Settle the Polish tax position with your accountant** 🟡
  Both revenue streams are cross-border B2B supplies of services from a Polish business to
  Google's EU entity — reverse charge, with an obligation to register for intra-EU transactions
  (VAT-R/VAT-UE) and file the monthly informacja podsumowująca, which applies even under the
  domestic small-supplier exemption. Play IAP revenue does not escape it just because Google is
  seller of record to the consumer: your supply is still to Google. The NIP is already printed
  with the PL prefix on both legal pages, which suggests VAT-UE registration may exist — confirm
  rather than assume (VIES lookup), then decide the PIT treatment and how AdMob/Play statements
  get booked. Registration has a lead time and the first filing falls on the 25th of the month
  after the first transaction. AdMob self-bills; Play does not; neither statement is a
  Polish-format invoice.

---

## 3. Licensing and IP

These four are one cluster: the repo is public, the licence covers the whole tree, and two of
the shipped assets have no established provenance.

- [ ] **Establish the provenance of the app icon** 🔴
  Every icon the app ships — including `assets/icon/play-store-512.png`, which goes to the store
  listing — is generated from `assets/icon/icon-2.png`. The credits in
  [AboutScreen.kt](../app/src/main/java/tech/idct/whaaack/ui/AboutScreen.kt) and
  [README.md](../README.md) name four vendors; the icon artwork is in neither list, and it is not
  a crop of the shipped watermelon sprite. Establish which it is. If it came from an asset pack,
  credit it and check the licence — pack licences very commonly carve out logos, app icons and
  store art specifically, which is exactly the use being made of it. If it was machine-generated,
  understand that in the EU it has no author and therefore no copyright owner, so you cannot stop
  a copycat and cannot base a trademark filing on it. If it was drawn by hand, say so. This is the
  one asset that becomes the brand.

- [ ] **Decide whether the repo stays public** ⚪
  It is public (`ideaconnect/whaaack`) and linked from every page footer, which hands a would-be
  cheat the exact numeric ceiling in the plausibility constraints — the derivation is spelled out
  in the migration comments. That is a judgement call, not a defect, and "stay public" is a
  defensible answer: a defence that only works while a constant is secret was never a defence. But
  it is what turns the LICENSE (§3) and the uncredited audio track ([technical plan](GO-TO-PRODUCTION-TECHNICAL.md) §9) from filing errors into
  redistribution exposure, so make it deliberately and record it in one sentence.

- [ ] **Fix the LICENSE / third-party-asset contradiction** 🟡
  The repo is public and carries a single root BSD 3-Clause LICENSE, which grants everyone
  "redistribution and use in source and binary forms" over the entire repository — including 12
  fruit sprites, 36 splat masks, three CraftPix backgrounds and ~7 MB of audio that are licensed
  *to* IDCT and cannot be sublicensed onward. It directly contradicts terms §7 ("You may not
  redistribute… the assets in your own products without written permission"). Scope the LICENSE to
  source code only, naming `app/src/main/assets/`, `assets/` and `website/assets/img/` as excluded;
  add `THIRD_PARTY_NOTICES.md` listing each pack, vendor, licence and what it permits; and
  reconcile terms §7. If a vendor licence forbids redistributing the raw files at all (CraftPix's
  free licence is the likely case), the honest fix is to stop shipping them in a public repo.

- [ ] **Archive dated licence evidence for all four vendors** 🟡
  Attribution exists and is good — a tappable ASSETS USED list in About plus the README — but no
  licence *text* is stored anywhere. Create `docs/licenses/` with, per vendor, the licence as
  downloaded, a dated capture of the vendor page, and the download or purchase receipt. Then check
  the specific conditions: **Kenney** is CC0 and the only one with no exposure; **JennPixel** —
  itch packs vary, confirm commercial use and whether redistributing raw PNGs publicly is allowed;
  **DavidKBD** — typically commercial-with-mandatory-credit, confirm the credit wording matches
  what he requires; **CraftPix** — permits commercial use but restricts redistributing source files
  and requires a link back. The CraftPix entry is also the weakest record: `author = null`, a
  generic title, and a URL pointing at the licence index rather than the pack, so it does not
  identify which pack was used. Fix that entry and email any vendor whose terms are ambiguous.

---

## 4. Legal documents

The privacy policy and terms are unusually thorough for an indie release, which is exactly why
the remaining gaps stand out. Google cross-checks the policy against the Data safety
declaration, so several of these are also Play submission risks.

- [ ] **Correct three statements about the paid unlock** 🟡
  From a full review of the money paths against the shipped code. None is a lie, all three are
  statements a refund dispute or a policy reviewer would test:
  - **terms §6 promises more than the code delivers offline.** It says the unlock "follows you to
    a new device and survives being offline". The second half is true only once *that install*
    has confirmed the purchase with Play at least once — on a fresh install with no connectivity,
    a paying player sees ads until Play is reachable, which `BillingManager`'s own header calls a
    deliberate consequence. Qualify the sentence rather than change the code: seeding the
    entitlement any other way hands the product to everyone.
  - **privacy §"Purchases" understates what stays on the device.** It says the purchase is "held
    by Google Play"; the app also keeps, in its own DataStore, whether you own it, the Play order
    id (so support can look a purchase up) and the time of the last check. Nothing leaves the
    device — which is the easy sentence to write and the one currently missing.
  - **the obfuscated account id is disclosed nowhere.** Purchases carry a SHA-256 of the Supabase
    user id to Play as `obfuscatedAccountId`, for Google's fraud detection. It is pseudonymous
    and Google's own guidance asks for it, but it is still a transfer to a named recipient and
    belongs in the recipients table beside the rest.

- [ ] **Add the three undisclosed processors to the privacy policy** 🔴
  The policy is unusually thorough — a per-purpose lawful-basis table, a recipients list, transfers,
  the full Art. 15–21 rights with UODO named — which makes the gaps stand out. Three live
  recipients appear nowhere: **Resend, Inc.** (US), which processes every user's email address and
  the body of every transactional message; **Intuition Machines / hCaptcha** (US), which receives
  visitor IP and browser signals on the contact page; and **GitHub** (Pages), which hosts the site
  the policy explicitly says it covers and logs visitor IPs. Each is an Art. 13(1)(e)/(f) gap and
  each makes the Play Data safety declaration inaccurate at the same time, because Google
  cross-checks the two.

- [ ] **Fix four privacy-policy claims that contradict the code** 🔴
  (1) §2's purchase row says "we store only a yes/no flag on your device", but `EntitlementStore`
  also persists the Play **order ID**, verification timestamps and a not-owned streak. (2) §4 says
  only display name and best score are public, while `leaderboard()` returns each player's **UUID**
  and `achieved_at` to any anonymous caller (see the trim item in [technical](GO-TO-PRODUCTION-TECHNICAL.md) §4 — fixing the function is the
  better fix). (3) The "stays on your device" list omits the cached email and display name in the
  session store. (4) §5's single generic SCC paragraph should name each processor's country and the
  mechanism actually relied on. Also make §4's DPA claim true rather than aspirational: accept
  Supabase's DPA in the org dashboard, request Resend's, confirm Web3Forms', and keep the copies
  where you can produce them within the month §7 promises.

- [ ] **Correct the consent-refused sentence** 🟡
  §3 says "If you refuse, you still get the game and you still get ads — just non-personalised
  ones." That is true only for *partial* refusal, where the player accepts Purpose 1 and declines
  personalisation. A player who rejects everything makes `canRequestAds()` false and this app then
  shows **no ads at all** — so the sentence is factually wrong for that case, in a document whose
  entire job is describing processing accurately.

- [ ] **State the site's cookie position and defer the only third-party script** 🟡
  Verified: the site's own code sets **no** cookies and touches no localStorage, and there is
  exactly one third-party resource across the whole site — `js.hcaptcha.com` on the contact page.
  That is the right outcome, just an undocumented one. The policy says it covers the website but
  has no website section. Add a short "Cookies and this website" heading saying plainly that no
  cookies are set, no analytics run, hosting is GitHub Pages, and hCaptcha is the only third-party
  resource. Because hCaptcha stores and reads on the device and the policy itself cites art. 399
  Prawa komunikacji elektronicznej, either argue the anti-abuse necessity exemption explicitly or —
  cleaner — load the script only when the user first focuses a form field, which keeps every other
  page third-party-free and removes any argument for a banner.

- [ ] **Add DSA notice-and-action and a point of contact** 🟡
  Publishing user-chosen display names makes this a hosting service under the DSA. As a
  micro-enterprise you are exempt from the Art. 20–28 online-platform duties via Art. 19, but
  Arts. 11–12 (a single electronic point of contact for authorities and recipients), 14 (ToS
  transparency on content restrictions) and 16–17 (notice-and-action plus a statement of reasons)
  apply regardless of size. Terms §3 already forbids offensive and impersonating names and promises
  to explain a removal, which is most of a statement of reasons. Add one short section: a named
  electronic contact point, an explicit route for a third party to report a name, a commitment to
  act and give reasons, and an appeal address.

- [ ] **Give the right of withdrawal an actual exercise mechanism** 🟡
  Terms §6 is already stronger than most — Google as seller of record in the EEA/UK, the art. 38
  ust. 1 pkt 13 conditions for losing the 14-day right and the concession that it survives if they
  were not met, Chapter 5b conformity remedies, and the correct note that the ODR platform closed
  on 20 July 2025. What is missing is the *how*: the model withdrawal form from Annex 1 to the
  ustawa o prawach konsumenta (or a link), where to send it, and when the 14 days start for digital
  content. Say explicitly that the practical first step is Google's refund flow and that a
  withdrawal will be honoured directly if Google declines, so the consumer is never bounced between
  two parties. Add the art. 12 ust. 1 pkt 18–19 functionality/interoperability line: the
  entitlement is bound to a Google Play account, needs Android 8.0+, and is not transferable.

- [ ] **Replace vague retention wording with real periods** 🟡
  Two rows resolve to nothing measurable — technical request data kept for "short-lived operational
  logs", backups holding traces "for a short period". Art. 13(2)(a) wants the period or the
  criteria. Look up the real numbers for the Supabase plan you land on (1 day of logs on Free,
  7 on Pro) and state them as days, using identical wording on both the privacy page and the
  delete-account page so they cannot drift. Cap the contact-form row too, e.g. 12 months after the
  exchange closes.

- [ ] **Publish Polish versions, or delete the clause that assumes they exist** ⚪
  Terms §13 says "Where a Polish-language version is provided and the two conflict, the Polish
  version prevails" — but no Polish version exists anywhere, so the clause dangles. Both documents
  are drafted around Polish law throughout (RODO, art. 399 PKE, ustawa o prawach konsumenta, UODO,
  UOKiK, powiatowy rzecznik konsumentów). Either publish `/whaaack/regulamin/` and
  `/whaaack/prywatnosc/` — a Polish trader selling to Polish consumers is expected to offer Polish
  terms and UOKiK treats the absence unfavourably — with hreflang cross-links and sitemap entries,
  or delete the clause and state English is the sole language of the contract.

- [ ] **Write the GDPR Art. 30 record of processing and a 72-hour breach procedure** 🟡
  The outward-facing side is strong; the internal side does not exist. Art. 30(5)'s
  under-250-employee carve-out does not rescue you: it applies only where processing is occasional,
  and a leaderboard continuously processing email addresses, online identifiers and per-run
  activity for every signed-in player is not occasional. The RoPA is a one-page document —
  categories of subject and data, purposes, legal bases, recipients (Supabase, Google, Resend,
  Web3Forms, hCaptcha, GitHub), transfers and safeguards, retention, security measures. Then the
  Art. 33 procedure: what counts as a breach here, notifying UODO within 72 hours, Art. 34
  notification to data subjects if high risk, and what evidence you would attach — which is where
  the Supabase log-retention item above stops being a durability question and becomes what makes
  the procedure executable.

- [ ] **Make an accessibility determination for the game and record the EAA position** 🟡
  One real accessibility feature exists and works: the "Parallax background — turn off to reduce
  motion" toggle, which the backdrop genuinely honours. Everything else is unaddressed, and the
  *game* is a different problem from the menus: the run is drawn on a Canvas by a private render
  thread, so TalkBack cannot see the board, the score, the strikes or the End-run control at all.
  There is one difficulty with no way to slow it down, fruit live 580ms across all sixteen tiles
  at top speed, fruit are distinguished by colour and silhouette with no colourblind consideration, and
  the three strike dots are red-on-dim with no other indicator. Record the legal position: the
  European Accessibility Act has applied since 28 June 2025 and covers e-commerce services, with a
  microenterprise exemption IDCT plainly meets — but the exemption applies to services, not
  products, so write the determination and its reasoning down rather than assuming it. Then pick
  one or two cheap product wins: an "Easier" mode holding the curve at a higher floor, and a strike
  counter that reads as text as well as dots.

---

## 5. Play Console — declarations and listing

Play blocks a production rollout while any App content section is incomplete, so most of this
section is a submission gate as well as a policy one.

- [ ] **Complete the Data safety form from what the code actually sends** 🔴
  Derived from the code, not from memory. Collected by **you** via Supabase, all **optional**
  (casual play needs no account) and all **encrypted in transit**: *Personal info → Email address*
  (account management); *Personal info → Name* — the display name, which is **publicly visible**;
  *Personal info → User IDs* — the Supabase UUID returned in leaderboard rows; *App activity →
  Other actions* — millis, hits, top_speed and timestamp. Collected **and shared** by the Google
  Mobile Ads SDK: *Device or other IDs* (the `AD_ID` permission is in the merged manifest —
  declaring the permission without declaring this data type is an automatic rejection), plus
  *Approximate location* (AdMob derives it from IP), *App activity → App interactions*, and
  *Crash logs / Diagnostics*. Copy the canonical AdMob rows from Google's published data-disclosure
  page rather than from memory, and check them against **25.4.0**, the version this app now ships.
  Declare nothing for the purchase — the entitlement is a device-local flag and Play's own billing
  data is exempt. Answer "Users can request that data be deleted" = **Yes**. If you adopt a crash
  reporter or analytics ([technical plan](GO-TO-PRODUCTION-TECHNICAL.md) §1), this form changes — which is why that decision comes first.

- [ ] **Enter the account-deletion URL** 🔴
  The page already satisfies the policy's substance: live, reachable with no login and no install,
  describes both the in-app route and the without-the-app route, states what is deleted and that it
  is irreversible, and is linked from every footer. Paste
  `https://idct.tech/whaaack/delete-account/` into the Data safety data-deletion step. One soft
  spot to close: the web route depends on the contact form, which needs JavaScript for its hCaptcha
  widget — add a plain mailto fallback to the delete-account page once the support mailbox exists.

- [ ] **Enter the privacy policy URL** 🔴
  `https://idct.tech/whaaack/privacy/` — use the canonical trailing-slash form so an automated
  checker never sees a redirect. The page is live and thorough; SETUP.md §7 still lists it as
  unpublished, which is stale — fix that line.

- [ ] **Declare "Contains ads" and the Advertising ID** 🔴
  App content → Ads: **Yes**. Declaring No while shipping AdMob is a straightforward policy
  violation. App content → Advertising ID: **Yes**, purposes Advertising/marketing and Analytics,
  because `com.google.android.gms.permission.AD_ID` is in the merged manifest. Play blocks a
  production release while any App content section is incomplete, so these are submission-flow
  blockers as well as policy ones.

- [ ] **Complete the IARC content rating, answering the interaction question honestly** 🔴
  Short for this app (no violence beyond fruit splats, no substances, no gambling), but three
  answers must be right or the rating is invalid and revocable: **users can interact / share
  user-provided content = YES**, because a user-chosen display name is published to every player;
  **digital purchases = YES**; **displays advertising = YES**. Expect PEGI 3 / ESRB Everyone with a
  "Users Interact" descriptor. Re-run it whenever these facts change.

- [ ] **Set Target audience to 13+ and manage the "appeals to children" risk** 🔴
  Select 13+ bands only. Ticking any under-13 band pulls you into the Families policy, which
  forbids the `AD_ID` permission the manifest declares and requires
  `tagForChildDirectedTreatment(true)` the code never sets — the current build would be
  non-compliant on both counts. Then expect pushback: bright cartoon fruit, a whack-a-mole loop and
  a playful icon are exactly the profile Google flags as "may unintentionally appeal to children".
  Keep the feature graphic and screenshots free of kid-coded framing. If Google reclassifies it as
  mixed-audience you must add a neutral age screen, branch TFCD/TFUA per user, drop AD_ID for the
  child branch, and restrict to Families-certified SDKs — a multi-day change. The privacy policy
  already takes the right position in §8.

- [ ] **Confirm the EU trader declaration still reads Trader, and that the published block matches this app's pages** 🟡
  The declaration is account-level and mandatory for EU distribution since 17 February 2025, so if
  the other apps are distributed in the EU it is already in place — this is a check, not a task.
  Two things worth actually looking at rather than assuming. First, an expired phone verification
  or a stale address quietly turns EU country availability to "blocked", and you would find out
  from the release page rather than a notification. Second, Google publishes that name, address,
  phone and email on the listing — so make sure it matches what
  [privacy §1](../website/privacy/index.html) and [terms §1](../website/terms/index.html) say for
  Whaaack!, because a reviewer comparing the two is exactly the kind of inconsistency that gets
  flagged. If the published Szczecin address is no longer the one you want public, changing it
  means changing it in all three places at once.

- [ ] **Provide working review credentials in App access** 🔴
  Casual play, the leaderboard read and all settings toggles work signed out; ranked submission,
  my-standing, display-name/email/password changes and account deletion do not. Select "All or some
  functionality is restricted" and add an entry with a pre-created account — create it in the
  Supabase dashboard with **Auto Confirm User** ticked so it works regardless of SMTP — plus
  step-by-step instructions ("tap Sign in → enter …→ Play ranked → the score posts to the board").
  Do **not** point reviewers at Google Sign-In: that additionally depends on the consent screen
  being in Production and the Play signing SHA-1 being registered. Note separately that shipping
  with signup broken is itself a rejection risk under Minimum Functionality — a reviewer who taps
  "Sign up" gets an error.

- [ ] **Tick the remaining short declarations** 🔴
  News apps, COVID-19 contact tracing, government apps, financial features, health apps — all No,
  so App content reads Complete.

- [ ] **Produce the 1024×500 feature graphic** 🔴
  Verified: nothing in the tree is 1024×500 (checked every PNG under `assets/` and
  `website/assets/img/`). It is a required asset — the listing stays incomplete and the release
  cannot roll out without it. PNG or JPEG, no alpha, max 15 MB, with the logo and any text well
  inside the middle ~60–80% because Play crops it differently across surfaces and overlays a play
  button on the games tab. The source material is already in the repo — the orchard parallax
  layers, the fruit sprites, `icon-2.png` — so add a `--feature-graphic` mode to
  `tools/generate_screenshots.py`, which already loads exactly those assets, and keep the output
  regenerable. The 512×512 store icon is done and verified compliant (fully opaque, 165 KB).

- [ ] **Replace the synthetic screenshots with real captures and add tablet sets** 🟡
  The four existing shots are 1080×2340 and technically valid, but they are **not device
  captures**: `tools/generate_screenshots.py` re-implements the renderer's geometry and palette in
  Pillow and even uses Windows Arial Black where the app uses `sans-serif-black`. They also cover
  only in-run gameplay — no home, leaderboard, game-over or settings. The Store Listing policy
  requires assets that reflect the actual app experience, so capture real frames
  (`adb exec-out screencap -p`) for home, a run at top speed, game over, the leaderboard, and
  Settings showing the ad-free row. Play accepts 2–8 phone shots; promotional eligibility wants at
  least 4 at ≥1080px in 16:9 or 9:16, and 1080×2340 is 9:19.5, so the Store may letterbox. Then add
  **7-inch and 10-inch tablet** sets — without them the app is ineligible for large-screen
  promotion and gets a "not optimised" treatment. Keep the generator for the website. Captions
  naming the hook outperform bare screenshots.

- [ ] **Write the store listing copy into `docs/STORE.md`** 🔴
  Nothing store-shaped is written down anywhere. **App name** ≤30 chars — "Whaaack!" is 8, so
  spend the rest on a keyword-bearing suffix ("Whaaack! Fruit Whack-a-Mole") since that is what
  gets indexed; no emoji, no all-caps words, no "#1"/"best", no price or promotion, all of which
  the metadata policy prohibits. **Short description** ≤80 chars — the highest-leverage ASO field,
  it appears in search results. **Full description** ≤4000 chars, front-loading the
  reflex/arcade/leaderboard keywords in the first ~170 because that is what shows before "read
  more". Category: Games → **Arcade** fits the mechanic better than Casual and is a less crowded
  chart. Up to five tags. Contact details: the new support email (mandatory and publicly shown),
  website `https://idct.tech/whaaack/` — this is also what AdMob crawls for app-ads.txt, so it must
  be on the `idct.tech` host and never a github.io URL, and never the `www` form, which 302s.
  "What's new" notes for the first release. Keep it consistent with the Data safety card and the
  privacy policy; contradictions between them are a common rejection reason. There is good raw
  material in `website/index.html` and the README.

- [ ] **Set country availability and price the product** 🟡
  Defaulting to all countries is fine for a game with no localisation, but note the advertising ID
  and UMP behave differently by region and the policy is English-only. Pick a base price in PLN or
  EUR, let Google auto-convert, then review the table for any territory where the conversion lands
  somewhere silly. Google is seller of record in the EEA/UK so VAT is handled there — but you still
  set the price and must have completed the tax forms.

- [ ] **Plan the tracks and turn on managed publishing** 🟡
  Internal testing first (≤100 testers, no review wait) to validate billing, Google sign-in under
  the Play signing key, and the pre-launch report. Then straight to production with a staged
  rollout — the mandatory closed-testing gate applies only to personal accounts that have never
  been granted production access, which is not this one. Switch on **managed publishing** so a
  review finishing at 03:00 does not go live unattended.

- [ ] **Use the pre-launch report deliberately** 🟡
  It runs automatically on the first AAB uploaded to any testing track, crawling real Firebase
  devices. Give it the same credentials you put in App access or it only ever exercises the home
  screen and a casual run. Read all four tabs: **Stability** (the surface-teardown paths are
  exactly what a forced rotation/backgrounding crawl stresses), **Performance**, **Accessibility**
  (expect findings — the HUD is a Canvas with no content descriptions), and **Screenshots** across
  form factors, which is the cheapest way to see the portrait-locked layout on a tablet. One
  caution: the crawler triggers interstitial requests against the **live** unit — watch AdMob's
  invalid-traffic reporting afterwards.

- [ ] **Add a promo video and a `pl-PL` listing** ⚪
  A 15–30 second YouTube clip of an actual run is the single highest-leverage listing asset for an
  arcade game — it plays in the header above the feature graphic. Public or unlisted, not
  age-restricted, ads disabled. And add a Polish localisation: the developer is Polish, the legal
  documents already speak to Polish consumer law, and Poland is the most likely first market.
  Localisation is per-language text on the same listing, so it costs one form, not a second app.
  Note the listing and the app UI are separate — the app itself ships English-only.

- [ ] **Note the Android Developer Verification programme** ⚪
  Phasing in from September 2026 in an initial set of countries: developers must register their
  package name and signing keys before apps can be installed on certified devices. Distributing
  through Play with a verified account covers it automatically, so this is awareness only — but if
  you ever also distribute the APK directly from `idct.tech`, that channel needs separate
  registration.

---

## 6. AdMob console

The code side of ads is in the [technical plan](GO-TO-PRODUCTION-TECHNICAL.md). These are
console settings — and the first one can end the monetisation model before launch if it is
skipped.

- [ ] **Register your test devices with AdMob before the first release-signed install** 🔴
  Debug builds are safe — they force Google's test unit — but internal and closed tracks distribute
  **release** builds against the live unit, and `setTestDeviceIds` is called nowhere in the project
  (the one `addTestDeviceHashedId` hit is UMP's consent-geography hook, unrelated to serving). So
  every release install on your own phone issues genuine ad requests, and every curious tap is a
  real self-generated click. If a handful of testers play for a week and a few tap an interstitial,
  invalid-traffic detection sees a tiny cluster with anomalous CTR — and an AdMob suspension on a
  zero-history account is effectively permanent and destroys the monetisation model before launch.
  Use the console mechanism (AdMob → Settings → Test devices, by advertising ID or the hashed id
  the SDK logs) because it applies without a rebuild and cannot ship to users. Brief every tester
  in writing: **do not click ads**.

- [ ] **Confirm ad unit `…/2703686934` is of type Interstitial** 🔴
  The code side is correct and the debug override uses the matching test format. Ad unit format
  **cannot be changed after creation** — if it reads Rewarded or Rewarded interstitial, the fix is
  a new unit, its id in `local.properties`, an updated fallback in `build.gradle.kts`, and
  archiving the old one. A mistyped unit never fills and `showThen` silently skips the ad, so this
  failure produces zero error signal in the app and looks exactly like no-fill.

- [ ] **Set AdMob's COPPA/audience settings to match the Play declaration** 🔴
  There is **no `RequestConfiguration` call anywhere** in the project, so GMA's
  `tagForChildDirectedTreatment`, `tagForUnderAgeOfConsent` and the new `AgeRestrictedTreatment`
  are all UNSPECIFIED. The only age signal is UMP's `setTagForUnderAgeOfConsent(false)`. Make the
  three places agree: Play target audience 13+, AdMob app settings "not child-directed", and if you
  set anything in code, `AgeRestrictedTreatment.UNSPECIFIED`.

- [ ] **Re-verify the GDPR/TCF message on a release build** 🟡
  The mechanism is documented correctly (UMP fetches the message by AdMob app id; there is no
  message id to embed) and one emulator run showed the real published message with 210 partners —
  but that predates the release build and the listing. Confirm four things: status is **Published**,
  not Draft; targeting **includes this app** (a message created before the app existed may target
  a list that omits it); the message offers a reject option **at the same level** as consent, which
  Google has required since the January 2024 TCF/DMA changes and which is a serving-eligibility
  problem, not cosmetics; and the configured privacy URL points at
  `https://idct.tech/whaaack/privacy/`. Also decide the partner list deliberately — 210 is the
  broad setting, which maximises demand and measurably increases dialog bounce.

- [ ] **Link the AdMob app to the Play listing after publication** 🟡
  An AdMob app that is not store-linked serves limited or no inventory, and Google applies limited
  serving to unverified apps generally — so **expect near-zero fill and eCPM for the first one to
  four weeks and do not diagnose it as a bug in `AdsManager`**. The order cannot be shortcut:
  publish to production → AdMob → Apps → App settings → App store → link the live listing → wait
  for the crawl. Also confirm the AdMob account itself is fully verified (identity, address PIN,
  tax, payments) or serving stays limited regardless.

- [ ] **Confirm app-ads.txt is crawled — it is already published and correct** ⚪
  **Correction to an earlier reading:** `https://idct.tech/app-ads.txt` is live and returns
  `google.com, pub-6904561240517963, DIRECT, f08c47fec0942fa0`, matching the AdMob app id, with a
  header comment explaining exactly why it must sit at the domain root. It is deliberately owned by
  the org's apex Pages repo, not this one — a project page under `/whaaack/` could only ever serve
  `/whaaack/app-ads.txt`, which no crawler reads, and `pages.yml` forbids a project CNAME for the
  same structural reason. **Do not "fix" this by copying a file into `website/`.** The only
  outstanding work: make sure the Play listing's website field is on the `idct.tech` host (apex,
  not `www`, which 302s), then check AdMob's status a few days after launch and treat "Not found"
  as a configuration problem rather than a missing file. Add a line to SETUP.md §4 recording where
  the file lives so nobody duplicates it later.

- [ ] **Defer mediation; set up blocking controls and an Ad Inspector baseline first** ⚪
  Only GMA and UMP are on the dependency list — no third-party adapters. Keep it that way through
  launch: each adapter brings its own SDK, permissions, TCF vendor entry, Data safety line and
  Families exposure, multiplying the compliance surface for a lift that is worthless while the app
  has no traffic and is still in limited serving. Instead configure Blocking controls and enable
  the Ad Review Center so you can pull a bad creative within hours. Leave eCPM floors alone until
  you have weeks of geography and fill data — a floor set blind on an unverified app suppresses the
  little fill you have.

---

## 7. The in-app product

The client is complete and ships safely without the product — the button hides itself on
`ITEM_UNAVAILABLE`. Testing the flow is in the [technical plan](GO-TO-PRODUCTION-TECHNICAL.md).

- [x] **Create and activate the one-time product** ✅
  Done, 14 Aug 2026: **`no.ads.forever`**, "Whaaack the ads!", with one purchase option
  `no-ads-forever-buy` — type Buy, **Active**, 173 countries/regions, and flagged **backwards
  compatible**, which is the property the client depends on (see the offer-model item in the
  [technical plan](GO-TO-PRODUCTION-TECHNICAL.md)). The id was `whaaack_remove_ads` here and in
  the build until the real product existed; `app/build.gradle.kts` now compiles in
  `no.ads.forever` as the BuildConfig default, and `local.properties` does not override it, so
  that exact string ships. Ids are permanent and cannot be reused after deletion — this one is
  now fixed for the life of the app.

  Two loose ends, both cosmetic and both console-side:
  - the store description reads "Removes completely ads from the game, forever." — the adverb is
    in the wrong place, and this string is shown to buyers inside Play's sheet. "Removes ads from
    the game completely, forever." reads properly and is still well inside the 200-char limit;
  - the tag **advertisment** is misspelled (advertisement). Tags are only discovery metadata, so
    nothing breaks, but they are visible in the console to anyone who inherits this account.

  Allow up to 24h after any edit before concluding propagation is broken, and note that a
  saved-but-*inactive* product returns `ITEM_UNAVAILABLE` exactly like a nonexistent one — the
  single most common "my IAP doesn't show up" cause. This one is already Active.

- [ ] **Write the refund runbook** 🟡
  Refunding an order in Play Console does **not** remove the entitlement unless you explicitly tick
  revoke — a refund-only order keeps appearing in `queryPurchasesAsync`, so the player keeps
  ad-free forever. Document the path, and document two consequences of the deliberate design for
  whoever handles support: revocation takes at least two foregrounded, online sessions to land, and
  an offline device keeps the unlock indefinitely (no TTL, by design, and the terms say so). There
  is no Voided Purchases API or RTDN integration, so revocation is only ever observed client-side.

---

## 8. Launch and the first 48 hours

- [ ] **Commit to the ordered sequence** 🔴
  Write it into `docs/RELEASE.md`. With the account queues already behind you the shape is short,
  and the ordering that matters is the one around the Play signing key — several things cannot be
  done until an AAB has been uploaded once.
  **Day 1:** SMTP fix; push the Supabase migration (technical §4) and re-run its probes; create the release
  keystore and wire the signing config; `config push` and publish the OAuth consent screen; create
  the app entry in Play Console; register your devices with AdMob **before** any release-signed
  install.
  **Days 1–3, no external wait:** the code fixes in technical §3; the feature graphic and real screenshots;
  the support address on the site and in About; the privacy-policy corrections in §4; the LICENSE
  split and the uncredited audio track; RELEASE.md, CHANGELOG.md, STORE.md.
  **First upload:** internal testing track — this mints the Play App Signing key. Copy its SHA-1
  onto the Android OAuth client, add the upload key's too, then reinstall from Play and verify
  Google sign-in and email signup actually work. Confirm the AdMob unit is Interstitial.
  **Then:** create and activate the in-app product, run the billing matrix (technical §5), complete every
  App content declaration and the IARC questionnaire, read the pre-launch report, smoke-test the
  R8'd release build end to end on a real device.
  **Then:** production submission with managed publishing on, staged rollout 5 / 20 / 50 / 100 with
  24h holds and named go/no-go criteria at each gate.

- [ ] **Harden the owner-account concentration** 🟡
  Nothing is recorded about who controls what. The Google Cloud project, both OAuth clients, the
  AdMob account, the Play account, GitHub, Supabase, Resend and the `idct.tech`
  registration all hang off one person with no documented recovery path — and with other apps
  already published under the same account, the blast radius is bigger than this project. Three
  things: **(1)** Play
  Console requires 2-Step Verification — turn it on with hardware keys or an authenticator plus
  printed backup codes stored offline, and do the same for the Google account owning AdMob and
  Cloud, because losing that one account loses the app, the ad revenue and the sign-in
  configuration simultaneously, and Play account recovery is a support ticket measured in weeks.
  **(2)** Put `idct.tech` on auto-renew with a registrar lock and a card that will not expire, plus
  a 60-day calendar reminder: the privacy-policy URL, the deletion URL, the in-app About links and
  AdMob's app-ads.txt crawl all resolve through that one domain, so a lapsed renewal is
  simultaneously a Play policy violation and a broken GDPR rights channel. **(3)** Write an account
  inventory into a password manager: each service, the owning email, where the recovery codes are,
  and who the second contact is.

- [ ] **Define the first-48-hours watch list and put a name on it** 🟡
  With a one-developer project the honest answer is "me, with phone alerts on" — state it rather
  than assume it. **Play vitals:** user-perceived crash rate against the ~1.09% bad-behaviour
  threshold and ANR against ~0.47%, checked **per device model** rather than only in aggregate,
  because a single vendor's GPU driver failing in `lockHardwareCanvas` shows as a small global
  number and a catastrophic model-specific one; halt the rollout if either crosses. **Ratings:**
  the first ~50 set the visible average for months — read every one-star in the first 48 hours,
  they are almost always a reproducible bug report. **AdMob:** the Policy centre for invalid-traffic
  flags, plus match rate and eCPM; a sudden CTR spike means a UI bug placing an interstitial where a
  tap lands, or worse. **Supabase:** auth error rate (where broken SMTP or the Resend daily cap
  surfaces), database size, egress. **Resend:** daily send count against the cap. **The website:**
  the privacy and terms pages must stay up because the app deep-links them; a Pages outage during
  review is a rejection. Turn on Play Console email alerts for vitals, ratings and policy, routed
  to an address someone actually reads.

- [ ] **Budget the running costs** ⚪
  $25 one-time Play registration; the `idct.tech` renewal; Supabase at $0 or ~$25/month (technical §4 argues
  for paid); Resend at $0 or ~$20/month depending on signup volume. The Play registration fee and
  both payments profiles are already paid for and set up. Play's service fee is 15% on the first
  $1M of annual earnings and 30% above, applied automatically across the account — so Whaaack!
  revenue shares the same annual threshold as the other published apps.

- [ ] **Set a review-response policy** ⚪
  Commit to reading and replying to every review in the first two weeks and every negative review
  thereafter — replies are public, they visibly move ratings, and Play surfaces developer
  responsiveness. Consider adding the Play In-App Review API triggered from game-over after a good
  run (a new personal best on the third-or-later session), never after a loss and never after an
  ad; the API self-throttles so the quota is not yours to manage.

- [ ] **Add the Play badge and store link at launch — and fix the copy that oversells** 🟡
  Correct as of today: no Play link or badge anywhere, so the site does not advertise a store page
  that does not exist. At launch, use the official badge artwork from Google's brand guidelines
  (do not redraw, recolour or crop), keep clear space of at least a quarter of the badge height,
  respect the minimum height, link to
  `https://play.google.com/store/apps/details?id=tech.idct.whaaack`, add the "Google Play and the
  Google Play logo are trademarks of Google LLC." footer attribution, and repoint the hero CTA from
  `#adfree` to the store. Separately: the homepage sells signed-in ranked play while signup email
  is still broken — fix SMTP before the site goes public with a store link.

---

## Already done — do not re-litigate

Verified during the audit, recorded so nobody spends time on them twice.

- **app-ads.txt is live and correct** at `https://idct.tech/app-ads.txt`, served from the org's
  apex Pages repo, with the publisher id matching the manifest. Copying it into `website/` would
  serve it at a path no crawler reads.
- **The privacy policy and terms are unusually thorough**: per-purpose lawful bases, a recipients
  list, transfers, the full Art. 15–21 rights with UODO named, correct Polish consumer-law framing
  down to the ODR platform's closure on 20 July 2025.
- **The account-deletion page satisfies Play's substance** — reachable without login, both routes
  described, irreversibility stated, linked from every footer.
- **The site sets no cookies and runs no analytics**; hCaptcha on the contact page is the only
  third-party resource on the entire site.
