package tech.idct.whaaack.data

/**
 * The rules a display name has to satisfy, held on this side of the wire as well as the
 * database's.
 *
 * `profiles` has enforced these since the first migration — `display_name_length` and
 * `display_name_shape` — and it stays the authority; nothing here is a substitute for a check
 * constraint, and a name that somehow gets past this is still refused where it counts. What
 * this is for is the answer a player gets. Without it a rename to `李雷` or `-nick-` left
 * Postgres to explain itself, and the mapping in [AuthRepository] has no branch for 23514, so
 * the rename sheet showed the player
 *
 *     new row for relation "profiles" violates check constraint "display_name_shape"
 *
 * which names our schema, blames them for nothing they can act on, and does not say which part
 * of the name was wrong. The constraint is the fence; this is the sign on it.
 *
 * The two must not drift. [PATTERN] is `display_name_shape` transcribed, and the length bounds
 * are `display_name_length`'s — if a migration ever widens one, it widens this too, and
 * `DisplayNameTest` is where that gets noticed.
 *
 * Uniqueness is deliberately *not* here. It is a question about every other row in the table,
 * which only the database can answer, and it comes back as [AuthError.NameTaken].
 */
object DisplayName {

    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 24

    /**
     * `display_name_shape`, character for character: alphanumeric at both ends, and in between
     * any of letters, digits, space, dot, underscore, hyphen. A two-character name is both
     * ends and no middle, which the optional group covers.
     */
    private val PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9 ._-]*[A-Za-z0-9])?$")

    /** What the signup and rename fields tell a player before they get anything wrong. */
    const val HINT = "2–24 characters: letters, numbers, spaces, . _ or -"

    /**
     * The name as it will be sent. Only the outer whitespace goes: the shape rule forbids a
     * name that starts or ends on a space anyway, and trimming is the difference between a
     * trailing space being a rejection and it being invisible.
     *
     * Inner runs are deliberately left alone. "Jan  Kowalski" is a name somebody may want, it
     * satisfies the constraint, and collapsing it would silently hand them a different name
     * from the one they typed — the failure this whole audit is about.
     */
    fun normalize(raw: String): String = raw.trim()

    /**
     * Null when [raw] is acceptable, otherwise the error to show.
     *
     * Length is reported before shape because it is the one a player is most likely to hit and
     * the easiest to act on, and because a one-character name fails both rules at once — being
     * told about the character set would send them looking in the wrong place.
     */
    fun validate(raw: String): AuthError? {
        val name = normalize(raw)
        if (name.length < MIN_LENGTH || name.length > MAX_LENGTH) {
            return AuthError.NameInvalid(
                "Display names are $MIN_LENGTH to $MAX_LENGTH characters. " +
                    "Yours is ${name.length}.",
            )
        }
        if (!PATTERN.matches(name)) {
            return AuthError.NameInvalid(
                "Use letters, numbers, spaces, dots, dashes or underscores, " +
                    "starting and ending with a letter or number.",
            )
        }
        return null
    }
}
