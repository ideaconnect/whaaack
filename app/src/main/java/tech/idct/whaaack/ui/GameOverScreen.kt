package tech.idct.whaaack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.RunSummary
import tech.idct.whaaack.data.LeaderboardRepository
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline

@Composable
fun GameOverScreen(
    run: RunSummary,
    signedIn: Boolean,
    adsAvailable: Boolean,
    onPlayAgain: () -> Unit,
    onHome: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                // Ending the run yourself is not losing it, and used to be reported as if
                // three fruit had escaped.
                if (run.quit) "Run ended." else "Three escaped.",
                color = Cream,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x66140A1A))
                    .border(1.dp, Hairline, RoundedCornerShape(28.dp))
                    .padding(vertical = 22.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "MILLISECONDS SURVIVED",
                    color = Color(0x8AFFF3E6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    LeaderboardRepository.formatScore(run.millis),
                    color = AccentLight,
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    StatColumn(run.hits.toString(), "HITS")
                    StatColumn(run.topSpeed.toString(), "TOP SPEED")
                    StatColumn(LeaderboardRepository.formatScore(run.personalBest), "YOUR BEST")
                }
            }

            if (run.ranked && run.newRank != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0x4DB98BD9), Color(0x2EFFC97A)),
                            )
                        )
                        .border(1.dp, Color(0x38FFF3E6), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFB98BD9), Color(0xFF8E5CB8)),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "#${run.newRank}",
                            color = Color(0xFF2A1633),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Global rank #${run.newRank}",
                            color = Cream,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "Your best run is on the board.",
                            color = Color(0xADFFF3E6),
                            fontSize = 12.sp,
                        )
                    }
                }
            } else if (!run.ranked) {
                Spacer(Modifier.height(16.dp))
                // Both cases explain the missing rank, but only someone without an account
                // should be invited to make one — a signed-in player who chose a casual run
                // was being told to sign in on top of the account they already have.
                val invite = !signedIn
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x57140A1A))
                        .border(1.dp, Color(0x47FFF3E6), RoundedCornerShape(24.dp))
                        .then(if (invite) Modifier.clickableOnce(true, onSignIn) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("🏆", fontSize = 20.sp)
                    Text(
                        if (invite) {
                            "Casual run — not ranked. Sign in to put your scores on the board."
                        } else {
                            "Casual run — not ranked. Play ranked to put a score on the board."
                        },
                        color = Color(0xCCFFF3E6),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        PrimaryButton("Play again", onClick = onPlayAgain)
        Spacer(Modifier.height(14.dp))
        SecondaryButton("Back to home", height = 52, onClick = onHome)
        if (adsAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                // "May", not "will": ads are capped to one every couple of minutes, and are
                // skipped outright when nothing has filled. Promising one that then does not
                // arrive is a worse look than not promising it.
                "AD MAY PLAY BEFORE NEXT SCREEN",
                color = Color(0x59FFF3E6),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Cream, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = Color(0x80FFF3E6),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}
