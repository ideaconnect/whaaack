/**
 * The settings page - the only place the Zepp edition takes a password, and now the only
 * place it can create an account.
 *
 * It runs inside the Zepp app on the phone, not on the watch, which is deliberate: a watch
 * has no keyboard worth the name, and the alternative (an on-glass character picker) turns
 * signing in into a two-minute chore that people abandon. Here it is a phone keyboard.
 *
 * This page never talks to Supabase itself. It writes a request into `settingsStorage` and
 * reads the outcome back out of it; the side service does the network call. Two reasons:
 * the service is where the session already lives and where every other authenticated call
 * is made from, and a settings page is torn down the moment somebody navigates away - which
 * is exactly the wrong lifetime for a request that has to finish.
 *
 * Two things about the Settings API that this file is shaped by, both learned the hard way:
 *
 *   `settingsStorage` is the only state that survives. A page rebuilds when storage
 *   changes and at no other time, and the object the framework calls `build` on does not
 *   carry values written to `this.state` across a rebuild. So the draft fields live in
 *   module variables - which do survive - and the ones that are not secret are additionally
 *   mirrored into storage, because writing is what makes a field redraw with what was
 *   typed. The password gets no such key: it lives in `draftPassword` for the seconds
 *   between being typed and being spent, and nowhere else.
 *
 *   Every `Text` needs `display: 'block'` of its own, or the renderer runs consecutive
 *   ones together into a single paragraph.
 *
 * And one thing worth saying out loud: the Settings API has no masked input, so the
 * password is legible while it is being typed. It is cleared from the form the instant the
 * button is pressed, and the side service deletes the stored request as soon as it reads
 * it. But on screen, while typing, it is in the clear.
 */

import { gettext } from 'i18n'

import {
  KEY_AUTH_REQUEST,
  KEY_AUTH_STATUS,
  KEY_EMAIL,
  KEY_NAME,
  KEY_MODE,
  MODE_SIGN_IN,
  MODE_SIGN_UP,
  AUTH_SIGNED_OUT,
  AUTH_WORKING,
  AUTH_SIGNED_IN,
  AUTH_CONFIRM,
  AUTH_ERROR,
} from '../shared/protocol.js'
import { NAME_HINT, PASSWORD_HINT } from '../shared/credentials.js'

const INK = '#2c2c34'
const MUTED = '#6d6d7a'
const ACCENT = '#f2704f'
const DANGER = '#c0392b'
const OK = '#3f8f2f'
const HAIRLINE = '#e4e4ea'

const CARD = {
  display: 'block',
  background: '#ffffff',
  borderRadius: '12px',
  padding: '16px',
  marginBottom: '14px',
}

const FIELD = {
  display: 'block',
  border: '1px solid ' + HAIRLINE,
  borderRadius: '8px',
  padding: '6px 12px',
  marginBottom: '10px',
  background: '#fbfbfd',
}

/** Module scope, so a rebuild does not lose what has been typed. See the header. */
let draftEmail = ''
let draftPassword = ''
let draftName = ''

/** A `Text` that occupies its own line. */
function line(content, style) {
  return Text({ style: Object.assign({ display: 'block' }, style) }, content)
}

AppSettingsPage({
  state: {
    props: {},
  },

  storage() {
    return this.state.props.settingsStorage
  },

  read(key) {
    const raw = this.storage().getItem(key)
    if (!raw) return null
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw
    } catch (e) {
      return null
    }
  },

  status() {
    return this.read(KEY_AUTH_STATUS) || { state: AUTH_SIGNED_OUT }
  },

  setStatus(status) {
    this.storage().setItem(KEY_AUTH_STATUS, JSON.stringify(status))
  },

  /** Which half of the form is showing. Stored, because switching has to redraw the page. */
  mode() {
    return this.storage().getItem(KEY_MODE) === MODE_SIGN_UP ? MODE_SIGN_UP : MODE_SIGN_IN
  },

  setMode(mode) {
    // A message about the last attempt has nothing to say about the form now on screen -
    // "that email and password do not match an account" under a *signup* form is actively
    // misleading. Clearing it is part of the switch.
    this.setStatus({ state: AUTH_SIGNED_OUT })
    this.storage().setItem(KEY_MODE, mode)
  },

  submit() {
    const email = draftEmail.trim()
    const password = draftPassword
    const signingUp = this.mode() === MODE_SIGN_UP

    // Say so rather than doing nothing. A button that silently declines to work is the
    // worst of the outcomes here, and it is the one an early return produces.
    if (!email || !password) {
      this.setStatus({ state: AUTH_ERROR, email, message: gettext('bothFields') })
      return
    }
    if (signingUp && !draftName.trim()) {
      this.setStatus({ state: AUTH_ERROR, email, message: gettext('nameNeeded') })
      return
    }

    // Out of the draft before it is out of this function. Re-rendering with the field still
    // populated is the difference between a password that is visible for the few seconds it
    // takes to type it and one that sits on screen until the phone locks.
    draftPassword = ''

    const request = { action: signingUp ? 'signup' : 'signin', email, password, at: Date.now() }
    if (signingUp) request.name = draftName.trim()

    // `at` carries no meaning beyond making each attempt a distinct value: the side service
    // is woken by a *change*, so retrying the same wrong password twice has to look like two
    // different writes or the second attempt never happens.
    this.storage().setItem(KEY_AUTH_REQUEST, JSON.stringify(request))
    this.setStatus({ state: AUTH_WORKING, email })
  },

  requestSignOut() {
    draftPassword = ''
    this.storage().setItem(
      KEY_AUTH_REQUEST,
      JSON.stringify({ action: 'signout', at: Date.now() }),
    )
  },

  heading(status) {
    // The subtitle follows the form. "Sign in to rank your runs" over a signup form is a
    // small lie, and it is the line a player reads to work out what this page is for.
    let subtitle = gettext('subtitle')
    if (status.state === AUTH_SIGNED_IN) subtitle = gettext('subtitleSignedIn')
    else if (status.state === AUTH_CONFIRM) subtitle = gettext('subtitleConfirm')
    else if (this.mode() === MODE_SIGN_UP) subtitle = gettext('subtitleSignUp')

    return View({ style: { display: 'block', padding: '4px 4px 14px' } }, [
      line(gettext('title'), {
        fontSize: '22px',
        fontWeight: 'bold',
        color: INK,
        marginBottom: '4px',
      }),
      line(subtitle, { fontSize: '13px', color: MUTED }),
    ])
  },

  field(label, value, onChange, placeholder, maxLength) {
    return View({ style: FIELD }, [
      TextInput({
        label,
        value,
        placeholder,
        maxLength,
        labelStyle: { fontSize: '12px', color: MUTED },
        subStyle: { fontSize: '15px', color: INK },
        onChange,
      }),
    ])
  },

  signedInCard(status) {
    return View({ style: CARD }, [
      line(gettext('signedInAs'), { fontSize: '13px', color: MUTED }),
      line(status.name || status.email || gettext('player'), {
        fontSize: '18px',
        fontWeight: 'bold',
        color: OK,
        margin: '4px 0 2px',
      }),
      status.name && status.email
        ? line(status.email, { fontSize: '12px', color: MUTED })
        : null,
      line(gettext('signedInHint'), { fontSize: '13px', color: MUTED, margin: '12px 0' }),
      Button({
        label: gettext('signOut'),
        style: {
          fontSize: '14px',
          borderRadius: '22px',
          background: '#ececef',
          color: INK,
          width: '100%',
        },
        onClick: () => this.requestSignOut(),
      }),
    ])
  },

  /**
   * The "check your inbox" card.
   *
   * Its own card rather than a red line under the signup form, because nothing went wrong:
   * the account exists, a mail is on its way, and the only thing left is to open it and
   * come back. It offers the sign-in form directly, since that is the next step.
   */
  confirmCard(status) {
    return View({ style: CARD }, [
      line(gettext('confirmTitle'), {
        fontSize: '17px',
        fontWeight: 'bold',
        color: OK,
        marginBottom: '6px',
      }),
      line(status.email || '', { fontSize: '14px', color: INK, marginBottom: '8px' }),
      line(gettext('confirmBody'), { fontSize: '13px', color: MUTED, marginBottom: '14px' }),
      Button({
        label: gettext('backToSignIn'),
        style: {
          fontSize: '15px',
          borderRadius: '22px',
          background: ACCENT,
          color: '#ffffff',
          width: '100%',
        },
        onClick: () => this.setMode(MODE_SIGN_IN),
      }),
    ])
  },

  formCard(status) {
    const signingUp = this.mode() === MODE_SIGN_UP
    const working = status.state === AUTH_WORKING
    const rows = []

    if (signingUp) {
      rows.push(
        this.field(
          gettext('displayName'),
          draftName,
          (value) => {
            draftName = String(value || '')
            this.storage().setItem(KEY_NAME, draftName)
          },
          gettext('displayNamePlaceholder'),
          24,
        ),
      )
      rows.push(line(NAME_HINT, { fontSize: '12px', color: MUTED, margin: '-4px 0 12px' }))
    }

    rows.push(
      this.field(
        gettext('email'),
        draftEmail,
        (value) => {
          draftEmail = String(value || '')
          // Also the rebuild trigger: without a storage write the field would go on showing
          // its placeholder however much has been typed into it.
          this.storage().setItem(KEY_EMAIL, draftEmail)
        },
        'you@example.com',
        254,
      ),
    )

    rows.push(
      this.field(
        gettext('password'),
        draftPassword,
        (value) => {
          draftPassword = String(value || '')
        },
        undefined,
        72,
      ),
    )

    if (signingUp) {
      rows.push(line(PASSWORD_HINT, { fontSize: '12px', color: MUTED, margin: '-4px 0 8px' }))
    }

    rows.push(
      line(gettext('passwordVisible'), {
        fontSize: '12px',
        color: MUTED,
        margin: '2px 0 14px',
      }),
    )

    rows.push(
      Button({
        label: working
          ? gettext('working')
          : signingUp
            ? gettext('createAccount')
            : gettext('signIn'),
        style: {
          fontSize: '15px',
          borderRadius: '22px',
          background: working ? '#c9c9d0' : ACCENT,
          color: '#ffffff',
          width: '100%',
        },
        onClick: () => this.submit(),
      }),
    )

    if (status.message && (status.state === AUTH_ERROR || status.state === AUTH_SIGNED_OUT)) {
      rows.push(
        line(status.message, {
          fontSize: '13px',
          color: status.state === AUTH_ERROR ? DANGER : MUTED,
          marginTop: '12px',
        }),
      )
    }

    return View({ style: CARD }, rows)
  },

  /**
   * The switch between the two forms.
   *
   * A link-shaped button under the card rather than a pair of tabs above it: there is one
   * thing a given player wants and the other is the escape hatch, and a watch's settings
   * page is a narrow column where two tabs would take a third of the width to say something
   * most people never need.
   */
  modeSwitch() {
    const signingUp = this.mode() === MODE_SIGN_UP
    return View({ style: { display: 'block', padding: '0 6px 18px' } }, [
      line(signingUp ? gettext('haveAccount') : gettext('noAccount'), {
        fontSize: '12px',
        color: MUTED,
        marginBottom: '8px',
      }),
      Button({
        label: signingUp ? gettext('switchToSignIn') : gettext('switchToSignUp'),
        style: {
          fontSize: '14px',
          borderRadius: '22px',
          background: '#ececef',
          color: INK,
          width: '100%',
        },
        onClick: () => this.setMode(signingUp ? MODE_SIGN_IN : MODE_SIGN_UP),
      }),
    ])
  },

  build(props) {
    this.state.props = props
    const status = this.status()
    if (!draftEmail) draftEmail = this.storage().getItem(KEY_EMAIL) || status.email || ''
    if (!draftName) draftName = this.storage().getItem(KEY_NAME) || ''

    if (status.state === AUTH_SIGNED_IN) {
      return View({ style: { padding: '14px 16px' } }, [
        this.heading(status),
        this.signedInCard(status),
      ])
    }

    if (status.state === AUTH_CONFIRM) {
      return View({ style: { padding: '14px 16px' } }, [
        this.heading(status),
        this.confirmCard(status),
      ])
    }

    return View({ style: { padding: '14px 16px' } }, [
      this.heading(status),
      this.formCard(status),
      this.modeSwitch(),
    ])
  },
})
