// Turns a Play Games server auth code into a Whaaack! session.
//
// ## Why this exists at all
//
// A Play Games leaderboard can only ever hold Play Games players, and our board holds
// Whaaack! accounts — so a player who has Play Games and nothing else could play ranked runs
// that went nowhere. This mints them an account from the identity they already have, without
// a sign-up screen.
//
// ## Why it has to be server-side
//
// The client cannot be trusted to say who it is. `requestServerSideAccess` hands the app a
// one-time authorization code that is worthless without the web OAuth client's *secret*, and
// only exchanging it proves which Play Games player asked. If the app simply POSTed a player
// id, anyone holding the anon key — which ships inside the APK — could claim any id on the
// board. So the secret lives here, the exchange happens here, and the player id is read out
// of Google's answer rather than out of the request body.
//
// ## The flow
//
//   1. POST { code } from the app, carrying its `apikey` header (the gateway's verify_jwt is
//      off — it would reject the publishable key — so the handler checks the header itself).
//   2. Exchange the code at Google's token endpoint for an access token.
//   3. GET games/v1/players/me with it — this, and only this, is where the player id and the
//      gamer tag come from.
//   4. Find-or-create a Supabase user keyed to a deterministic address derived from that id.
//   5. Mint a session for it and hand the session back.
//
// ## Configuration
//
//   supabase secrets set PGS_WEB_CLIENT_ID=...       # the "Game server" credential's client
//   supabase secrets set PGS_WEB_CLIENT_SECRET=...   # ...and its secret, from Cloud Console
//   supabase secrets set PGS_PLAYER_EMAIL_DOMAIN=pgs.whaaack.invalid   # optional
//
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are injected by the platform.
//
// The client id must be the **web** one registered as a Game server credential in the Play
// Games Services configuration — not the Android OAuth client, which has no secret and is
// identified by package name and signing certificate instead.

import { createClient, type Session } from "jsr:@supabase/supabase-js@2";

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GAMES_PLAYER_URL = "https://games.googleapis.com/games/v1/players/me";

/**
 * The domain half of the address every Play Games account is keyed to.
 *
 * `.invalid` is reserved by RFC 2606 and can never resolve, which is the point: this is an
 * identifier, not a mailbox, and it must be impossible for it to collide with a real address
 * somebody could receive mail at. Nothing can be sent here, least of all a password reset —
 * and nothing needs to be. Play Games is the credential: the player id is stable for a Google
 * account, so a new device, a reinstall or a log out all resolve back to this same address
 * and therefore this same account, with no prompt and nothing for the player to remember.
 */
const EMAIL_DOMAIN = Deno.env.get("PGS_PLAYER_EMAIL_DOMAIN") ?? "pgs.whaaack.invalid";

const CLIENT_ID = Deno.env.get("PGS_WEB_CLIENT_ID") ?? "";
const CLIENT_SECRET = Deno.env.get("PGS_WEB_CLIENT_SECRET") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * What the app is told when something goes wrong.
 *
 * Deliberately coarse. The app maps this onto one line of copy and there is nothing a player
 * can do about any of the distinctions — a bad client secret and a Google outage are the same
 * event from the sofa. The detail goes to the function log, where it is useful.
 */
function failed(status: number, code: string, detail: unknown): Response {
  console.error(`play-games-auth: ${code}`, detail);
  return json(status, { error_code: code, msg: "Play Games sign-in could not be completed." });
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json(405, { error_code: "method_not_allowed", msg: "POST only." });

  if (!CLIENT_ID || !CLIENT_SECRET || !SERVICE_ROLE_KEY || !SUPABASE_URL || !ANON_KEY) {
    return failed(500, "not_configured", "missing one of PGS_WEB_CLIENT_ID/SECRET or the platform keys");
  }

  // The gateway's verify_jwt is off — it parses Authorization as a JWT, and this project's
  // publishable key is not one, so leaving it on 401'd every call before this code ran. This
  // is the same keep-off-the-open-internet property done where it can actually work: the app
  // sends its `apikey` header on every request, and anyone without the key gets no free
  // Google-token-exchange endpoint. Not a security boundary — the key ships in the APK; the
  // code exchange below is the boundary — just a door that isn't left open.
  if (req.headers.get("apikey") !== ANON_KEY) {
    return json(401, { error_code: "unauthorized", msg: "Missing or wrong API key." });
  }

  let code: unknown;
  try {
    code = (await req.json())?.code;
  } catch (error) {
    return failed(400, "bad_request", error);
  }
  if (typeof code !== "string" || code.length === 0) {
    return json(400, { error_code: "bad_request", msg: "A Play Games auth code is required." });
  }

  // ---- 1. the code is only worth anything with the secret ---------------------------

  const tokenResponse = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      // Empty rather than absent, matching Google's own server-side sample: there is no
      // browser in this flow and so no redirect to come back to.
      redirect_uri: "",
    }),
  });
  if (!tokenResponse.ok) {
    // The overwhelmingly likely causes are a client id that is not the Game server
    // credential, a stale secret, or a code that has already been spent — an auth code is
    // single-use, so a retried request lands here rather than on a second session.
    return failed(401, "code_rejected", await tokenResponse.text());
  }
  const googleAccessToken = (await tokenResponse.json())?.access_token;
  if (typeof googleAccessToken !== "string") {
    return failed(502, "no_access_token", "token endpoint returned no access_token");
  }

  // ---- 2. who the code actually belongs to -------------------------------------------

  const playerResponse = await fetch(GAMES_PLAYER_URL, {
    headers: { Authorization: `Bearer ${googleAccessToken}` },
  });
  if (!playerResponse.ok) {
    return failed(502, "player_lookup_failed", await playerResponse.text());
  }
  const player = await playerResponse.json();
  const playerId: unknown = player?.playerId;
  if (typeof playerId !== "string" || playerId.length === 0) {
    // The field names, never the values. Google's players/me answer carries the gamer tag,
    // avatar URLs and profile settings, and none of that belongs in a log we only keep to
    // find out which key was missing.
    return failed(502, "no_player_id", { received: Object.keys(player ?? {}) });
  }
  // The gamer tag is a suggestion, not an identity: handle_new_user() sanitises it into the
  // shape profiles accepts and de-duplicates it against the board. Nothing here depends on it
  // being present or sane.
  //
  // Except in one way, which is why the fallback is spelled out rather than left to the
  // trigger. Handed an empty name, handle_new_user() falls back to the local part of the
  // email — and for these accounts that address is derived from the player ID, so a missing
  // gamer tag would publish `pgs-<playerId>` as the display name on a *public* leaderboard.
  // Play Games makes a gamer tag mandatory, so this should be unreachable; it is guarded
  // anyway, because the failure mode is leaking an identifier and the guard costs one line.
  // "Player" is what the trigger's own last resort uses, and it de-duplicates from there.
  const tag = typeof player?.displayName === "string" ? player.displayName.trim() : "";
  const gamerTag = tag.length > 0 ? tag : "Player";

  // ---- 3. find or create the account ---------------------------------------------------

  const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  // Deterministic, so a returning player resolves to the account they already have without a
  // lookup table of our own. The player id is Google's stable per-game identifier.
  const email = `pgs-${playerId.toLowerCase()}@${EMAIL_DOMAIN}`;

  const created = await admin.auth.admin.createUser({
    email,
    email_confirm: true,
    user_metadata: { display_name: gamerTag, pgs_player_id: playerId },
    app_metadata: { provider: "play_games", providers: ["play_games"], pgs_player_id: playerId },
  });

  // A createUser error is *held*, not judged. The obvious judging — match 422 / an "already
  // registered" message — is wrong twice over: the wording is GoTrue's to change, and two
  // devices minting the same player at once can both pass GoTrue's pre-insert existence
  // check, so the loser's failure is a 500 duplicate-key, not the 422 the heuristic expects.
  // generateLink below is the authoritative existence check — it fails for a user that does
  // not exist — so the held error is only ever reported if that fails too.
  const createError = created.error;
  if (createError) console.warn("play-games-auth: createUser did not create", createError);

  // ---- 4. mint a session for it ---------------------------------------------------------

  // There is no admin "issue a session" call, so this is the supported way round: generate the
  // one-time token a magic link would have carried, then spend it here rather than mailing it.
  // The address is unroutable, so no mail is sent and none could be received.
  //
  // Two attempts, for one precise reason: GoTrue keeps a single recovery-token slot per user,
  // so a second device minting the same player between this device's generateLink and its
  // verifyOtp clobbers the hash and the verify fails as spent/expired. The account exists
  // and is healthy — the retry mints a fresh hash and succeeds. Everything here is
  // idempotent per player, which is what makes the retry safe.
  const anon = createClient(SUPABASE_URL, ANON_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  let session: Session | null = null;
  for (let attempt = 0; attempt < 2 && !session; attempt++) {
    const link = await admin.auth.admin.generateLink({ type: "magiclink", email });
    if (link.error) {
      // The user this address names does not exist (or GoTrue is down). If createUser also
      // failed, *that* is the story; report both.
      return failed(500, "link_failed", { linkError: link.error, createError });
    }
    const tokenHash = link.data?.properties?.hashed_token;
    if (!tokenHash) return failed(500, "no_token_hash", "generateLink returned no hashed_token");

    const verified = await anon.auth.verifyOtp({ type: "email", token_hash: tokenHash });
    if (verified.error) {
      if (attempt === 0) {
        console.warn("play-games-auth: verifyOtp failed, retrying once", verified.error);
        continue;
      }
      return failed(500, "verify_failed", verified.error);
    }
    session = verified.data?.session ?? null;
  }
  if (!session) return failed(500, "no_session", "verifyOtp returned no session");

  // ---- 5. make the provider stamp true, for every mint ----------------------------------

  // The session names the user, whichever path produced it — which is what lets these
  // corrections run unconditionally instead of only on first creation.
  //
  // Both are load-bearing, not cosmetic. GoTrue inserts the auth.users row with
  // app_metadata.provider = 'email' and merges ours in afterwards, so handle_new_user() —
  // an AFTER INSERT trigger reading raw_app_meta_data at that instant — writes
  // profiles.provider = 'email' for every account this function creates. The app decides
  // from exactly these fields whether to offer a password and whether to show the derived
  // address as if it were a mailbox; an account left mislabelled would offer a password
  // reset that emails a .invalid address — undeliverable, and a waste of the shared
  // email-send budget real signups depend on. So a failed correction fails the mint: the
  // player retries and the retry repairs it, rather than adopting a mislabelled account.
  // For a returning, already-correct player both are idempotent no-ops.
  const userId = session.user?.id;
  if (!userId) return failed(500, "no_user_id", "verifyOtp session carried no user id");

  const stampedMeta = { provider: "play_games", providers: ["play_games"], pgs_player_id: playerId };
  const stamped = await admin.auth.admin.updateUserById(userId, { app_metadata: stampedMeta });
  if (stamped.error) return failed(500, "stamp_failed", stamped.error);

  const profile = await admin.from("profiles").update({ provider: "play_games" }).eq("id", userId);
  if (profile.error) return failed(500, "profile_stamp_failed", profile.error);

  // The session was minted *before* the stamp, so on a first mint its user object still says
  // 'email'. The app reads provider straight out of this response — hand it the truth the
  // stamp just wrote rather than the snapshot from before it.
  const user = { ...session.user, app_metadata: { ...session.user.app_metadata, ...stampedMeta } };

  // GoTrue's own token shape, so the app parses this with the same Session.fromTokenResponse
  // it uses for every other grant. No second parser, no second set of edge cases.
  return json(200, {
    access_token: session.access_token,
    refresh_token: session.refresh_token,
    expires_in: session.expires_in,
    token_type: "bearer",
    user,
  });
});
