package tech.idct.whaaack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.idct.whaaack.UiState
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.data.Player
import tech.idct.whaaack.data.Preferences
import tech.idct.whaaack.data.Standing

/**
 * Two lines of text — the Home card's and the leaderboard footer's — both of which used to
 * present `prefs.localBestMillis` as the player's ranked score.
 *
 * That number is the *device's*: it records casual runs as well as ranked ones, and it
 * survives a log-out on purpose, because a log-out is not a delete. So on any phone that had
 * been played before, a brand-new account was greeted with somebody else's 46 seconds — with
 * a rank of "—" beside it, which is the tell. The rule these tests hold to is that a score is
 * only ever shown as ranked when the server has confirmed it, for this account, for the
 * window on screen; anything else is either labelled casual or shown as nothing at all.
 */
class OwnScoreTest {

    private val player = Player(
        userId = "u-1",
        email = "player@example.com",
        displayName = "Bolek",
        provider = "email",
    )

    /** The device has a 46-second casual best on it, as the reported phone did. */
    private val played = Preferences(localBestMillis = 46_000L)

    private fun signedIn(
        standing: Standing? = null,
        standingScope: BoardScope? = null,
        boardScope: BoardScope = BoardScope.ALL_TIME,
        prefs: Preferences = played,
    ) = UiState(
        player = player,
        sessionResolved = true,
        prefs = prefs,
        boardScope = boardScope,
        standing = standing,
        standingScope = standingScope,
    )

    // ---- the reported bug -----------------------------------------------------------

    @Test
    fun `a new account on a played-on phone is never credited with the device's best`() {
        // The server has answered: this account has no ranked run. It is not a gap.
        val state = signedIn(standing = null, standingScope = BoardScope.ALL_TIME)

        val row = ownStandingRow(state)
        assertEquals("no rank to show", "—", row.rank)
        assertEquals("and no score either", "—", row.score)
        assertEquals("said out loud", "No ranked run yet", row.note)
        assertEquals("Bolek (you)", row.name)

        assertEquals(
            "Home labels the device's number for what it is, and gives it no rank",
            "Casual best 46,000",
            playerBestLine(state),
        )
    }

    @Test
    fun `the weekly board says which window is empty`() {
        val state = signedIn(standingScope = BoardScope.WEEKLY, boardScope = BoardScope.WEEKLY)
        assertEquals("No ranked run this week", ownStandingRow(state).note)
    }

    // ---- a player who has actually ranked ---------------------------------------------

    @Test
    fun `a confirmed standing is shown as rank and score`() {
        val state = signedIn(
            standing = Standing(rank = 12, millis = 128_940, totalPlayers = 400),
            standingScope = BoardScope.ALL_TIME,
        )

        val row = ownStandingRow(state)
        assertEquals("#12", row.rank)
        assertEquals("128,940", row.score)
        assertNull("nothing needs explaining", row.note)

        assertEquals("Best 128,940 · Rank #12", playerBestLine(state))
    }

    // ---- the states that used to be indistinguishable from "no ranked run" -------------

    @Test
    fun `an unanswered standing shows nothing rather than guessing`() {
        // Nobody has asked yet, or the ask failed: standingScope is null either way.
        val state = signedIn(standing = null, standingScope = null)

        val row = ownStandingRow(state)
        assertEquals("—", row.rank)
        assertEquals("—", row.score)
        assertNull("must not accuse a ranked player of having no ranked run", row.note)

        // Home still has something honest to say while it waits.
        assertEquals("Casual best 46,000", playerBestLine(state))
    }

    @Test
    fun `a standing for the other window is not shown under this one`() {
        val weekly = Standing(rank = 2, millis = 51_000, totalPlayers = 9)
        val state = signedIn(
            standing = weekly,
            standingScope = BoardScope.WEEKLY,
            boardScope = BoardScope.ALL_TIME,
        )

        val row = ownStandingRow(state)
        assertEquals("a real number about the wrong board", "—", row.rank)
        assertEquals("—", row.score)
        assertNull("we have not been told about this window at all", row.note)

        assertEquals(
            "and Home, which speaks for the all-time board, does not borrow it either",
            "Casual best 46,000",
            playerBestLine(state),
        )
    }

    // ---- the cases that were already right, held in place -------------------------------

    @Test
    fun `signed out, the casual best is still shown - as nobody's rank`() {
        val state = UiState(sessionResolved = true, prefs = played)

        val row = ownStandingRow(state)
        assertEquals("—", row.rank)
        assertEquals("Sign in to rank", row.name)
        assertEquals("46,000", row.score)
        assertNull(row.note)
    }

    @Test
    fun `a fresh install has no number anywhere`() {
        val state = signedIn(standingScope = BoardScope.ALL_TIME, prefs = Preferences())
        assertEquals("—", ownStandingRow(state).score)
        assertEquals("No runs yet", playerBestLine(state))
    }
}
