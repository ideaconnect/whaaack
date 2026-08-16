package tech.idct.whaaack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tech.idct.whaaack.RankedInvite
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline

/**
 * Offered to a Play Games player who has just asked for their first ranked run and has no
 * Whaaack! account.
 *
 * They do not need to sign up — the account can be made from the Play Games identity they
 * already have — so what is left to ask about is the only consequence they cannot undo by
 * themselves: their name becomes public. That is the whole reason accounts are minted from
 * this tap rather than at Play Games sign-in, where there would be no moment to say it and no
 * question to answer.
 *
 * So the copy leads with the leaderboard, not with the account. "We'll create an account for
 * you" is our plumbing; "your name will be on a public board" is the part that is theirs.
 *
 * Dismissal is [onDecline]: a back gesture must not create an account, and there is a "Play
 * for fun" button behind this dialog that needs no account at all.
 */
@Composable
fun RankedInviteDialog(
    state: RankedInvite,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val working = state == RankedInvite.WORKING
    Dialog(
        // Ignored while the round trip is in flight: it is two network hops that cannot be
        // cancelled cleanly, and a dialog that vanishes mid-way would leave the player on Home
        // with an account quietly appearing behind them.
        onDismissRequest = { if (!working) onDecline() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF7091428))
                .border(1.dp, Hairline, RoundedCornerShape(28.dp))
                // Same reasoning as the ad break: a dialog cannot outgrow the window, and at a
                // large font scale the buttons would otherwise be cut off the bottom.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Play ranked with Play Games",
                color = Cream,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )

            Text(
                // Names the visible consequence first, and does not promise a specific name:
                // the display name is derived from the Play Games profile server-side and
                // de-duplicated against the board, so it can differ from their gamer tag.
                "Ranked runs go on the public leaderboard, where your Play Games name and " +
                    "your best time can be seen by other players.\n\n" +
                    "We'll set up your Whaaack! account from Play Games — there's nothing to " +
                    "fill in. You can rename yourself or delete the account in Settings at " +
                    "any time.",
                color = Color(0xC7FFF3E6),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton(
                    if (working) "Setting up…" else "Play ranked",
                    height = 56,
                    // Both halves matter: the button must not fire twice, and the label has to
                    // change, because two networks in sequence can take a few seconds and a
                    // still button under an unchanged label reads as one that missed the tap.
                    enabled = !working,
                    onClick = onAccept,
                )
                if (!working) {
                    SecondaryButton("Not now", height = 48, onClick = onDecline)
                    Text(
                        "Playing for fun needs no account, and stays off the board.",
                        color = Color(0x73FFF3E6),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
