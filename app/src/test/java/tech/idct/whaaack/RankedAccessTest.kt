package tech.idct.whaaack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.whaaack.data.Player

/**
 * Who is offered ranked play, who can have an account made for them, and who is shown Play
 * Games as a way in.
 *
 * Home shows one of two pairs of buttons, and the choice used to be "signed in or not". A
 * Play Games player can now be given an account from the identity they already hold, so they
 * see the ranked pair too — but only when every part of that is actually true. Each guard
 * here fails in a way that is invisible until a player hits it: an unresolved session flashes
 * the wrong buttons at launch, an unconfigured build offers a sign-in that cannot complete,
 * and minting for somebody already signed in would quietly split their scores across two
 * accounts.
 *
 * The auth screen's guard is the odd one out and the reason it is tested here: it looks like
 * the ranked ones and is deliberately weaker, because that button can fix its own precondition.
 */
class RankedAccessTest {

    private val player = Player(
        userId = "u-1",
        email = "player@example.com",
        displayName = "Bolek",
        provider = "email",
    )

    /** A Play Games player, no Whaaack! account, on a build that can mint one. */
    private val playGamesOnly = UiState(
        sessionResolved = true,
        player = null,
        playGamesAuthenticated = true,
        playGamesOnDevice = true,
        playGamesRankingAvailable = true,
    )

    @Test
    fun `a play games player with no account is offered ranked play`() {
        assertTrue(playGamesOnly.canMintPlayGamesAccount)
        assertTrue(playGamesOnly.offersRanked)
    }

    @Test
    fun `a signed-in player is offered ranked play and never minted a second account`() {
        val state = playGamesOnly.copy(player = player)
        assertTrue("still ranked", state.offersRanked)
        assertFalse(
            "minting for somebody who already has an account would split their scores",
            state.canMintPlayGamesAccount,
        )
    }

    @Test
    fun `nothing is offered before the session has resolved`() {
        // The launch flash this mirrors sessionResolved for: guessing here shows one pair of
        // buttons and then replaces it with the other.
        val state = playGamesOnly.copy(sessionResolved = false)
        assertFalse(state.canMintPlayGamesAccount)
        assertFalse(state.offersRanked)
    }

    @Test
    fun `an unconfigured build leaves ranked play behind a sign-up, as before`() {
        val state = playGamesOnly.copy(playGamesRankingAvailable = false)
        assertFalse("no Game server credential, so no code can be exchanged", state.offersRanked)
    }

    @Test
    fun `play games not yet resolved is not treated as signed in to it`() {
        // null means the SDK's automatic attempt has not answered, and is deliberately
        // distinct from false — offering the ranked pair and withdrawing it is worse than
        // waiting a beat for the answer.
        assertFalse(playGamesOnly.copy(playGamesAuthenticated = null).offersRanked)
        assertFalse(playGamesOnly.copy(playGamesAuthenticated = false).offersRanked)
    }

    @Test
    fun `the auth screen offers play games to a player who is not signed in to it`() {
        // The dead end this exists to stop. v2 prompts once, at launch, over whatever the
        // player was doing; dismissing it used to remove Play Games from the provider list
        // entirely, with the only way back a Settings row nothing pointed at. The button now
        // raises that prompt itself, so neither state may hide it.
        assertTrue(playGamesOnly.copy(playGamesAuthenticated = false).offersPlayGamesSignIn)
        assertTrue(playGamesOnly.copy(playGamesAuthenticated = null).offersPlayGamesSignIn)
    }

    @Test
    fun `the auth screen offers play games only where there is any to offer`() {
        assertFalse(
            "no Game server credential, so no code could be exchanged for an account",
            playGamesOnly.copy(playGamesRankingAvailable = false).offersPlayGamesSignIn,
        )
        assertFalse(
            "no Play Games on the device, so the prompt behind the button cannot be raised",
            playGamesOnly.copy(playGamesOnDevice = false).offersPlayGamesSignIn,
        )
        assertFalse(
            "and not before the SDK has answered once, which is what null means here",
            playGamesOnly.copy(playGamesOnDevice = null).offersPlayGamesSignIn,
        )
    }

    @Test
    fun `a minted account holds no password and no reachable address`() {
        val minted = player.copy(provider = "play_games")
        assertTrue(minted.isPlayGames)
        assertFalse("nothing may offer to change a password it does not have", minted.hasPassword)
        assertFalse("and it is not a Google account either", minted.isGoogle)

        // The two federated kinds agree on the part Settings gates on.
        assertFalse(player.copy(provider = "google").hasPassword)
        assertTrue("an email signup keeps both rows", player.hasPassword)
    }
}
