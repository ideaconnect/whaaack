package tech.idct.whaaack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display-name rules, which exist twice — here and as `display_name_length` /
 * `display_name_shape` in `supabase/migrations/20260812000000_init.sql` — and must not drift.
 *
 * Every case below is written from the constraint rather than from the Kotlin, so a change to
 * one that is not made to the other fails here. Getting it wrong in the permissive direction
 * is the harmless half: the database still refuses the name, and the player gets a raw 23514
 * instead of a sentence. Getting it wrong in the strict direction is the half that matters —
 * the app would refuse names the leaderboard would happily hold, and no server-side check
 * would ever contradict it.
 */
class DisplayNameTest {

    @Test
    fun `the names the constraint accepts are accepted here`() {
        for (name in listOf(
            "Zenek",
            "Ze",                        // exactly MIN_LENGTH
            "abcdefghijklmnopqrstuvwx",  // exactly MAX_LENGTH, 24
            "Zenek2",                    // what a collision now produces
            "Jan Kowalski",              // inner space
            "a.b_c-d",                   // every punctuation the shape allows, in the middle
            "Player4f2a91c0d3b8",        // the trigger's uuid-derived last resort
            "99",                        // digits at both ends
        )) {
            assertNull("should be accepted: $name", DisplayName.validate(name))
        }
    }

    @Test
    fun `length is bounded at both ends, after trimming`() {
        assertNotNull("one character", DisplayName.validate("a"))
        assertNotNull("empty", DisplayName.validate(""))
        assertNotNull("whitespace only", DisplayName.validate("   "))
        assertNotNull("25 characters", DisplayName.validate("abcdefghijklmnopqrstuvwxy"))
        // Trimmed first, so the surrounding space is not what makes it too long.
        assertNull("24 characters plus padding", DisplayName.validate("  abcdefghijklmnopqrstuvwx  "))
    }

    @Test
    fun `both ends must be alphanumeric, which is the rule most easily broken by accident`() {
        for (name in listOf("-bartek", "bartek-", "_dev", "dev_", ".x", "x.")) {
            assertNotNull("should be refused: $name", DisplayName.validate(name))
        }
    }

    @Test
    fun `a space is the one edge character that is fixed rather than refused`() {
        // The shape rule forbids ending on a space just as firmly as ending on a dash, but
        // the two are not the same mistake: a stray space is a typing artefact and a dash is
        // a choice. normalize() trims, so these are valid names that happen to arrive padded.
        assertNull(DisplayName.validate(" lead"))
        assertNull(DisplayName.validate("trail "))
        assertEquals("lead", DisplayName.normalize(" lead"))
    }

    @Test
    fun `characters outside the allowed set are refused rather than quietly stripped`() {
        // The signup trigger sanitises these away, which is why a name of nothing but
        // non-ASCII used to arrive on the leaderboard as "Player" without a word to the player.
        for (name in listOf("李雷", "Zenek!", "a@b", "emoji🐿name", "semi;colon", "sla/sh")) {
            assertNotNull("should be refused: $name", DisplayName.validate(name))
        }
    }

    @Test
    fun `normalize trims the edges and leaves the middle alone`() {
        assertEquals("Zenek", DisplayName.normalize("  Zenek "))
        // Deliberate: collapsing this would hand somebody a different name from the one they
        // typed, which is the failure the whole rename path is being audited for.
        assertEquals("Jan  Kowalski", DisplayName.normalize(" Jan  Kowalski "))
    }

    @Test
    fun `a rejection says which rule was broken`() {
        val tooShort = DisplayName.validate("a")
        assertNotNull(tooShort)
        // Length is reported for a one-character name even though it breaks the shape rule
        // too: sending somebody to look at the character set for that is a wrong answer.
        assertEquals(
            "Display names are 2 to 24 characters. Yours is 1.",
            (tooShort as AuthError.NameInvalid).body,
        )

        val badShape = DisplayName.validate("-bartek-")
        assertNotNull(badShape)
        val body = (badShape as AuthError.NameInvalid).body
        assertTrue(
            "copy should name the rule, not the constraint: $body",
            "starting and ending" in body,
        )
        assertFalse(
            "and must never quote the constraint at the player: $body",
            "display_name_shape" in body,
        )
    }
}
