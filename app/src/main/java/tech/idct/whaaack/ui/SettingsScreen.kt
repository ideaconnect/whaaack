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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.UiState
import tech.idct.whaaack.ui.theme.AccentInk
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.DangerSoft
import tech.idct.whaaack.ui.theme.Hairline

private enum class Sheet { NONE, NAME, EMAIL, PASSWORD, DELETE }

@Composable
fun SettingsScreen(
    state: UiState,
    privacyOptionsRequired: Boolean,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleMusic: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleParallax: (Boolean) -> Unit,
    onPrivacyOptions: () -> Unit,
    onRemoveAds: () -> Unit,
    onRestorePurchases: () -> Unit,
    onChangeName: (String) -> Unit,
    onChangeEmail: (String) -> Unit,
    onChangePassword: (String) -> Unit,
    onDeleteAccount: () -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit,
) {
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            ScreenHeader("Settings", onBack)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleRow(
                    "Sound effects", "Splats, pops and the whistle",
                    state.prefs.sound, onToggleSound,
                )
                ToggleRow("Music", "Menu and gameplay loops", state.prefs.music, onToggleMusic)
                ToggleRow("Haptics", "Buzz on every hit", state.prefs.haptics, onToggleHaptics)
                ToggleRow(
                    "Parallax background", "Turn off to reduce motion",
                    state.prefs.parallax, onToggleParallax,
                )

                Spacer(Modifier.height(10.dp))
                SectionLabel("Ads")
                when {
                    // Already paid: say so, and never show a price again.
                    state.adsRemoved == true -> Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CrownGold.copy(alpha = 0.16f))
                            .border(1.dp, CrownGold.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CrownIcon(color = CrownGold, size = 21.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Ad-free",
                                color = Cream,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                "Thanks for supporting Whaaack!",
                                color = Color(0x9EFFF3E6),
                                fontSize = 11.sp,
                            )
                        }
                    }

                    state.canBuyRemoveAds ->
                        RemoveAdsButton(state.removeAdsPrice.orEmpty(), onClick = onRemoveAds)
                }

                // Always available, even when there is nothing to sell: this is the way back
                // for someone who paid on another device or reinstalled.
                ActionRow(
                    "Restore purchases",
                    "Already bought ad-free? Get it back here",
                    onRestorePurchases,
                )

                if (privacyOptionsRequired) {
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Privacy")
                    ActionRow(
                        "Ad privacy options",
                        "Change your consent for personalised ads",
                        onPrivacyOptions,
                    )
                }

                state.player?.let { player ->
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Account")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0x6B091428))
                            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFFFC46B), Color(0xFFE2574C)),
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                player.initial,
                                color = AccentInk,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                player.displayName,
                                color = Cream,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                if (player.isGoogle) "Google account" else player.email.orEmpty(),
                                color = Color(0x9EFFF3E6),
                                fontSize = 11.sp,
                            )
                        }
                    }

                    ActionRow(
                        "Display name",
                        "Changeable once every 30 days",
                    ) { sheet = Sheet.NAME }

                    if (!player.isGoogle) {
                        ActionRow("Email", player.email.orEmpty()) { sheet = Sheet.EMAIL }
                        ActionRow("Password", "••••••••") { sheet = Sheet.PASSWORD }
                    }

                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Danger zone", DangerSoft)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0x38B22A30))
                            .border(1.5.dp, Color(0x8CFF8A7A), RoundedCornerShape(18.dp))
                            .clickableOnce(true) { sheet = Sheet.DELETE }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Delete my account",
                                color = Color(0xFFFFC7BE),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                "Erases your scores, rank and email. Cannot be undone.",
                                color = Color(0xB3FFF3E6),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                        Text("›", color = DangerSoft, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }

                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Log out", height = 52, onClick = onLogout)
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x8C091428))
                            .border(1.dp, Color(0x3DFFF3E6), RoundedCornerShape(999.dp))
                            .clickableOnce(true, onAbout)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Whaaack! v1.0 · About & credits",
                            color = Color(0xD9FFF3E6),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // The sheet stays up until the request actually resolves. Dismissing it inside
        // onConfirm meant the busy state and any error had nowhere left to appear, so a
        // failed change looked exactly like a successful one.
        LaunchedEffect(state.actionSucceeded) {
            if (sheet != Sheet.NONE) sheet = Sheet.NONE
        }
        val dismissSheet: () -> Unit = {
            sheet = Sheet.NONE
            onClearError()
        }

        when (sheet) {
            Sheet.NAME -> EditSheet(
                title = "Change display name",
                body = "This is the name on the leaderboard. You can change it once every 30 days.",
                fieldLabel = "New display name",
                initial = state.player?.displayName.orEmpty(),
                cta = "Save name",
                busy = state.busy,
                error = state.authError?.body,
                onDismiss = dismissSheet,
                onConfirm = { onChangeName(it) },
            )

            Sheet.EMAIL -> EditSheet(
                title = "Change email",
                body = "We'll send a confirmation link to the new address. " +
                    "Your old one keeps working until you confirm.",
                fieldLabel = "New email",
                initial = "",
                cta = "Send confirmation",
                busy = state.busy,
                error = state.authError?.body,
                onDismiss = dismissSheet,
                onConfirm = { onChangeEmail(it) },
            )

            Sheet.PASSWORD -> EditSheet(
                title = "Change password",
                body = "Use at least 8 characters with one number. You'll stay signed in on this device.",
                fieldLabel = "New password",
                initial = "",
                cta = "Update password",
                isPassword = true,
                busy = state.busy,
                error = state.authError?.body,
                onDismiss = dismissSheet,
                onConfirm = { onChangePassword(it) },
            )

            Sheet.DELETE -> DeleteSheet(
                busy = state.busy,
                error = state.authError?.body,
                onDismiss = dismissSheet,
                onConfirm = { onDeleteAccount() },
            )

            Sheet.NONE -> Unit
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color = Color(0xB3FFF3E6)) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun ActionRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x57140A1A))
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickableOnce(true, onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(hint, color = Color(0x9EFFF3E6), fontSize = 11.sp)
        }
        Text("›", color = Color(0x9EFFF3E6), fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun EditSheet(
    title: String,
    body: String,
    fieldLabel: String,
    initial: String,
    cta: String,
    busy: Boolean,
    error: String?,
    isPassword: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    SheetScaffold(onDismiss) {
        Text(title, color = Cream, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Color(0xBFFFF3E6), fontSize = 12.sp, lineHeight = 17.sp)
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner("That didn't work", error)
        }
        Spacer(Modifier.height(16.dp))
        FieldLabel(fieldLabel)
        WhaaackField(
            value = value,
            onValueChange = { value = it },
            placeholder = fieldLabel,
            isPassword = isPassword,
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (busy) "Working…" else cta,
            height = 54,
            enabled = !busy && value.isNotBlank(),
            onClick = { onConfirm(value) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Cancel",
            color = Color(0xB8FFF3E6),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickableOnce(true, onDismiss).padding(10.dp),
        )
    }
}

@Composable
private fun DeleteSheet(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val armed = typed.trim().equals("DELETE", ignoreCase = false) && !busy

    SheetScaffold(onDismiss, background = Color(0xFF1B1420), borderColor = Color(0x80FF8A7A)) {
        Text("Delete everything?", color = Color(0xFFFFC7BE), fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(
            "This removes your account, your email, and every ranked score. " +
                "We keep nothing. You can still play for fun without an account.",
            color = Color(0xD9FFF3E6),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner("That didn't work", error)
        }
        Spacer(Modifier.height(16.dp))
        FieldLabel("Type DELETE to confirm")
        WhaaackField(value = typed, onValueChange = { typed = it }, placeholder = "DELETE")
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (armed) Color(0xFFB22A30) else Color(0x40B22A30))
                .clickableOnce(armed, onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "Deleting…" else "Delete my account",
                color = if (armed) Cream else Color(0x66FFF3E6),
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Keep my account",
            color = Color(0xB8FFF3E6),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickableOnce(true, onDismiss).padding(10.dp),
        )
    }
}

@Composable
private fun SheetScaffold(
    onDismiss: () -> Unit,
    background: Color = Color(0xFF12233D),
    borderColor: Color = Color(0x2EFFF3E6),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB80C0610))
            .clickableOnce(true, onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(background)
                .border(
                    1.5.dp,
                    borderColor,
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                )
                // Swallow clicks so tapping the sheet does not dismiss it.
                .clickableOnce(true) {}
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x4DFFF3E6)),
                )
            }
            content()
        }
    }
}
