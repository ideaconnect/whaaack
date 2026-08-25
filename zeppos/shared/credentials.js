/**
 * What a new account has to satisfy, checked here as well as where it counts.
 *
 * None of this is a substitute for the server: `profiles` has enforced the display-name
 * rules since the first migration (`display_name_length`, `display_name_shape`) and GoTrue
 * enforces the password ones, and a value that somehow got past this file would still be
 * refused there. What this is for is the *answer a player gets*. Left to the database, a
 * name like `-nick-` comes back as
 *
 *     new row for relation "profiles" violates check constraint "display_name_shape"
 *
 * which names our schema, blames them for nothing they can act on, and does not say which
 * part of the name was wrong. The constraint is the fence; this is the sign on it.
 *
 * The phone game holds the same rules in `DisplayName.kt`, transcribed from the same
 * migration, and the password ones come from `supabase/config.toml`
 * (`minimum_password_length = 8`, `password_requirements = "letters_digits"`). All three
 * copies have to move together if a rule ever changes.
 *
 * Uniqueness is deliberately absent. It is a question about every other row in the table,
 * which only the database can answer.
 */

export const NAME_MIN = 2
export const NAME_MAX = 24
export const PASSWORD_MIN = 8

/**
 * `display_name_shape`, character for character: alphanumeric at both ends, and in between
 * any of letters, digits, space, dot, underscore, hyphen. A two-character name is both ends
 * and no middle, which the optional group covers.
 */
const NAME_SHAPE = /^[A-Za-z0-9](?:[A-Za-z0-9 ._-]*[A-Za-z0-9])?$/

/**
 * Only the outer whitespace goes. The shape rule forbids a name that starts or ends on a
 * space anyway, and trimming is the difference between a trailing space being a rejection
 * and it being invisible. Inner runs are left alone: "Jan  Kowalski" is a name somebody may
 * want, it satisfies the constraint, and collapsing it would silently hand them a different
 * name from the one they typed.
 */
export function normalizeName(raw) {
  return String(raw || '').trim()
}

/** Null when the name is acceptable, otherwise the sentence to show. */
export function validateName(raw) {
  const name = normalizeName(raw)
  // Length before shape: it is the rule a player is most likely to hit and the easiest to
  // act on, and a one-character name breaks both at once - being told about the character
  // set would send them looking in the wrong place.
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    return 'Display names are ' + NAME_MIN + ' to ' + NAME_MAX + ' characters. Yours is ' + name.length + '.'
  }
  if (!NAME_SHAPE.test(name)) {
    return 'Use letters, numbers, spaces, dots, dashes or underscores, starting and ending with a letter or number.'
  }
  return null
}

/** Null when the password is acceptable, otherwise the sentence to show. */
export function validatePassword(raw) {
  const password = String(raw || '')
  if (password.length < PASSWORD_MIN) {
    return 'Passwords are at least ' + PASSWORD_MIN + ' characters.'
  }
  // `password_requirements = "letters_digits"`. Said as two facts rather than one rule,
  // because "must contain letters and digits" leaves a player guessing which half is missing.
  if (!/[A-Za-z]/.test(password)) return 'Add at least one letter to the password.'
  if (!/[0-9]/.test(password)) return 'Add at least one digit to the password.'
  return null
}

/**
 * Null when the address is plausible.
 *
 * Deliberately loose - GoTrue is the authority and the real test is whether the
 * confirmation mail arrives. This catches the typo that would otherwise cost a player a
 * round trip: no `@`, nothing after it, or no dot in the domain.
 */
export function validateEmail(raw) {
  const email = String(raw || '').trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return 'That does not look like an email address.'
  }
  return null
}

/** The hint shown under the name field, before anything has gone wrong. */
export const NAME_HINT =
  NAME_MIN + '–' + NAME_MAX + ' characters: letters, numbers, spaces, . _ or -'

export const PASSWORD_HINT =
  'At least ' + PASSWORD_MIN + ' characters, with a letter and a digit.'
