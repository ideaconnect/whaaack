package tech.idct.whaaack.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tech.idct.whaaack.R
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline

/**
 * The heads-up before an interstitial: an ad is about to play, and here is the one-time
 * purchase that stops it happening again.
 *
 * Raised only when both of those are actually true — an ad is loaded, uncapped and consented
 * to, and Play has something to sell this player. The gate is in the ViewModel rather than
 * here so this composable stays a plain piece of UI, but it is the whole reason the prompt is
 * not a nuisance: it can only appear immediately before advertising the player was going to
 * see anyway, at most once every couple of minutes, which is the interstitial cap.
 *
 * Dismissal is [onCancel], not [onContinue]: an accidental back gesture must not be answered
 * with a full-screen ad. See `WhaaackViewModel.cancelAdBreak`.
 */
@Composable
fun AdBreakDialog(
    price: String,
    onBuy: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        // The panel sets its own width; the platform default is a narrow box that would
        // squeeze the badge and wrap the buttons onto three lines each.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Filled to the dialog window and centred inside it, so the panel below is measured
        // against the height that actually exists. Without this the panel is measured unbounded:
        // in landscape it grew past the bottom of the screen and took Continue with it, and the
        // scroll it carries for large font scales had nothing to scroll against.
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 400.dp)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(28.dp))
                    // Near-opaque, unlike the menu panels: this sits over the game-over screen
                    // *and* the orchard, and cream body text over both is not readable.
                    .background(Color(0xF7091428))
                    .border(1.dp, Hairline, RoundedCornerShape(28.dp))
                    // A dialog cannot grow past the window and does not scroll on its own, so at
                    // a large system font scale the buttons would simply be cut off the bottom —
                    // with the navigation the player asked for waiting behind them.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    Image(
                        // Decorative: the heading below says everything this says.
                        painter = painterResource(R.drawable.badge_ad_free),
                        contentDescription = null,
                        modifier = Modifier.size(104.dp),
                    )
                }

                Text(
                    // "May", not "will", and for the same reason as the game-over caption: an ad
                    // that is loaded can still fail to present, and a promise that does not
                    // arrive is worse than no promise.
                    "An ad may play next",
                    color = Cream,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Text(
                    // Not "it goes straight to the developer", which was the first draft: Google
                    // is the seller of record and takes its cut, and the terms on the website say
                    // so. A sentence in a purchase prompt that the terms contradict is the sort
                    // of thing that turns a refund dispute into a complaint.
                    "Whaaack! is free to play, so a short ad helps keep it going. " +
                        "Prefer none at all? Buy the ad-free unlock once and that is the last " +
                        "one you will see — and it supports the developer.",
                    color = Color(0xC7FFF3E6),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                )

                // The same control Home and Settings offer, so the unlock is one recognisable
                // thing in three places rather than three near-identical buttons.
                //
                // Guarded on the price rather than assumed: the prompt is only raised when Play
                // has quoted one, but the dialog outlives that instant, and a priceless row would
                // read as "Remove ads · <nothing>" over a button that cannot open a sheet.
                if (price.isNotBlank()) {
                    RemoveAdsButton(price = price, subtitle = "One-time purchase", onClick = onBuy)
                }

                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton("Continue", height = 48, onClick = onContinue)
                    Text(
                        "No purchase needed — this just carries on.",
                        color = Color(0x73FFF3E6),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
