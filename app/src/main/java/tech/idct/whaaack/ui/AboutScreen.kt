package tech.idct.whaaack.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
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

private const val PRIVACY_URL = "https://idct.tech/whaaack/privacy"

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
                    "Version 1.0 · built by IDCT",
                    color = Color(0xB8FFF3E6),
                    fontSize = 12.sp,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x57140A1A))
                    .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                    .clickableOnce(true) { open(PRIVACY_URL) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Privacy policy", color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text("idct.tech/whaaack/privacy", color = Color(0x9EFFF3E6), fontSize = 11.sp)
                }
                Text("↗", color = AccentLight, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }

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
