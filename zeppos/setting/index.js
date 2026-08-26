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
  AUTH_RESET_SENT,
  AUTH_ERROR,
} from '../shared/protocol.js'
import { NAME_HINT, PASSWORD_HINT } from '../shared/credentials.js'
import { LOGO_IDCT, ICON_ANDROID, ICON_COFFEE } from './assets.js'

const INK = '#2c2c34'
const MUTED = '#6d6d7a'
const ACCENT = '#f2704f'
const DANGER = '#c0392b'
const OK = '#3f8f2f'
const HAIRLINE = '#e4e4ea'

/** Buy Me a Coffee's own yellow, and the near-black they set their cup in. */
const COFFEE_YELLOW = '#ffdd00'
const COFFEE_INK = '#111111'

const COFFEE_URL = 'https://buymeacoffee.com/idct'
const ANDROID_URL = 'https://play.google.com/store/apps/details?id=tech.idct.whaaack'
const IDCT_URL = 'https://idct.tech'

/**
 * Centring has to be said on the text itself.
 *
 * `Text` writes `text-align: left` into its own style, so it wins over a `textAlign` on
 * the block around it and the inherited value never arrives - the image above these lines
 * centres from the parent, the lines do not.
 */
const CENTRED = { textAlign: 'center' }


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

/**
 * The new password, on the account card.
 *
 * Its own variable rather than sharing `draftPassword`, which belongs to the sign-in form.
 * They are never on screen together - one is for a session that exists and the other for
 * one that does not - but sharing would mean a half-typed sign-in password reappearing in
 * the change-password field after a sign-in failed and succeeded, which is exactly the
 * moment nobody is watching the field.
 */
let draftNewPassword = ''

/** A `Text` that occupies its own line. */
function line(content, style) {
  return Text({ style: Object.assign({ display: 'block' }, style) }, content)
}

/**
 * Narrower than the cards above them, and centred in the page.
 *
 * These two are invitations rather than controls - one asks for a tip, the other points at
 * a different game - and a full-width block reads as something the page needs you to do.
 * Pulled in, they read as an offer, and the inset keeps them clear of the sign-in button
 * that people actually came here for.
 */
const ROW_WIDTH = '80%'

/**
 * An icon and a label, centred together on one line, wrapped in a link out to the web.
 *
 * `inline-block` on a shared baseline rather than flexbox: this page is rendered by
 * whatever webview the Zepp app is built around, on both phone platforms and across
 * several app versions, and inline layout is the part of CSS that has behaved the same way
 * in every renderer since 1997. It also centres the pair for free - two inline boxes on a
 * line that `text-align: center` acts on - where flex would need the row to agree about
 * `justify-content`.
 *
 * The label is capped rather than fixed. At a fixed width it would fill the row and its
 * own centring would be all that showed; a maximum lets a short label shrink to its text
 * so the icon sits beside it, and a long one still wrap instead of pushing the icon out.
 */
function linkRow({ href, icon, iconSize, label, labelStyle, style }) {
  return Link({ source: href }, [
    View(
      {
        style: Object.assign(
          { display: 'block', width: ROW_WIDTH, margin: '0 auto', textAlign: 'center' },
          style,
        ),
      },
      [
        Image({
          src: icon,
          style: {
            display: 'inline-block',
            verticalAlign: 'middle',
            width: iconSize,
            height: iconSize,
            marginRight: '12px',
          },
        }),
        Text(
          {
            style: Object.assign(
              { display: 'inline-block', verticalAlign: 'middle', maxWidth: '68%' },
              CENTRED,
              labelStyle,
            ),
          },
          label,
        ),
      ],
    ),
  ])
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
    draftNewPassword = ''
    this.storage().setItem(
      KEY_AUTH_REQUEST,
      JSON.stringify({ action: 'signout', at: Date.now() }),
    )
  },

  /**
   * Asks for a reset link to whatever address is in the sign-in form.
   *
   * The form's own field rather than a second one of its own: somebody who has come here
   * to reset a password has almost always just typed the address and failed to sign in
   * with it, and a second box to type it into again would be asking them to prove they can
   * still spell it.
   */
  requestReset() {
    const email = draftEmail.trim()
    if (!email) {
      this.setStatus({ state: AUTH_ERROR, email, message: gettext('emailNeeded') })
      return
    }
    // The password in the form has nothing to do with this request and is about to be
    // irrelevant anyway.
    draftPassword = ''
    this.storage().setItem(
      KEY_AUTH_REQUEST,
      JSON.stringify({ action: 'reset', email, at: Date.now() }),
    )
    this.setStatus({ state: AUTH_WORKING, email })
  },

  /** Sets a new password on the session that is already signed in. */
  requestPasswordChange() {
    const status = this.status()
    const password = draftNewPassword
    if (!password) {
      this.setStatus(
        Object.assign({}, status, { notice: null, problem: gettext('newPasswordNeeded') }),
      )
      return
    }

    // Out of the draft before it is out of this function, exactly as `submit` does with the
    // sign-in one: the field is legible while it is on screen, so it comes off it the
    // moment the button is pressed rather than sitting there until the phone locks.
    draftNewPassword = ''

    this.storage().setItem(
      KEY_AUTH_REQUEST,
      JSON.stringify({ action: 'password', password, at: Date.now() }),
    )
    this.setStatus(Object.assign({}, status, { notice: null, problem: null, busy: true }))
  },

  heading(status) {
    // The subtitle follows the form. "Sign in to rank your runs" over a signup form is a
    // small lie, and it is the line a player reads to work out what this page is for.
    let subtitle = gettext('subtitle')
    if (status.state === AUTH_SIGNED_IN) subtitle = gettext('subtitleSignedIn')
    else if (status.state === AUTH_CONFIRM) subtitle = gettext('subtitleConfirm')
    else if (status.state === AUTH_RESET_SENT) subtitle = gettext('subtitleReset')
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
   * Changing the password, on the account card's own terms.
   *
   * A card of its own under the account rather than another control inside it, because it
   * is the one thing on this page that is not about *this* app: it changes the password of
   * an account the phone game shares, and a separator says that better than a paragraph
   * would.
   *
   * One field. The server does not ask for the current password
   * (`secure_password_change = false`, and supabase/config.toml explains why), so asking
   * for one here would be theatre - and on a page with no masked input, theatre performed
   * in the clear. There is no confirm-it-twice field either: that guards against a typo
   * you cannot see, and this one is legible the whole time it is being typed.
   */
  passwordCard(status) {
    const busy = !!status.busy
    const rows = [
      line(gettext('changePassword'), {
        fontSize: '15px',
        fontWeight: 'bold',
        color: INK,
        marginBottom: '10px',
      }),
      this.field(
        gettext('newPassword'),
        draftNewPassword,
        (value) => {
          draftNewPassword = String(value || '')
        },
        undefined,
        72,
      ),
      line(PASSWORD_HINT, { fontSize: '12px', color: MUTED, margin: '-4px 0 10px' }),
      Button({
        label: busy ? gettext('working') : gettext('changePassword'),
        style: {
          fontSize: '15px',
          borderRadius: '22px',
          background: busy ? '#c9c9d0' : ACCENT,
          color: '#ffffff',
          width: '100%',
        },
        onClick: () => this.requestPasswordChange(),
      }),
    ]

    if (status.problem) {
      rows.push(line(status.problem, { fontSize: '13px', color: DANGER, marginTop: '12px' }))
    } else if (status.notice) {
      rows.push(line(status.notice, { fontSize: '13px', color: OK, marginTop: '12px' }))
    }

    return View({ style: CARD }, rows)
  },

  /**
   * What a reset request gets in return.
   *
   * The wording is conditional on purpose. GoTrue answers a recovery request for an
   * address it has never seen exactly as it answers one for an address it knows, which is
   * what stops this form being a way to ask whether somebody has an account - so this card
   * must not claim a mail was sent, only that one is on its way if there was anywhere to
   * send it.
   */
  resetSentCard(status) {
    return View({ style: CARD }, [
      line(gettext('resetSentTitle'), {
        fontSize: '17px',
        fontWeight: 'bold',
        color: OK,
        marginBottom: '6px',
      }),
      line(status.email || '', { fontSize: '14px', color: INK, marginBottom: '8px' }),
      line(gettext('resetSentBody'), { fontSize: '13px', color: MUTED, marginBottom: '6px' }),
      line(gettext('resetSentWhere'), { fontSize: '12px', color: MUTED, marginBottom: '14px' }),
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

    // Only under the sign-in half. On the signup form there is no password to have
    // forgotten yet, and the offer would read as an instruction.
    if (!signingUp) {
      rows.push(
        line(gettext('forgotPassword'), {
          fontSize: '12px',
          color: MUTED,
          margin: '16px 0 8px',
        }),
        Button({
          label: gettext('sendReset'),
          style: {
            fontSize: '14px',
            borderRadius: '22px',
            background: '#ececef',
            color: INK,
            width: '100%',
          },
          onClick: () => this.requestReset(),
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

  /**
   * What sits under every version of this page: somewhere to say thank you, a pointer to
   * the bigger game, and who made it.
   *
   * Under *every* version deliberately. This is the only screen the Zepp edition has that
   * is big enough to read, and the three states above it - signed out, awaiting a
   * confirmation mail, signed in - are all states a player can sit in for days. A footer
   * that only appeared on one of them would be a footer most people never saw.
   */
  footer() {
    return View({ style: { display: 'block', padding: '2px 4px 8px' } }, [
      linkRow({
        href: COFFEE_URL,
        icon: ICON_COFFEE,
        iconSize: '30px',
        label: gettext('buyMeACoffee'),
        labelStyle: { fontSize: '15px', fontWeight: 'bold', color: COFFEE_INK },
        style: {
          background: COFFEE_YELLOW,
          borderRadius: '12px',
          padding: '12px 16px',
          marginBottom: '12px',
        },
      }),

      linkRow({
        href: ANDROID_URL,
        icon: ICON_ANDROID,
        iconSize: '44px',
        label: gettext('androidVersion'),
        labelStyle: { fontSize: '14px', fontWeight: 'bold', color: INK },
        style: {
          background: '#ffffff',
          border: '1px solid ' + HAIRLINE,
          borderRadius: '12px',
          padding: '12px 16px',
          marginBottom: '20px',
        },
      }),

      View({ style: { display: 'block', textAlign: 'center', paddingBottom: '6px' } }, [
        Link({ source: IDCT_URL }, [
          Image({
            src: LOGO_IDCT,
            alt: 'IDCT',
            style: { display: 'inline-block', width: '92px', height: '86px' },
          }),
        ]),
        line(
          gettext('copyright'),
          Object.assign({ fontSize: '11px', color: MUTED, marginTop: '6px' }, CENTRED),
        ),
        line(
          gettext('rights'),
          Object.assign({ fontSize: '11px', color: MUTED, marginTop: '2px' }, CENTRED),
        ),
      ]),
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
        this.passwordCard(status),
        this.footer(),
      ])
    }

    if (status.state === AUTH_CONFIRM) {
      return View({ style: { padding: '14px 16px' } }, [
        this.heading(status),
        this.confirmCard(status),
        this.footer(),
      ])
    }

    if (status.state === AUTH_RESET_SENT) {
      return View({ style: { padding: '14px 16px' } }, [
        this.heading(status),
        this.resetSentCard(status),
        this.footer(),
      ])
    }

    return View({ style: { padding: '14px 16px' } }, [
      this.heading(status),
      this.formCard(status),
      this.modeSwitch(),
      this.footer(),
    ])
  },
})
