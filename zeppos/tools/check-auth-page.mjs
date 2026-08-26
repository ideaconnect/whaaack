/**
 * Checks the page an emailed link lands on: website/auth/index.html.
 *
 *     node tools/check-auth-page.mjs
 *
 * That page is the watch edition's whole answer to "I have never installed the Android
 * app" — it confirms an address, and it takes a new password off a reset link. Nothing
 * else in this repository covers it, and it is exactly the sort of page that is only ever
 * exercised by somebody who is already locked out and already annoyed.
 *
 * So this runs the page's own inline script — extracted from the file, not a copy — against
 * a stub DOM, and asks the questions a browser would. Every fragment shape GoTrue can
 * redirect with gets its own scenario, and the last one makes a *real* request to the real
 * project so the refusal mapping is checked against what GoTrue actually answers rather
 * than against what anybody remembers it answering.
 *
 * The stub reads each element's starting `hidden` out of the markup rather than assuming
 * it. That is not fussiness: the first version of this file assumed a visible form, and
 * four scenarios duly "passed" a form no browser would ever have shown.
 *
 * What it cannot cover is a live recovery token being accepted, which needs a real mail to
 * a real account. The request that path makes is the same `PUT /auth/v1/user` that
 * check-backend.mjs already proves against a live session, so what is untested is the
 * token's provenance rather than the call.
 */

import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { fileURLToPath } from 'node:url'

// Resolved off this file rather than off the working directory, because the sibling checks
// are run from `zeppos/` and everything this one reads lives above it.
const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')
const PAGE = path.join(ROOT, 'website', 'auth', 'index.html')
const SECRETS = path.join(ROOT, 'zeppos', 'shared', 'secrets.js')

const HTML = fs.readFileSync(PAGE, 'utf8')
const inline = [...HTML.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1])
if (inline.length !== 1) {
  console.log('FAIL: expected exactly one inline script in the page, found ' + inline.length)
  process.exit(1)
}
const CODE = inline[0]

/**
 * The real project, when there is one to hand.
 *
 * secrets.js is generated and gitignored, so a fresh clone has none — and the scenarios
 * that do not touch the network are worth running anyway. The live one is skipped loudly
 * rather than quietly, because a check that silently stops checking is worse than no check.
 */
function project() {
  if (!fs.existsSync(SECRETS)) return null
  const text = fs.readFileSync(SECRETS, 'utf8')
  const url = /SUPABASE_URL = '([^']+)'/.exec(text)
  const key = /SUPABASE_ANON_KEY = '([^']+)'/.exec(text)
  return url && key ? { url: url[1], key: key[1] } : null
}

const LIVE = project()

/** Whether the markup ships this element hidden. See the header. */
function startsHidden(id) {
  const tag = new RegExp('<[a-z]+[^>]* id="' + id + '"[^>]*>').exec(HTML)
  if (!tag) throw new Error('no element with id="' + id + '" in the page')
  return / hidden[ >]/.test(tag[0])
}

function element(id, inMarkup) {
  return {
    id,
    children: [],
    textContent: '',
    value: '',
    type: id === 'new-password' ? 'password' : '',
    hidden: inMarkup ? startsHidden(id) : false,
    disabled: false,
    className: '',
    style: {},
    listeners: {},
    addEventListener(name, fn) {
      ;(this.listeners[name] = this.listeners[name] || []).push(fn)
    },
    setAttribute(key, value) {
      this['attr:' + key] = value
    },
    appendChild(child) {
      this.children.push(child)
    },
    focus() {},
  }
}

const IDS = [
  'auth-mark',
  'auth-eyebrow',
  'auth-title',
  'auth-body',
  'reset-form',
  'new-password',
  'pw-toggle',
  'reset-problem',
  'reset-submit',
]

/** Loads the page with `fragment` in the address bar, and hands back what it did. */
function open(fragment, options) {
  const configured = !options || options.configured !== false
  const els = {}
  for (const id of IDS) els[id] = element(id, true)

  const document = {
    title: 'Your Whaaack! account — Whaaack!',
    getElementById: (id) => els[id] || null,
    createElement: () => element('p', false),
  }
  const window = {
    location: { search: '', hash: fragment, pathname: '/whaaack/auth/' },
    history: { replaceState() {} },
    WHAAACK_BACKEND: {
      url: (LIVE && LIVE.url) || 'https://example.invalid',
      key: configured ? (LIVE && LIVE.key) || 'sb_publishable_stub' : '__SUPABASE_PUBLISHABLE_KEY__',
    },
  }
  const sandbox = { window, document, fetch, console, setTimeout, JSON }
  sandbox.globalThis = sandbox
  vm.runInNewContext(CODE, sandbox)

  return {
    els,
    tab: () => document.title,
    title: () => els['auth-title'].textContent,
    said: () => els['auth-body'].children.map((c) => c.textContent).join(' '),
    formShown: () => els['reset-form'].hidden === false,
    problem: () => (els['reset-problem'].hidden ? null : els['reset-problem'].textContent),
    type: (password) => {
      els['new-password'].value = password
    },
    submit: () => els['reset-form'].listeners.submit[0]({ preventDefault() {} }),
    toggle: () => els['pw-toggle'].listeners.click[0](),
  }
}

let failures = 0
function check(label, ok, detail) {
  if (!ok) failures++
  console.log((ok ? 'ok    ' : 'FAIL  ') + label + (detail ? '  - ' + detail : ''))
}

console.log('page  ' + path.relative(ROOT, PAGE).replace(/\\/g, '/'))
console.log('')

// --------------------------------------------------------------- which link is this

{
  const page = open(
    '#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired',
  )
  check('an expired link says so', page.title() === 'That link has expired.', page.title())
  check('and offers no form to type into', !page.formShown())
  // The headline is not what a history entry, a bookmark or a task switcher shows.
  check('and the tab agrees with the headline', page.tab().startsWith('That link has expired.'), page.tab())
  // GoTrue's error redirect does not say which flow it belonged to, so the wording has to
  // serve both. It used to talk only about signing up.
  check('and covers both flows, since the error does not say which', page.said().includes('resetting a password'))
}

{
  const page = open('#access_token=t&refresh_token=r&expires_in=3600&token_type=bearer&type=recovery')
  check('a recovery link offers the form', page.formShown())
  check('and says what to do with it', page.title() === 'Choose a new password.', page.title())
}

{
  const page = open('#access_token=t&type=signup')
  check('a confirmation link still confirms', page.title() === 'Your email has been confirmed!', page.title())
  check('and offers no form', !page.formShown())
}

{
  const page = open('')
  check('a bare visit alarms nobody', page.title() === 'Nothing to do here.', page.title())
  check('and offers no form', !page.formShown())
}

{
  // Should not happen, but a mail client that rewrites links or drops fragments produces
  // exactly this — and a form that cannot spend anything is worse than a plain refusal.
  const page = open('#type=recovery')
  check('a recovery link with no token takes no password', !page.formShown())
  check('and says why', page.title() === 'That link has expired.', page.title())
}

{
  // The deploy substitutes the publishable key into backend-config.js. If that ever fails
  // open, the page must not put up a form that accepts a password and changes nothing.
  const page = open('#access_token=t&type=recovery', { configured: false })
  check('an unconfigured deploy takes no password', !page.formShown())
  check('and says so rather than pretending', page.title() === 'Cannot set it here just yet.', page.title())
}

// ------------------------------------------------------------------- the form itself

{
  const page = open('#access_token=t&type=recovery')

  page.type('short1')
  page.submit()
  check('a short password is refused before any request', page.problem() === 'Passwords are at least 8 characters.', page.problem())
  check('and the form stays up to fix it', page.formShown())

  page.type('orchardorchard')
  page.submit()
  check('a password with no digit is refused', page.problem() === 'Add at least one digit to the password.', page.problem())

  page.type('1234567890')
  page.submit()
  check('a password with no letter is refused', page.problem() === 'Add at least one letter to the password.', page.problem())
}

{
  // There is no confirm-it-twice field, so this is the only defence against a typo.
  const page = open('#access_token=t&type=recovery')
  check('the field starts masked', page.els['new-password'].type === 'password')
  page.toggle()
  check('and the toggle reveals it', page.els['new-password'].type === 'text')
  check('and says so to a screen reader', page.els['pw-toggle']['attr:aria-pressed'] === 'true')
  page.toggle()
  check('and masks it again', page.els['new-password'].type === 'password')
}

// ------------------------------------------- against the real project, with a dead token

if (!LIVE) {
  console.log('skip  the live refusal check - no zeppos/shared/secrets.js')
  console.log('      run: python tools/sync_zepp_secrets.py')
} else {
  const page = open('#access_token=notarealtoken&type=recovery')
  page.type('orchard42')
  page.submit()
  const deadline = Date.now() + 20000
  while (page.formShown() && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  check('a token the project rejects reads as an expired link', page.title() === 'That link has expired.', page.title())
  check('and the form is taken away', !page.formShown())
  check('and the wording sends them somewhere that works', page.said().includes('Email me a reset link'))
}

console.log('')
console.log(failures ? failures + ' failed' : 'all checks passed')
process.exit(failures ? 1 : 0)
