package tech.idct.whaaack.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.UiState
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.data.LeaderboardRepository
import tech.idct.whaaack.game.Fruit
import tech.idct.whaaack.game.GameAssets
import tech.idct.whaaack.ui.theme.AccentInk
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline

@Composable
fun HomeScreen(
    state: UiState,
    assets: GameAssets?,
    onPlayRanked: () -> Unit,
    onPlayCasual: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onLeaderboard: () -> Unit,
    onAchievements: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onRemoveAds: () -> Unit,
) {
    // Home is a hero, an account card, two buttons, a chip row and sometimes an upsell. Standing
    // them in a Column that fills the screen is right until the screen is a landscape phone — about
    // 360dp tall — where a Column that cannot scroll does not shrink, it clips, and what gets
    // clipped is the bottom: the buttons.
    //
    // Below the threshold the screen scrolls instead, and the hero gives up its weight: a weighted
    // child cannot be measured inside a scrolling parent, which hands its children an infinite
    // height (the same reason the reset form scrolls on its inner branch, see ForgotPasswordScreen).
    val short = isShortScreen()
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .displayCutoutPadding()
            .then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .menuColumnWidth()
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CircleIconButton("⚙", onSettings)
        }

        Column(
            if (short) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (short) Spacer(Modifier.height(10.dp))
            Text(
                "Whaaack!",
                color = Cream,
                fontSize = if (short) 44.sp else 62.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x99091428))
                    .border(1.dp, Color(0x29FFF3E6), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "SURVIVE THE ORCHARD",
                    color = Cream,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FruitChip(assets, Fruit.STRAWBERRY)
                FruitChip(assets, Fruit.APPLE)
                FruitChip(assets, Fruit.WATERMELON)
            }
        }

        state.player?.let { player ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x57140A1A))
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFFC46B), Color(0xFFE2574C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(player.initial, color = AccentInk, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(player.displayName, color = Cream, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    Text(
                        playerBestLine(state),
                        color = Color(0x9EFFF3E6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x33FFF3E6), RoundedCornerShape(12.dp))
                        .clickableOnce(true, onLogout)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text("Log out", color = Color(0x99FFF3E6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Which pair of buttons is correct depends on the session, so until that is known
        // the honest thing to show is neither.
        if (!state.sessionResolved) {
            SessionLoader()
        } else if (state.offersRanked) {
            // Also the pair a Play Games player sees before they have an account: theirs can
            // be made from the identity they already have, so "sign in first" would be asking
            // them to do something we can do for them. The tap raises the invitation.
            PrimaryButton("Play ranked", onClick = onPlayRanked)
            Spacer(Modifier.height(14.dp))
            SecondaryButton("Play for fun", onClick = onPlayCasual)
        } else {
            PrimaryButton("Play for fun", onClick = onPlayCasual)
            Spacer(Modifier.height(14.dp))
            SecondaryButton("Sign in to play ranked", onClick = onSignIn)
        }

        Spacer(Modifier.height(14.dp))
        // Two chips at most, ever: a third splits the row into thirds and the labels start
        // wrapping on a narrow phone. So the second slot is contested, and "Create account"
        // wins it — someone with no account is being offered the ranked half of the game,
        // which is worth more to them than a shortcut to a screen Settings also reaches.
        // Once they have one, that slot is free for the achievements.
        val offerAccount = state.sessionResolved && !state.signedIn
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeChipButton("🏆  Leaderboard", Modifier.weight(1f), onLeaderboard)
            if (offerAccount) {
                HomeChipButton("Create account", Modifier.weight(1f), onCreateAccount)
            } else if (state.playGamesAuthenticated == true) {
                HomeChipButton("🎖  Achievements", Modifier.weight(1f), onAchievements)
            }
        }

        // Only once Play has confirmed there is something to sell *and* that this player has
        // not already bought it. Until then it is simply absent — no greyed-out teaser, and
        // nothing at all for someone who already paid.
        if (state.canBuyRemoveAds) {
            Spacer(Modifier.height(10.dp))
            RemoveAdsButton(price = state.removeAdsPrice.orEmpty(), onClick = onRemoveAds)
        }
    }
}

/**
 * The line under the player's name on the account card.
 *
 * "Best" means their *ranked* best — the number the leaderboard would show them — so it is
 * printed only once the server has confirmed one for the all-time board. Everything else is
 * labelled as the casual best it actually is, and carries no rank.
 *
 * The distinction is the whole point. `prefs.localBestMillis` counts every run played on this
 * device, including runs played for fun and runs played by whoever held the phone before this
 * account signed in, and it deliberately outlives a log-out. Printing it as "Best" under a
 * name was what made a freshly created account claim 46 seconds it had never played.
 */
internal fun playerBestLine(state: UiState): String {
    val ranked = state.standing?.takeIf { state.standingScope == BoardScope.ALL_TIME }
    if (ranked != null) {
        val best = LeaderboardRepository.formatScore(ranked.millis)
        return "Best $best · Rank #${ranked.rank}"
    }
    val local = state.prefs.localBestMillis
    if (local <= 0L) return "No runs yet"
    return "Casual best ${LeaderboardRepository.formatScore(local)}"
}

/**
 * Stands in for the two play buttons while the session is being read, at exactly their
 * combined height (62 + 8 lift, a 14 gap, then 54) so the real ones drop into place rather
 * than shoving the screen around.
 */
@Composable
private fun SessionLoader() {
    val transition = rememberInfiniteTransition(label = "session")
    Box(Modifier.fillMaxWidth().height(138.dp), contentAlignment = Alignment.Center) {
        Row(
            // The same dark pill the tagline uses: cream on its own washes out against the
            // orchard, and this sits over the busiest part of it.
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x99091428))
                .border(1.dp, Color(0x29FFF3E6), RoundedCornerShape(999.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        // Staggered starts turn three pulsing dots into one travelling wave.
                        animation = tween(520, delayMillis = index * 150, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AccentLight.copy(alpha = alpha)),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Checking your account…",
                color = Cream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FruitChip(assets: GameAssets?, fruit: Fruit) {
    // asImageBitmap allocates a wrapper each call, so it is cached like the backdrop's.
    val image = remember(assets, fruit) { assets?.fruits?.get(fruit)?.asImageBitmap() }
    if (image == null) {
        Box(Modifier.size(28.dp))
        return
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

@Composable
private fun HomeChipButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x57140A1A))
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickableOnce(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
