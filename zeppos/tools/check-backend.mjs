/**
 * Exercises `shared/supabase.js` against the real project, from Node.
 *
 *     node tools/check-backend.mjs                       # anonymous checks only
 *     node tools/check-backend.mjs you@example.com pass  # the whole sign-in path too
 *
 * This exists because the sign-in path cannot be tested any other way. It starts in a Zepp
 * OS settings page, which runs inside the phone's Zepp app and accepts input from a person
 * and from nothing else - no synthetic keystroke reaches it, on a simulator or a phone. So
 * the client was written to depend on a `fetch` and a key/value store and on nothing from
 * Zepp OS at all, and this harness supplies both: Node's `fetch`, wrapped to the shape the
 * side service's one has, and a plain object for storage.
 *
 * With credentials it also exercises the signed-in half: it POSTs one score, because "does
 * a real run reach the board" is the question worth answering, and it tries to sign up the
 * address a second time, because GoTrue deliberately makes that look like success and this
 * client has to see through it. The score is a real row on the real board; the millis are
 * deliberately tiny so it cannot displace anybody, and the second signup creates nothing.
 */

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createBackend } from '../shared/supabase.js'
import { validateName, validatePassword, validateEmail } from '../shared/credentials.js'
import {
  KEY_AUTH_REQUEST,
  KEY_AUTH_STATUS,
  KEY_SESSION,
  AUTH_RESET_SENT,
  SCOPE_ALL_TIME,
  SCOPE_WEEKLY,
} from '../shared/protocol.js'

const HERE = dirname(fileURLToPath(import.meta.url))

function secrets() {
  const source = readFileSync(resolve(HERE, '../shared/secrets.js'), 'utf8')
  const grab = (name) => {
    const match = source.match(new RegExp(`export const ${name} = '([^']*)'`))
    if (!match) throw new Error(`${name} missing - run python ../tools/sync_zepp_secrets.py`)
    return match[1]
  }
  return { url: grab('SUPABASE_URL'), anonKey: grab('SUPABASE_ANON_KEY') }
}

/**
 * The side service's `fetch` shape, on top of Node's, keeping the URLs it was asked for.
 *
 * The URLs matter here as much as the answers. `redirect_to` is where an emailed link
 * lands, GoTrue *silently* falls back to `site_url` when the value is not on the project's
 * allow list, and the whole failure is invisible from this side: the call succeeds, the
 * mail is sent, and the only symptom is a player tapping a link that opens an app they do
 * not have. Nothing but the outgoing URL can catch that.
 */
const called = []

async function sideFetch({ url, method, headers, body }) {
  called.push(url)
  const response = await globalThis.fetch(url, { method, headers, body })
  return {
    status: response.status,
    statusText: response.statusText,
    body: await response.text(),
  }
}

/** The last request whose path contains `fragment`, or undefined. */
function lastCall(fragment) {
  for (let i = called.length - 1; i >= 0; i--) {
    if (called[i].includes(fragment)) return called[i]
  }
  return undefined
}

function memoryStorage() {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, v),
    removeItem: (k) => map.delete(k),
    dump: () => Object.fromEntries(map),
  }
}

let failures = 0

function check(label, ok, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '  - ' + detail : ''}`)
  if (!ok) failures++
}

async function main() {
  const { url, anonKey } = secrets()
  const storage = memoryStorage()
  const api = createBackend({ url, anonKey, fetch: sideFetch, storage })

  console.log(`project ${url.replace('https://', '')}\n`)

  // ---------------------------------------------------------------- anonymous
  check('no session to start with', api.authSnapshot().signedIn === false)

  for (const scope of [SCOPE_ALL_TIME, SCOPE_WEEKLY]) {
    const board = await api.board(scope)
    check(
      `zepp_leaderboard(${scope}) answers anonymously`,
      Array.isArray(board.rows),
      `${board.rows.length} rows`,
    )
    check(`${scope}: no standing without a session`, board.me === null)
  }

  const refused = await api.submit(12345, 9)
  check('a run is refused when nobody is signed in', refused.saved === false && refused.reason === 'signed_out')

  // A malformed request must not be able to wedge the service.
  storage.setItem(KEY_AUTH_REQUEST, '{ not json')
  await api.spendPendingRequest()
  check('a malformed auth request is survivable', true)

  storage.setItem(KEY_AUTH_REQUEST, JSON.stringify({ action: 'signin', email: '', password: '' }))
  await api.spendPendingRequest()
  check(
    'an empty form is reported rather than ignored',
    JSON.parse(storage.getItem(KEY_AUTH_STATUS)).state === 'error',
  )
  check('the request is spent, not left lying about', storage.getItem(KEY_AUTH_REQUEST) === null)

  // ------------------------------------------------------------ signup validation

  // These never reach the network: a bad name or a weak password is refused here so the
  // player gets a sentence they can act on instead of a check-constraint name.
  check('a one-character name is refused', !!validateName('x'))
  check('a name that starts on punctuation is refused', !!validateName('-nick-'))
  check('a name of only non-ASCII is refused', !!validateName('李雷'))
  check('an ordinary name passes', validateName('Bartek') === null)
  check('a name with inner spaces passes', validateName('Jan  Kowalski') === null)
  check('a 24-character name passes', validateName('a'.repeat(24)) === null)
  check('a 25-character name is refused', !!validateName('a'.repeat(25)))
  check('a short password is refused', !!validatePassword('ab1'))
  check('a password with no digit is refused', !!validatePassword('abcdefghij'))
  check('a password with no letter is refused', !!validatePassword('1234567890'))
  check('a letters-and-digits password passes', validatePassword('orchard42') === null)
  check('a bare word is not an email', !!validateEmail('nobody'))
  check('an ordinary address passes', validateEmail('a@b.co') === null)

  const badName = await api.signUp('someone@example.invalid', 'orchard42', '-nick-')
  check('signUp refuses a bad name before the network', badName.created === false, badName.message)

  // -------------------------------------------------------- password, signed out
  //
  // Both of these have to refuse without a session, and refuse for the right reason. A
  // change-password that quietly did nothing would be the worst outcome: `authorized`
  // falls back to the anon key when there is no token, and PostgREST answers that with an
  // empty success rather than a 401 - which is exactly how the phone game once managed to
  // report a change of nothing as a change.
  const noSession = await api.changePassword('orchard42')
  check(
    'a password change is refused when nobody is signed in',
    noSession.changed === false && noSession.reason === 'signed_out',
    noSession.reason,
  )

  check('a malformed address never reaches the reset endpoint', !!validateEmail('nobody'))
  const badReset = await api.requestPasswordReset('nobody')
  check('and requestPasswordReset says so', badReset.sent === false, badReset.message)

  // The one call here that does go out. It mails nobody - there is no account on
  // example.invalid - but it proves the request shape, the redirect and the client's
  // reading of the answer, which is the part that would otherwise only ever run in front
  // of a player who had forgotten their password.
  storage.setItem(
    KEY_AUTH_REQUEST,
    JSON.stringify({ action: 'reset', email: 'nobody@example.invalid', at: 3 }),
  )
  await api.spendPendingRequest()
  const resetStatus = JSON.parse(storage.getItem(KEY_AUTH_STATUS))
  // Either outcome is correct. GoTrue answers a recovery for an address it has never seen
  // exactly as it answers one it knows, so `reset_sent` is the ordinary result - but the
  // project's own send-rate limit can refuse it, and that is a sentence a player can act on
  // rather than a failure of this client.
  check(
    'a reset request comes back with something a player can act on',
    resetStatus.state === AUTH_RESET_SENT || (resetStatus.state === 'error' && !!resetStatus.message),
    resetStatus.state + (resetStatus.message ? ': ' + resetStatus.message : ''),
  )
  const leakedByReset = /relation|constraint|violates|PGRST|supabase\.co|apikey/i
  check(
    'and does not leak the project on the way back',
    !leakedByReset.test(resetStatus.message || ''),
    resetStatus.message,
  )

  const recoverUrl = lastCall('/auth/v1/recover') || ''
  // The same three failures to tell apart as the confirmation check below, for the same
  // reason: GoTrue gives no sign when `redirect_to` is not on the allow list. It silently
  // substitutes `site_url`, which here would address the one link a locked-out player was
  // sent to an Android deep link they may well not be able to open.
  const resetWhere = !recoverUrl
    ? 'recover was never called'
    : recoverUrl.split('?')[1] || 'called with no redirect_to at all'
  check(
    'a reset link is addressed to the web page',
    recoverUrl.includes('redirect_to=' + encodeURIComponent('https://idct.tech/whaaack/auth')),
    resetWhere,
  )
  check(
    'and not to an app a watch player may never have installed',
    recoverUrl.length > 0 && !recoverUrl.includes(encodeURIComponent('whaaack://')),
    resetWhere,
  )

  // ------------------------------------------------ where a confirmation link lands
  //
  // Against a stubbed transport rather than the project, because the only way to make
  // GoTrue send a confirmation mail is to create an account, and this needs asking a
  // hundred times rather than once. The client takes its `fetch` as an argument precisely
  // so this is possible - what is being checked is the URL it builds, and that is decided
  // before anything leaves the machine.
  //
  // It is the one thing that would strand a watch-only player: a confirmation link has to
  // land on the web page, which needs nothing installed, rather than on the Android deep
  // link, which for them is a dead end on the last step of signing up. And GoTrue gives no
  // sign when it is wrong - an unlisted `redirect_to` is silently replaced with `site_url`,
  // the call succeeds, the mail goes out, and the only symptom is a player tapping a link
  // that opens nothing.
  {
    const seen = []
    const offline = createBackend({
      url,
      anonKey,
      storage: memoryStorage(),
      fetch: async ({ url: called }) => {
        seen.push(called)
        // A user with one identity: GoTrue's shape for "created, now go and confirm".
        return { status: 200, statusText: 'OK', body: JSON.stringify({ id: 'x', identities: [{}] }) }
      },
    })
    await offline.signUp('someone@example.invalid', 'orchard42', 'Someone')

    const signupUrl = seen.find((u) => u.includes('/auth/v1/signup')) || ''
    // Three different failures to tell apart, because the difference is the whole point:
    // no signup at all, a signup with no redirect on it, and a signup pointed somewhere
    // else. Collapsing them into one message costs an afternoon the next time this fails.
    const where = !signupUrl
      ? 'signup was never called'
      : signupUrl.split('?')[1] || 'called with no redirect_to at all'
    check(
      'a confirmation link is addressed to the web page',
      signupUrl.includes('redirect_to=' + encodeURIComponent('https://idct.tech/whaaack/auth')),
      where,
    )
    check(
      'and not to the app a watch cannot assume is there',
      signupUrl.length > 0 && !signupUrl.includes(encodeURIComponent('whaaack://')),
      where,
    )
  }

  // ------------------------------------------------- a result that expires
  //
  // KEY_AUTH_STATUS is storage: it outlives the page, the app and the phone being switched
  // off, and the settings page rebuilds straight out of it. So the outcome of a password
  // change has to carry the time it happened, or "Password changed." stops being a report
  // and becomes a permanent green line greeting the player under an empty field for the
  // rest of the account's life. Stubbed, because what is being checked is the shape of
  // what gets written, not the round trip.
  {
    const store = memoryStorage()
    store.setItem(
      KEY_SESSION,
      JSON.stringify({
        accessToken: 'a',
        refreshToken: 'r',
        expiresAt: Date.now() + 3600000,
        userId: 'u',
        name: 'Someone',
        email: 'someone@example.invalid',
      }),
    )
    const offline = createBackend({
      url,
      anonKey,
      storage: store,
      fetch: async () => ({ status: 200, statusText: 'OK', body: '{}' }),
    })

    const before = Date.now()
    const changed = await offline.changePassword('orchard42')
    const after = JSON.parse(store.getItem(KEY_AUTH_STATUS))

    check('a password change on a live session succeeds', changed.changed === true, changed.message)
    check(
      'and never leaves the account card',
      after.state === 'signed_in',
      after.state,
    )
    check(
      'and stamps its result so it cannot be shown as news for ever',
      typeof after.noticeAt === 'number' && after.noticeAt >= before,
      'noticeAt=' + after.noticeAt,
    )

    const weak = await offline.changePassword('short')
    const refused = JSON.parse(store.getItem(KEY_AUTH_STATUS))
    check('a refusal is stamped too', typeof refused.noticeAt === 'number', weak.message)
  }

  // ------------------------------------------------------------- wrong password
  storage.setItem(
    KEY_AUTH_REQUEST,
    JSON.stringify({ action: 'signin', email: 'nobody@example.invalid', password: 'nope', at: 1 }),
  )
  await api.spendPendingRequest()
  const rejected = JSON.parse(storage.getItem(KEY_AUTH_STATUS))
  check('bad credentials produce an error status', rejected.state === 'error', rejected.message)
  check('and no session', api.authSnapshot().signedIn === false)

  // ------------------------------------------------------------------- signed in
  const email = process.argv[2]
  const password = process.argv[3]
  if (!email || !password) {
    console.log('\n(pass an email and password to also check the signed-in and signup paths)')
    return failures
  }

  // Signing up an address that already has a confirmed account. Only meaningful with
  // credentials, because that is exactly the case GoTrue hides behind a 200 and an empty
  // `identities` array - and the one this client has to see through. It creates nothing
  // and sends no mail.
  const taken = await api.signUp(email, 'orchard42', 'Someone Else')
  check(
    'signing up an address that already exists says so',
    taken.created === false && /already has an account/.test(taken.message || ''),
    taken.message,
  )
  check('and did not sign anybody in', api.authSnapshot().signedIn === false)

  // The same assertion against the real project, now that a signup has actually gone out
  // over the wire rather than into a stub.
  const liveSignup = lastCall('/auth/v1/signup') || ''
  check(
    'the live signup carried the web redirect too',
    liveSignup.includes('redirect_to=' + encodeURIComponent('https://idct.tech/whaaack/auth')),
    liveSignup.split('?')[1],
  )

  storage.setItem(
    KEY_AUTH_REQUEST,
    JSON.stringify({ action: 'signin', email, password, at: 2 }),
  )
  await api.spendPendingRequest()

  const status = JSON.parse(storage.getItem(KEY_AUTH_STATUS))
  check('sign-in succeeds', status.state === 'signed_in', status.message || status.name)
  if (status.state !== 'signed_in') return failures

  const session = JSON.parse(storage.getItem(KEY_SESSION))
  check('a display name came back from profiles', !!session.name, session.name)
  check('the password was not stored anywhere', !JSON.stringify(storage.dump()).includes(password))

  const saved = await api.submit(4321, 3)
  check('a run reaches zepp_scores', saved.saved === true, saved.message || '')

  const board = await api.board(SCOPE_ALL_TIME)
  check('the board now has the run', board.rows.length > 0, `${board.rows.length} rows`)
  check('and a standing for this player', board.me !== null, board.me ? `rank ${board.me.rank} of ${board.me.totalPlayers}` : '')

  // The plausibility constraints have to actually refuse an impossible run...
  const tooManyHits = await api.submit(1000, 5000)
  check('an impossible hit count is refused', tooManyHits.saved === false, tooManyHits.message || '')

  // ...and the refusal must not arrive as the constraint's own name. Postgres answers a
  // 23514 with `new row for relation "zepp_scores" violates check constraint
  // "zepp_scores_hits_floor"`, which names our schema and tells a player nothing they can
  // act on. Whatever reaches the watch has to be a sentence written for them.
  const leaked = /relation|constraint|zepp_scores|violates|null value|PGRST|P0001/i
  check(
    'and does not name our schema on the way back',
    !leaked.test(tooManyHits.message || ''),
    tooManyHits.message,
  )

  // ------------------------------------------------------ changing the password
  //
  // The weak one never leaves the machine. The second is the *current* password, set
  // again: it is the only way to prove an authenticated `PUT /auth/v1/user` really reaches
  // GoTrue without leaving the account with a password this script chose. Either answer
  // proves it - GoTrue either accepts the no-op or refuses it as `same_password` - and the
  // account ends the run with the password it started with.
  const weak = await api.changePassword('short')
  check('a weak new password is refused before the network', weak.changed === false, weak.message)
  check('and the player is still signed in', api.authSnapshot().signedIn === true)

  const same = await api.changePassword(password)
  check(
    'setting the password reaches GoTrue',
    same.changed === true || /already your password/.test(same.message || ''),
    same.changed ? 'accepted' : same.message,
  )
  const leakedByChange = /relation|constraint|violates|PGRST|supabase\.co|apikey/i
  check('and refuses in words rather than in error codes', !leakedByChange.test(same.message || ''), same.message)

  const afterChange = JSON.parse(storage.getItem(KEY_AUTH_STATUS))
  check(
    'a password change never drops the player out of the account card',
    afterChange.state === 'signed_in',
    afterChange.state,
  )

  await api.signOut()
  check('sign-out clears the session', api.authSnapshot().signedIn === false)

  return failures
}

main()
  .then((count) => {
    console.log(count ? `\n${count} check(s) failed` : '\nall checks passed')
    process.exit(count ? 1 : 0)
  })
  .catch((error) => {
    console.error('\nharness error:', error)
    process.exit(2)
  })
