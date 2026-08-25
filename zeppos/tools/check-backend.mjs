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

/** The side service's `fetch` shape, on top of Node's. */
async function sideFetch({ url, method, headers, body }) {
  const response = await globalThis.fetch(url, { method, headers, body })
  return {
    status: response.status,
    statusText: response.statusText,
    body: await response.text(),
  }
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

  // The plausibility constraints have to actually refuse an impossible run.
  const tooManyHits = await api.submit(1000, 5000)
  check('an impossible hit count is refused', tooManyHits.saved === false, tooManyHits.message || '')

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
