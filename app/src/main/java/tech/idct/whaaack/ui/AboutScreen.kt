package tech.idct.whaaack.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import tech.idct.whaaack.BuildConfig
import tech.idct.whaaack.R
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline

private data class Credit(val title: String, val author: String?, val url: String)

private val CREDITS = listOf(
    Credit("12 Fruit Pack", "by JennPixel", "https://jennpixel.itch.io/fruits-pack-12"),
    Credit("Splat Pack (1.0)", "Created/distributed by Kenney", "https://www.kenney.nl"),
    Credit("Free Summer Pixel Art Backgrounds", null, "https://craftpix.net/file-licenses/"),
    Credit(
        "Calypso and Surf Rock",
        "by DavidKBD",
        "https://davidkbd.itch.io/tropical-dreams-spring-and-summer-music-pack",
    ),
)

// Internal rather than private: the ranked invitation dialog links to the same two documents,
// and a second copy of these strings is a second thing to forget when a URL moves.
internal const val PRIVACY_URL = "https://idct.tech/whaaack/privacy"
internal const val TERMS_URL = "https://idct.tech/whaaack/terms"

/** The studio, not a document — nothing else links here, so it stays private. */
private const val IDCT_URL = "https://idct.tech"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    url.toUri(),
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .displayCutoutPadding()
            .menuColumnWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        ScreenHeader("About", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0x80091428))
                    .border(1.dp, Color(0x33FFF3E6), RoundedCornerShape(22.dp))
                    .padding(vertical = 20.dp, horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Not the launcher foreground: that one is padded out to the
                // adaptive-icon safe zone, so it would draw a third smaller
                // than this box with no way to reclaim the margin. This asset
                // is the same artwork bled to its own edges.
                Image(
                    painter = painterResource(R.drawable.logo_whaaack),
                    contentDescription = "Whaaack! logo",
                    modifier = Modifier.size(132.dp),
                )
                Text("Whaaack!", color = Cream, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(
                    // Read from the build, not typed twice: bumping versionName in Gradle
                    // used to leave both this and the Settings footer still claiming 1.0,
                    // and with no crash reporter the version a player reads off this screen
                    // is the only way to know which build a bug report came from. The code
                    // is what Play Console and Android vitals key on, so show that too.
                    // The byline that used to hang off this line is the card below now, which
                    // says the same thing with the logo and a link on it.
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = Color(0xB8FFF3E6),
                    fontSize = 12.sp,
                )
            }

            LinkRow("Built by IDCT", "idct.tech", icon = R.drawable.logo_idct) { open(IDCT_URL) }

            // Both documents, not just the privacy policy: Play expects the terms to be
            // reachable from inside the app once there is something to buy.
            LinkRow("Privacy policy", "idct.tech/whaaack/privacy") { open(PRIVACY_URL) }
            LinkRow("Terms & conditions", "idct.tech/whaaack/terms") { open(TERMS_URL) }

            Text(
                "ASSETS USED",
                color = Color(0xB3FFF3E6),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.6.sp,
                modifier = Modifier.padding(start = 4.dp),
            )

            CREDITS.forEach { credit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x57140A1A))
                        .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                        .clickableOnce(true) { open(credit.url) }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(credit.title, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    credit.author?.let {
                        Text(it, color = Color(0xB8FFF3E6), fontSize = 12.sp)
                    }
                    Text(
                        credit.url.removePrefix("https://"),
                        color = AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x8C091428))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("no moles were involved", color = Color(0xB8FFF3E6), fontSize = 11.sp)
            }
        }
    }
}

/**
 * One tappable row pointing at a page on the website, with an optional mark in front of it.
 *
 * [icon] is tinted rather than drawn as it comes, because the only thing that uses it is the
 * IDCT logo: a black silhouette with its highlights punched through as holes, which on this
 * screen's near-black cards would be a row with a gap where the logo should be. Cream is what
 * the rest of the row is written in, and the holes let the card show through, so the mark
 * reads the way it does on any dark surface rather than needing a white patch behind it.
 */
@Composable
private fun LinkRow(
    title: String,
    url: String,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x57140A1A))
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickableOnce(true, onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Image(
                painter = painterResource(it),
                // Named on the row it belongs to: a screen reader reaching this reads the
                // title and url either way, and "IDCT logo" before them would be noise.
                contentDescription = null,
                colorFilter = ColorFilter.tint(Cream),
                // Sized off the wordmark under the squirrel rather than the row: the mark is a
                // lockup, and at anything smaller the IDCT below it stops being legible before
                // the emblem above it does. This is about as tall as the two lines beside it,
                // so it does not drive the row's height either way.
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(url, color = Color(0x9EFFF3E6), fontSize = 11.sp)
        }
        Text("↗", color = AccentLight, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}
