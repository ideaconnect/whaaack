/**
 * The names three separate JavaScript environments have to agree on.
 *
 * A Zepp OS app is three programs: the watch app, the side service running inside the
 * Zepp app on the phone, and the settings page rendered by that same phone app. They
 * share no runtime and can only reach each other through message methods and
 * `settingsStorage` keys - both of which are plain strings, and both of which fail
 * silently when they disagree. Keeping them in one file is the only thing that makes a
 * typo a build-time problem instead of a "why is the board always empty" problem.
 */

// ------------------------------------------------- watch -> side service (request)

/** Ask whether there is a usable session, without forcing a network round trip. */
export const REQ_AUTH = 'AUTH'
/** Fetch a page of the Zepp leaderboard, plus the caller's own standing. */
export const REQ_BOARD = 'BOARD'
/** Record a finished run. */
export const REQ_SUBMIT = 'SUBMIT'

// ------------------------------------------ settings page <-> side service (storage)

/**
 * What the settings page asks the side service to do, as JSON:
 *
 *   `{ action: 'signin',   email, password, at }`
 *   `{ action: 'signup',   email, password, name, at }`
 *   `{ action: 'signout',  at }`
 *   `{ action: 'reset',    email, at }`     mail a password-reset link
 *   `{ action: 'password', password, at }`  set a new password on the live session
 *
 * A password lives in this key only for as long as it takes the side service to spend it,
 * which clears the key. It is never written to the session key, never sent to the watch,
 * and never kept once the attempt has succeeded or failed. That holds for the new password
 * in a `password` request exactly as it does for the one in a `signin`.
 *
 * `at` is a timestamp with no meaning beyond making every request a distinct value:
 * `onSettingsChange` fires on change, so retrying the same wrong password twice has to
 * look like two different writes or the second one is dropped.
 */
export const KEY_AUTH_REQUEST = 'authRequest'

/**
 * What the side service reports back, as JSON:
 * `{ state: 'signed_in' | 'signed_out' | 'working' | 'confirm' | 'reset_sent' | 'error',
 *    name?, email?, message?, notice?, problem?, busy? }`.
 *
 * The settings page renders straight from this, so the phone always shows what the
 * service actually managed to do rather than what the form hoped it would.
 *
 * `notice`, `problem` and `busy` are stamped with `noticeAt` and are only true for as
 * long as that is recent. This key is storage: it outlives the page, the app and the phone
 * being switched off, so an unstamped "Password changed." would greet the player in green
 * under an empty field every time they opened the page for the rest of the account's life,
 * and a `busy` whose answer never arrived would leave a button reading "Working…" for ever.
 *
 * `notice`, `problem` and `busy` belong to the signed-in state and to nothing else. A
 * password change happens *inside* an account that is already signed in, so its progress
 * and its outcome cannot be reported as `working` or `error` the way sign-in's are: the
 * page picks its whole layout off `state`, and either of those would replace the account
 * card - and the change-password field on it - with a sign-in form, over a session that is
 * still perfectly good.
 */
export const KEY_AUTH_STATUS = 'authStatus'

/**
 * The email last typed into the settings form.
 *
 * Kept in storage rather than in the page's own state because writing it is what makes the
 * page re-render: a Settings page rebuilds when `settingsStorage` changes and at no other
 * time, so a field whose value lives only in memory shows its placeholder for ever, however
 * much has been typed into it. The password gets no such key - it lives in a module
 * variable for the seconds between being typed and being spent, and nowhere else.
 */
export const KEY_EMAIL = 'email'

/** The display name last typed into the signup form. Same reasoning as `KEY_EMAIL`. */
export const KEY_NAME = 'displayName'

/**
 * Which half of the settings form is showing, `MODE_SIGN_IN` or `MODE_SIGN_UP`.
 *
 * In storage for the same reason as the drafts: a Settings page redraws when storage
 * changes and at no other time, so a mode held in memory would flip without the page ever
 * showing the other form.
 */
export const KEY_MODE = 'formMode'

export const MODE_SIGN_IN = 'signin'
export const MODE_SIGN_UP = 'signup'

/**
 * The tokens, as JSON: `{ accessToken, refreshToken, expiresAt, userId, name }`.
 *
 * Held on the phone rather than the watch on purpose. Every authenticated call is made by
 * the side service, so the watch never needs a token - and a watch is the easier of the
 * two devices to hand to somebody for a moment.
 */
export const KEY_SESSION = 'session'

// -------------------------------------------------------------- watch local storage

/** Best run this watch has seen, whether or not it was ever ranked. */
export const LOCAL_BEST = 'best'

/**
 * Whether the game makes any sound, as `1` or `0`. Absent means on.
 *
 * On the watch rather than in the phone's settings page, where the account lives, because
 * this is the one preference a player may want to change without their phone in reach -
 * the point of turning the music off is usually that somebody has just walked in. A
 * setting that needed the Zepp app to change would be no use at that moment.
 */
export const LOCAL_SOUND = 'sound'

/**
 * `'1'` once this watch has actually played something, `'0'` once it has refused outright.
 * Absent until either has happened.
 *
 * Whether a watch can play audio at all is only discoverable on the game page, a second or
 * two after it opens - and the place a player asks the question is the home screen, where
 * the toggle would otherwise go on offering sound that they have already found out is not
 * coming. Remembering the answer is what lets that button stop lying.
 */
export const LOCAL_SOUND_OK = 'soundOk'

// ------------------------------------------------------------------------- statuses

export const AUTH_SIGNED_OUT = 'signed_out'
export const AUTH_WORKING = 'working'
export const AUTH_SIGNED_IN = 'signed_in'
export const AUTH_ERROR = 'error'

/**
 * The account exists but the address has not been proved yet.
 *
 * Its own state rather than a flavour of `error`, because nothing went wrong: there is a
 * mail on its way, and the only thing left is for the player to open it and then sign in.
 * Telling them that in red, under a heading that says something failed, would send them
 * round the signup form a second time.
 */
export const AUTH_CONFIRM = 'confirm'

/**
 * A reset link is in the post.
 *
 * Its own state rather than a flavour of `confirm`, which says "we made you an account,
 * now prove the address". This one says "we did not make anything, and if that address has
 * an account there is a link on its way to it" - a different promise, and one that has to
 * be worded carefully, because GoTrue answers a recovery request for an address it has
 * never seen exactly as it answers one for an address it knows.
 */
export const AUTH_RESET_SENT = 'reset_sent'

export const SCOPE_ALL_TIME = 'all_time'
export const SCOPE_WEEKLY = 'weekly'
