/**
 * The Supabase client, and every rule about what a response means.
 *
 * Deliberately free of `@zeppos/*` and `@zos/*`: it is handed a `fetch` and a key/value
 * `storage` and does the rest itself. Two things follow from that. It can be exercised
 * against the real project from Node - `node tools/check-backend.mjs` does exactly that,
 * which is the only way the sign-in path gets tested at all, because a Zepp OS settings
 * page cannot be typed into by anything but a person. And the side service beside it is
 * left as what it should be: message plumbing, twenty lines of it.
 *
 * The three jobs:
 *
 *   sign-in     driven from the settings page, which writes credentials into
 *               `KEY_AUTH_REQUEST` and reads the outcome back out of `KEY_AUTH_STATUS`.
 *               The password is spent on a token and the key is cleared; it is never
 *               stored, never sent to the watch, and never kept after the attempt.
 *
 *   sign-up     the same road, one field longer. The project has email confirmation on,
 *               so this ends at "check your inbox" rather than at a session - see `signUp`.
 *
 *   the board   `zepp_leaderboard` / `zepp_my_standing`, the Zepp edition's own RPCs.
 *               They are deliberately not the phone's: a 3x3 board tapped with one finger
 *               and a 4x4 board tapped with two thumbs do not produce comparable
 *               milliseconds, so the two boards never mix (see the migration).
 *
 *   submitting  a POST to `zepp_scores`, refused unless somebody is signed in.
 *
 * Token refresh is explicit rather than clever: the access token is refreshed when it is
 * inside a minute of expiry, and a 401 against a token we believed was current forces one
 * more refresh before the call is retried once. A definitive rejection of the *refresh*
 * token (400..403) is the only thing that clears the session - an outage or a rate limit
 * must not read as "signed out", or a bad minute of connectivity costs the player their
 * sign-in.
 */

import {
  KEY_AUTH_REQUEST,
  KEY_AUTH_STATUS,
  KEY_SESSION,
  AUTH_SIGNED_OUT,
  AUTH_WORKING,
  AUTH_SIGNED_IN,
  AUTH_CONFIRM,
  AUTH_RESET_SENT,
  AUTH_ERROR,
  SCOPE_ALL_TIME,
} from './protocol.js'
import {
  normalizeName,
  validateEmail,
  validateName,
  validatePassword,
  PASSWORD_HINT,
} from './credentials.js'

/** Refresh this far ahead of expiry, so a call never starts on a token about to die. */
const EXPIRY_SKEW_MS = 60000

const BOARD_LIMIT = 20

/** Mirrors zepp_scores_millis_plausible. */
const MAX_MILLIS = 600000

/**
 * Where an emailed link lands - both kinds of link, one destination.
 *
 * Not `site_url`, which is `whaaack://auth` - a deep link only the Android app can answer.
 * That is the right destination for the phone game, where the app is by definition
 * installed, and the wrong one here: an account can be created, confirmed and recovered
 * start to finish from this settings page by somebody who has never installed the Android
 * version and has no reason to. Handing them a link their phone cannot open is a dead end
 * at the exact moment they are trying to get in.
 *
 * Confirmation was the easy half - the page only has to say the address is verified, and
 * it needs nothing installed to say it. Recovery stayed broken longer, because setting a
 * new password needs somewhere to *type* one, and for a while the only thing in this
 * project that offered that was the Android app. It is not any more:
 * `website/auth/index.html` grew a form that spends the recovery token the link arrives
 * with, so this one constant now covers both flows.
 *
 * The phone game is untouched and keeps its own deep link - `AuthRepository`'s
 * `sendPasswordReset` still asks for `whaaack://auth`, which is the right destination for
 * a caller that *is* the app. Which flow a link belongs to is decided per request by this
 * parameter, so no project setting changes for either.
 *
 * The exact spelling matters. GoTrue matches `redirect_to` against
 * `additional_redirect_urls` and *silently substitutes `site_url`* when it does not match -
 * no error, no clue, just a link that goes back to being an Android deep link. This string
 * is already on that list in `supabase/config.toml`; a trailing slash would not be.
 */
const AUTH_REDIRECT = 'https://idct.tech/whaaack/auth'

/**
 * @param url            the project's base URL
 * @param anonKey        the publishable client key
 * @param fetch          `({url, method, headers, body, timeout}) => {status, body}`
 * @param storage        `{ getItem, setItem, removeItem }`
 */
export function createBackend({ url, anonKey, fetch, storage }) {
  // ---------------------------------------------------------------- stored session

  function readJson(key) {
    const raw = storage.getItem(key)
    if (!raw) return null
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw
    } catch (e) {
      return null
    }
  }

  function writeJson(key, value) {
    storage.setItem(key, JSON.stringify(value))
  }

  function loadSession() {
    const session = readJson(KEY_SESSION)
    if (!session || !session.accessToken || !session.refreshToken) return null
    return session
  }

  function clearSession() {
    storage.removeItem(KEY_SESSION)
  }

  function setStatus(status) {
    writeJson(KEY_AUTH_STATUS, status)
  }

  // ----------------------------------------------------------------------- transport

  /**
   * One HTTP call against the project, with the response body already parsed.
   *
   * Never throws for a non-2xx: the status comes back alongside the body so each caller
   * can decide what it means. A 401 on a score submission and a 401 on a board read want
   * very different reactions, and an exception loses the distinction.
   */
  async function call(path, options) {
    const opts = options || {}
    const headers = {
      apikey: anonKey,
      Authorization: 'Bearer ' + (opts.token || anonKey),
      'Content-Type': 'application/json',
    }
    if (opts.prefer) headers.Prefer = opts.prefer

    const response = await fetch({
      url: url + path,
      method: opts.method || 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      timeout: 20000,
    })

    const raw = response && response.body
    let parsed = null
    if (raw) {
      if (typeof raw === 'string') {
        try {
          parsed = JSON.parse(raw)
        } catch (e) {
          parsed = null
        }
      } else {
        parsed = raw
      }
    }
    return { status: (response && response.status) || 0, body: parsed }
  }

  // -------------------------------------------------------------------------- auth

  function sessionFromTokenResponse(body, previous) {
    if (!body || !body.access_token || !body.refresh_token) return null
    const expiresInMs = (body.expires_in ? body.expires_in : 3600) * 1000
    return {
      accessToken: body.access_token,
      refreshToken: body.refresh_token,
      expiresAt: Date.now() + expiresInMs,
      userId: (body.user && body.user.id) || (previous && previous.userId) || '',
      email: (body.user && body.user.email) || (previous && previous.email) || '',
      // The profiles table is the authority on the display name, not user_metadata: a
      // rename PATCHes profiles only, and the signup trigger de-duplicates. Carrying the
      // cached name forward keeps a routine refresh from regressing it.
      name: (previous && previous.name) || '',
    }
  }

  /** The display name the boards will show, read from the row RLS lets a player see. */
  async function fetchDisplayName(session) {
    const result = await call(
      '/rest/v1/profiles?select=display_name&id=eq.' + session.userId,
      { token: session.accessToken },
    )
    if (result.status >= 200 && result.status < 300 && Array.isArray(result.body)) {
      const row = result.body[0]
      if (row && row.display_name) return row.display_name
    }
    return ''
  }

  async function signIn(email, password) {
    setStatus({ state: AUTH_WORKING, email })

    const result = await call('/auth/v1/token?grant_type=password', {
      method: 'POST',
      body: { email, password },
    })

    if (result.status < 200 || result.status >= 300) {
      const code = result.body && (result.body.error_code || result.body.error)

      // An address that exists but has never been confirmed also answers 400, and telling
      // that player their password is wrong is the worst thing this page can say: it is
      // false, and it sends somebody who has just registered back round the signup form.
      // Send them to the inbox they are actually waiting on.
      if (code === 'email_not_confirmed') {
        setStatus({ state: AUTH_CONFIRM, email })
        return { signedIn: false, unconfirmed: true }
      }

      // `invalid_credentials` is by far the commonest case and deserves plain words;
      // anything else is reported as GoTrue described it, since its messages are written
      // for people rather than for a schema.
      const message =
        code === 'invalid_credentials' || result.status === 400
          ? 'That email and password do not match an account.'
          : errorMessage(result, 'Could not sign in (' + result.status + ').')
      setStatus({ state: AUTH_ERROR, email, message })
      return { signedIn: false, message }
    }

    const session = sessionFromTokenResponse(result.body, null)
    if (!session) {
      const message = 'The sign-in service returned a response with no session in it.'
      setStatus({ state: AUTH_ERROR, email, message })
      return { signedIn: false, message }
    }

    session.name = (await fetchDisplayName(session)) || ''
    writeJson(KEY_SESSION, session)
    setStatus({ state: AUTH_SIGNED_IN, name: session.name, email: session.email })
    return { signedIn: true, name: session.name, email: session.email }
  }

  /**
   * Creates an account, or explains why it could not.
   *
   * The project has `enable_confirmations = true`, so the ordinary outcome is **not** a
   * session: GoTrue creates the user, mails a link, and answers 200 with a user and no
   * tokens. So this does not sign anybody in, and says so - the player confirms the address
   * and then signs in with the password they just chose, on this same page.
   *
   * The one case that needs care is an address that already has a confirmed account. With
   * confirmations on, GoTrue does *not* answer 422 for that - saying so would confirm the
   * address exists to anyone who cares to ask. It answers 200 with an obfuscated user whose
   * `identities` array is empty, and sends no mail. Read naively that is indistinguishable
   * from success, and the player would be told to watch an inbox nothing was ever going to
   * arrive in. The empty array is GoTrue's own documented tell, and we are allowed to use
   * it where an attacker is not: this caller just proved they can send *us* the address
   * either way.
   *
   * The display name is checked here rather than left to the signup trigger, which
   * sanitises instead of refusing - a name of nothing but non-ASCII sails through and lands
   * as "Player", and somebody who typed one deserves to be told before the account exists
   * rather than to discover it on the leaderboard. Uniqueness is still the trigger's to
   * resolve, and it does so by appending a number.
   */
  async function signUp(email, password, displayName) {
    const complaint =
      validateEmail(email) || validatePassword(password) || validateName(displayName)
    if (complaint) {
      setStatus({ state: AUTH_ERROR, email, message: complaint })
      return { created: false, message: complaint }
    }

    setStatus({ state: AUTH_WORKING, email })

    const result = await call('/auth/v1/signup?redirect_to=' + encodeURIComponent(AUTH_REDIRECT), {
      method: 'POST',
      body: {
        email: String(email).trim(),
        password,
        data: { display_name: normalizeName(displayName) },
      },
    })

    if (result.status < 200 || result.status >= 300) {
      const code = result.body && (result.body.error_code || result.body.error)
      const message =
        code === 'user_already_exists' || result.status === 422
          ? 'That email already has an account. Sign in instead.'
          : errorMessage(result, 'Could not create the account (' + result.status + ').')
      setStatus({ state: AUTH_ERROR, email, message })
      return { created: false, message }
    }

    const identities = result.body && result.body.identities
    if (Array.isArray(identities) && identities.length === 0) {
      const message = 'That email already has an account. Sign in instead.'
      setStatus({ state: AUTH_ERROR, email, message })
      return { created: false, message }
    }

    // Confirmations off, or an already-confirmed address: a session came back, so there is
    // nothing left to ask of the player.
    const session = sessionFromTokenResponse(result.body, null)
    if (session) {
      session.name = (await fetchDisplayName(session)) || normalizeName(displayName)
      writeJson(KEY_SESSION, session)
      setStatus({ state: AUTH_SIGNED_IN, name: session.name, email: session.email })
      return { created: true, signedIn: true, name: session.name }
    }

    setStatus({ state: AUTH_CONFIRM, email, name: normalizeName(displayName) })
    return { created: true, signedIn: false }
  }

  /**
   * Sets a new password on the session that is already signed in.
   *
   * No current-password field, and none is asked for. The project runs with
   * `secure_password_change = false` (see supabase/config.toml, which explains why at
   * length), so GoTrue accepts a bare `PUT /auth/v1/user` on any live session - and the
   * phone game's own `AuthRepository.updatePassword` does exactly this. Asking here for a
   * password the server will not check would be theatre, and on a page with no masked
   * input it would be theatre performed in the clear.
   *
   * There is no confirm-it-twice field either, for that same reason: the Settings API has
   * no masked input, so the new password is legible while it is typed. A second field
   * guards against a typo you cannot see, and this one you can.
   *
   * The status stays `signed_in` throughout. Reporting progress as `working` or a refusal
   * as `error` would swap the account card for a sign-in form over a session that never
   * stopped being valid, and the player would think they had been signed out by trying to
   * change their password.
   */
  async function changePassword(newPassword) {
    const session = loadSession()
    if (!session) {
      setStatus({ state: AUTH_SIGNED_OUT })
      return { changed: false, reason: 'signed_out' }
    }

    /**
     * A signed-in status carrying the result of something that has just happened.
     *
     * Stamped, and that stamp is not decoration. `KEY_AUTH_STATUS` is *storage*: it
     * outlives the page, the app, and the phone being switched off, and the settings page
     * rebuilds straight out of it. With no time on it, "Password changed." stops being a
     * report and becomes a permanent green line greeting the player under an empty field
     * every time they open the page for the rest of the account's life. A refusal is a
     * permanent red one, and a `busy` that never got its answer - because the phone was
     * off when the request went out - is a button that says "Working…" for ever.
     */
    const report = (extra) =>
      Object.assign(
        { state: AUTH_SIGNED_IN, name: session.name || '', email: session.email || '' },
        { noticeAt: Date.now() },
        extra,
      )

    const complaint = validatePassword(newPassword)
    if (complaint) {
      setStatus(report({ problem: complaint }))
      return { changed: false, message: complaint }
    }

    setStatus(report({ busy: true }))

    const result = await authorized('/auth/v1/user', {
      method: 'PUT',
      body: { password: newPassword },
    })

    if (result.unauthenticated) {
      // `authorized` only reports this once the refresh token is gone too, and it has
      // already cleared the session by then.
      setStatus({ state: AUTH_SIGNED_OUT, message: 'Signed out - please sign in again.' })
      return { changed: false, reason: 'signed_out' }
    }

    if (result.status < 200 || result.status >= 300) {
      const code = result.body && (result.body.error_code || result.body.error)
      const message =
        code === 'same_password'
          ? 'That is already your password. Choose a different one.'
          : code === 'weak_password'
            ? PASSWORD_HINT
            : errorMessage(result, 'Could not change the password just now.')
      setStatus(report({ problem: message }))
      return { changed: false, message }
    }

    // Nothing to store. The response carries a user rather than tokens, and with
    // `secure_password_change` off the session that made the change stays valid - so the
    // player is still signed in on both the phone and the watch, which is what they meant.
    setStatus(report({ notice: 'Password changed.' }))
    return { changed: true }
  }

  /**
   * Asks GoTrue to mail a reset link.
   *
   * Deliberately says the same thing whatever comes back, because GoTrue does: a recovery
   * request for an address it has never seen is answered exactly like one for an address
   * it knows, and that is the point - anything else here would turn this form into a way
   * of asking whether somebody has an account. So the success wording is conditional
   * ("if that address has an account") rather than a claim about what was sent.
   *
   * `redirect_to` is the web page rather than the phone game's deep link, and that is the
   * whole difference for a watch-only player. The link has to land somewhere that can take
   * a new password, and `website/auth/index.html` is now somewhere that can: it reads the
   * recovery token out of the fragment GoTrue redirects it with and PUTs the new password
   * itself, so somebody who has never installed the Android app finishes the flow in their
   * phone's browser. The phone game still sends its own resets to `whaaack://auth`.
   */
  async function requestPasswordReset(email) {
    const address = String(email || '').trim()
    const complaint = validateEmail(address)
    if (complaint) {
      setStatus({ state: AUTH_ERROR, email: address, message: complaint })
      return { sent: false, message: complaint }
    }

    setStatus({ state: AUTH_WORKING, email: address })

    const result = await call('/auth/v1/recover?redirect_to=' + encodeURIComponent(AUTH_REDIRECT), {
      method: 'POST',
      body: { email: address },
    })

    if (result.status < 200 || result.status >= 300) {
      const code = result.body && (result.body.error_code || result.body.error)
      // `max_frequency = "60s"`, and the project-wide hourly budget on top of it. Both come
      // back as a rate limit, and both mean "wait", which is advice a player can act on -
      // unlike GoTrue's own wording, which counts down in seconds it does not always give.
      const message =
        code === 'over_email_send_rate_limit'
          ? 'A link was requested very recently. Give it a minute and try again.'
          : errorMessage(result, 'Could not send the reset link just now.')
      setStatus({ state: AUTH_ERROR, email: address, message })
      return { sent: false, message }
    }

    setStatus({ state: AUTH_RESET_SENT, email: address })
    return { sent: true }
  }

  async function signOut() {
    const session = loadSession()
    // Best effort: the local session is cleared either way. A revoke that fails leaves a
    // token alive until it expires, which is a far smaller problem than a sign-out button
    // that appears not to work.
    if (session) {
      try {
        await call('/auth/v1/logout', {
          method: 'POST',
          token: session.accessToken,
          body: {},
        })
      } catch (e) {
        // Deliberately ignored - see above.
      }
    }
    clearSession()
    setStatus({ state: AUTH_SIGNED_OUT })
  }

  /**
   * Mints a new access token from the refresh token.
   *
   * `rejected` switches this from "refresh because it looks expired" to "refresh because
   * the server said no", where the stored expiry cannot be trusted - disagreeing with it
   * is the whole reason we are here. Returns null only when there is genuinely nothing
   * left to refresh; a failure that says nothing about the token is thrown, so no caller
   * can mistake an outage for a dead session.
   */
  async function refresh(rejected) {
    const session = loadSession()
    if (!session) return null
    if (rejected) {
      if (session.accessToken !== rejected) return session
    } else if (session.expiresAt - EXPIRY_SKEW_MS > Date.now()) {
      return session
    }

    const result = await call('/auth/v1/token?grant_type=refresh_token', {
      method: 'POST',
      body: { refresh_token: session.refreshToken },
    })

    if (result.status >= 400 && result.status <= 403) {
      // The refresh token itself is dead. This is the only path that signs a player out.
      clearSession()
      setStatus({ state: AUTH_SIGNED_OUT, message: 'Signed out - please sign in again.' })
      return null
    }
    if (result.status < 200 || result.status >= 300) {
      throw new Error(errorMessage(result, 'Could not reach the leaderboard.'))
    }

    const next = sessionFromTokenResponse(result.body, session)
    if (!next) {
      // A 2xx carrying nothing usable says nothing about the refresh token either, so the
      // session is deliberately left alone.
      throw new Error('The sign-in service returned a response with no session in it.')
    }

    // Never resurrect a session that was signed out while this was on the wire.
    //
    // The two are genuinely concurrent: `onRequest` fires `spendPendingRequest()` without
    // awaiting it and then starts the authorized call in the same turn, so a Sign out
    // queued on the phone and a refresh driven by the watch interleave at the `fetch`
    // suspension point. Writing unconditionally puts a freshly minted access token back
    // into a store the player just emptied — and a JWT is not revoked server-side, so it
    // stays good for its full hour. The watch would go on showing them signed in, and
    // submitting every later run to the account they had just left.
    //
    // The refresh token is the identity check: this save is only valid as the successor of
    // the exact session that was spent to mint it. The phone client carries the same guard
    // and the same reasoning (SupabaseClient.refreshSession).
    const current = loadSession()
    if (!current || current.refreshToken !== session.refreshToken) return null

    writeJson(KEY_SESSION, next)
    return next
  }

  /** An authenticated call that survives a token the server has stopped believing in. */
  async function authorized(path, options) {
    const session = loadSession()
    if (!session) return { status: 401, body: null, unauthenticated: true }

    let fresh = await refresh()
    if (!fresh) return { status: 401, body: null, unauthenticated: true }

    let result = await call(path, Object.assign({}, options, { token: fresh.accessToken }))
    if (result.status === 401) {
      fresh = await refresh(fresh.accessToken)
      if (!fresh) return { status: 401, body: null, unauthenticated: true }
      result = await call(path, Object.assign({}, options, { token: fresh.accessToken }))
    }
    return result
  }

  // ------------------------------------------------------------------------ the board

  function authSnapshot() {
    const session = loadSession()
    if (!session) return { signedIn: false }
    return { signedIn: true, name: session.name || '', email: session.email || '' }
  }

  async function board(scope) {
    const rows = await call('/rest/v1/rpc/zepp_leaderboard', {
      method: 'POST',
      body: { p_scope: scope || SCOPE_ALL_TIME, p_limit: BOARD_LIMIT },
    })
    if (rows.status < 200 || rows.status >= 300) {
      // Deliberately not `errorMessage`: a PostgREST failure here names our functions and
      // tables, and none of that is a sentence to put on somebody's wrist. It is logged
      // instead, where a developer can find it and a player cannot.
      console.log('zepp board failed: ' + rows.status + ' ' + JSON.stringify(rows.body))
      throw new Error('The leaderboard is not answering right now.')
    }

    const rankings = (Array.isArray(rows.body) ? rows.body : []).map((row) => ({
      rank: Number(row.rank) || 0,
      name: row.display_name || '',
      millis: Number(row.millis) || 0,
    }))

    // The player's own standing may sit far below the page above, so it is fetched
    // separately - and only when there is a session, since the RPC is authenticated-only.
    let me = null
    if (loadSession()) {
      const standing = await authorized('/rest/v1/rpc/zepp_my_standing', {
        method: 'POST',
        body: { p_scope: scope || SCOPE_ALL_TIME },
      })
      if (standing.status >= 200 && standing.status < 300 && Array.isArray(standing.body)) {
        const row = standing.body[0]
        if (row) {
          me = {
            rank: Number(row.rank) || 0,
            millis: Number(row.millis) || 0,
            totalPlayers: Number(row.total_players) || 0,
          }
        }
      }
    }

    return { rows: rankings, me, signedIn: !!loadSession() }
  }

  async function submit(millis, hits) {
    if (!loadSession()) return { saved: false, reason: 'signed_out' }

    const result = await authorized('/rest/v1/zepp_scores', {
      method: 'POST',
      // Clamping rather than sending it through matters: the server *refuses* an
      // over-ceiling row, and a refusal is reported to the player as a score that failed
      // to save. Nothing the engine can produce comes near ten minutes, so this only ever
      // fires on a clock fault - where losing the excess beats losing the run.
      body: {
        millis: Math.max(0, Math.min(MAX_MILLIS, Math.round(millis))),
        hits: Math.max(0, Math.round(hits)),
      },
      prefer: 'return=minimal',
    })

    if (result.unauthenticated) return { saved: false, reason: 'signed_out' }
    if (result.status < 200 || result.status >= 300) {
      return { saved: false, reason: 'rejected', message: refusalMessage(result) }
    }
    return { saved: true }
  }

  /**
   * What to tell a player whose run the server would not take.
   *
   * Never the server's own words, with one exception. A plausibility constraint arrives as
   * `new row for relation "zepp_scores" violates check constraint "zepp_scores_hits_floor"`
   * — which names our schema, blames the player for nothing they can act on, and does not
   * even say what was wrong. The phone client made the same call for the same reason and
   * replaces every submission failure wholesale.
   *
   * The exception is the flood stop, which is the one refusal the migration wrote a
   * sentence for: `raise exception 'score_rate_limited' using hint = '...'`. PostgREST puts
   * the exception name in `message` and the sentence in `hint`, so taking `message` first —
   * as the generic error reader does — would print the internal name and throw away the
   * only human-readable thing in the envelope.
   */
  function refusalMessage(result) {
    const body = (result && result.body) || {}
    console.log('zepp submit refused: ' + result.status + ' ' + JSON.stringify(body))
    if (body.message === 'score_rate_limited') {
      return body.hint || 'Too many runs at once — give it a minute.'
    }
    return "That run couldn't be recorded on the leaderboard."
  }

  /**
   * Reads a sign-in or sign-out request out of storage and acts on it, once.
   *
   * The request is deleted before it is acted on rather than after, for two reasons: a
   * password must not sit in storage a moment longer than it takes to spend it, and both
   * of the callers can fire for the same write, so whoever gets there first has to take it
   * off the table for the other.
   *
   * Returns a promise so a test can wait for it; the service deliberately does not.
   */
  function spendPendingRequest() {
    const request = readJson(KEY_AUTH_REQUEST)
    if (!request || !request.action) return Promise.resolve(null)
    storage.removeItem(KEY_AUTH_REQUEST)

    if (request.action === 'signout') {
      return signOut().catch(() => {
        clearSession()
        setStatus({ state: AUTH_SIGNED_OUT })
      })
    }

    if (request.action === 'reset') {
      const address = String(request.email || '').trim()
      return requestPasswordReset(address).catch((error) => {
        setStatus({
          state: AUTH_ERROR,
          email: address,
          message: describe(error, 'Could not reach the sign-in service.'),
        })
      })
    }

    if (request.action === 'password') {
      return changePassword(String(request.password || '')).catch((error) => {
        const session = loadSession()
        // Reported inside the account card rather than as an `error` state, which would
        // take the player out of it. See `changePassword`.
        setStatus({
          state: session ? AUTH_SIGNED_IN : AUTH_SIGNED_OUT,
          name: session ? session.name || '' : undefined,
          email: session ? session.email || '' : undefined,
          noticeAt: Date.now(),
          problem: describe(error, 'Could not reach the sign-in service.'),
        })
      })
    }

    if (request.action !== 'signin' && request.action !== 'signup') {
      return Promise.resolve(null)
    }

    const email = String(request.email || '').trim()
    const password = String(request.password || '')
    if (!email || !password) {
      setStatus({
        state: AUTH_ERROR,
        email,
        message: 'Enter both an email address and a password.',
      })
      return Promise.resolve(null)
    }

    const attempt =
      request.action === 'signup'
        ? signUp(email, password, request.name)
        : signIn(email, password)

    return attempt.catch((error) => {
      setStatus({
        state: AUTH_ERROR,
        email,
        message: describe(error, 'Could not reach the sign-in service.'),
      })
    })
  }

  return {
    signIn,
    signUp,
    signOut,
    changePassword,
    requestPasswordReset,
    refresh,
    authSnapshot,
    board,
    submit,
    spendPendingRequest,
    loadSession,
  }
}

/**
 * GoTrue and PostgREST disagree about the shape of an error envelope, so try both, and
 * fall back to something a player can act on rather than a bare status code.
 */
function errorMessage(result, fallback) {
  const body = result && result.body
  if (body) {
    const message =
      body.error_description || body.msg || body.message || body.hint || body.error
    if (message && typeof message === 'string') return message
  }
  return fallback
}

export function describe(error, fallback) {
  if (!error) return fallback
  if (typeof error === 'string') return error
  if (error.message) return error.message
  return fallback
}
