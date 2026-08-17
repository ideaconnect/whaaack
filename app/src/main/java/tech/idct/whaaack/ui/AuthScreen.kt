package tech.idct.whaaack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.AuthMode
import tech.idct.whaaack.UiState
import tech.idct.whaaack.data.DisplayName
import tech.idct.whaaack.ui.theme.AccentInk
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.Cream

@Composable
fun AuthScreen(
    state: UiState,
    googleAvailable: Boolean,
    onBack: () -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onGoogle: () -> Unit,
    playGamesAvailable: Boolean,
    onPlayGames: () -> Unit,
    onForgot: () -> Unit,
    onSkip: () -> Unit,
) {
    val signUp = state.authMode == AuthMode.SIGN_UP
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    // Each field hands focus to the next one by name rather than through
    // FocusDirection.Next: "Forgot your password?" sits between the email and the password in
    // layout order and is clickable, which makes it focusable, so the automatic traversal
    // stops on a link rather than on the field the player is heading for.
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Drops the keyboard before submitting: what comes back is either an error banner near
    // the top of this form or a change of screen, and neither is visible from behind the IME.
    // Hiding it rather than clearing focus, deliberately — clearFocus() hands the focus back
    // to the root, which re-runs traversal from the top of the screen and scrolls the form
    // away from the button that was just pressed.
    fun submit() {
        keyboard?.hide()
        if (signUp) onSignUp(email, password, displayName) else onSignIn(email, password)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // Outside the verticalScroll, so an open keyboard shrinks the scrolling viewport
            // instead of covering it. The window is edge-to-edge (see enableEdgeToEdge in
            // MainActivity), so on API 30+ nothing else moves this content out from under the
            // IME — without it the Column stayed full-height, had nothing to scroll, and the
            // password field and the submit button below it were simply unreachable until the
            // keyboard was dismissed. It also gives the focused field somewhere to scroll
            // itself into. systemBarsPadding above has already consumed the navigation bar
            // inset, so the two do not stack.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        // Held while a sign-in is in flight, same as the Skip control below: the busy flag
        // disables the provider buttons, but an exit that stays live re-opens the mint offer
        // on Home while the first attempt is still on the wire.
        CircleIconButton("‹", onBack, enabled = !state.busy)
        Spacer(Modifier.height(14.dp))
        Text(
            if (signUp) "Create account" else "Welcome back",
            color = Cream,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "Ranked runs need an account. Casual play never does.",
            color = Color(0xB8FFF3E6),
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.height(16.dp))
        SegmentedTabs(
            options = listOf("Sign in", "Create account"),
            selectedIndex = if (signUp) 1 else 0,
            onSelect = { onModeChange(if (it == 1) AuthMode.SIGN_UP else AuthMode.SIGN_IN) },
        )

        Spacer(Modifier.height(14.dp))
        GoogleSignInButton(
            label = if (signUp) "Sign up with Google" else "Sign in with Google",
            enabled = googleAvailable && !state.busy,
            onClick = onGoogle,
        )
        if (!googleAvailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Google sign-in isn't available right now — use email instead.",
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
            )
        }

        // Offered whether or not Play Games has signed this player in: the button raises that
        // sign-in first when it has to, then trades the result for a Whaaack! account. It used
        // to be shown only to an already-authenticated player, which turned a dismissed launch
        // prompt — the SDK offers exactly one, and it lands over whatever the player was doing
        // — into a missing provider on the one screen that is about picking a provider, with
        // the way back buried in Settings and nothing here saying so.
        if (playGamesAvailable) {
            Spacer(Modifier.height(10.dp))
            PlayGamesSignInButton(
                // Same label either way, and it is honest in both: this creates the account if
                // there isn't one and signs into it if there is, with nothing for the player
                // to fill in. The "Create account" tab has no bearing on it.
                label = "Continue with Play Games",
                enabled = !state.busy,
                onClick = onPlayGames,
            )
            Spacer(Modifier.height(6.dp))
            // One sentence for both states, because the button no longer knows which it is in
            // until it is pressed — and copy that changed under the player as the SDK resolved
            // would be the flicker the null check everywhere else exists to prevent.
            Text(
                "Signs you in with your Play Games profile, asking you for it first if you're " +
                    "not signed in. That name will appear on the leaderboard.",
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x47FFF3E6)))
            Text(
                "OR",
                color = Color(0xB3FFF3E6),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x47FFF3E6)))
        }

        state.authError?.let { error ->
            Spacer(Modifier.height(14.dp))
            ErrorBanner(error.title, error.body)
        }

        Spacer(Modifier.height(14.dp))
        if (signUp) {
            FieldLabel("Display name")
            WhaaackField(
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = "Shown on the leaderboard",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
            )
            Spacer(Modifier.height(6.dp))
            // The same rules the database holds, said before they can be broken rather than
            // after — and worth the line here because a name that breaks them used to be
            // quietly replaced at signup rather than refused.
            Text(
                DisplayName.HINT,
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))
        }

        FieldLabel("Email")
        WhaaackField(
            value = email,
            onValueChange = { email = it },
            placeholder = "you@example.com",
            modifier = Modifier.focusRequester(emailFocus),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { FieldLabel("Password") }
            if (!signUp) {
                Text(
                    "Forgot your password?",
                    color = AccentLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clickableOnce(true, onForgot)
                        .padding(bottom = 6.dp),
                )
            }
        }
        WhaaackField(
            value = password,
            onValueChange = { password = it },
            placeholder = "••••••••",
            modifier = Modifier.focusRequester(passwordFocus),
            isPassword = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            // The last field submits, like the button does — and is held during a request for
            // the same reason the button is disabled.
            keyboardActions = KeyboardActions(onDone = { if (!state.busy) submit() }),
        )
        if (signUp) {
            Spacer(Modifier.height(6.dp))
            Text(
                "At least 8 characters, with a letter and a number.",
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (state.busy) "Working…" else if (signUp) "Create account" else "Sign in",
            height = 58,
            enabled = !state.busy,
            onClick = { submit() },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "Skip — just play for fun",
            color = Color(0xB3FFF3E6),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                // Held while a sign-in is in flight, like every other control here: leaving
                // for the game mid-round-trip meant the completion landed on top of a live
                // run. The ViewModel also refuses to navigate a player who left — this is
                // the belt to that braces.
                .clickableOnce(!state.busy, onSkip)
                .padding(12.dp),
        )
    }
}

@Composable
fun ForgotPasswordScreen(
    state: UiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onDone: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    val sentTo = state.resetEmailSent
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        keyboard?.hide()
        onSend(email)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // Same reason as the sign-in form above: edge-to-edge means the keyboard overlays
            // the window rather than resizing it, so the space left for the content has to be
            // taken here or the send button ends up behind the IME on a short screen.
            .imePadding()
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        CircleIconButton("‹", onBack)

        if (sentTo == null) {
            // The scroll goes on this branch rather than on the Column above, because the
            // confirmation branch below centres itself with a weight — and a weight cannot be
            // measured inside a scrolling parent, whose children get an infinite height.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(16.dp))
                Text("Reset password", color = Cream, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Tell us the email on your account and we'll send a reset link. " +
                        "It expires in 30 minutes.",
                    color = Color(0xB8FFF3E6),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                state.authError?.let {
                    Spacer(Modifier.height(16.dp))
                    ErrorBanner(it.title, it.body)
                }

                Spacer(Modifier.height(20.dp))
                FieldLabel("Email")
                WhaaackField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "you@example.com",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (!state.busy) submit() }),
                )

                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    text = if (state.busy) "Sending…" else "Send reset link",
                    height = 58,
                    enabled = !state.busy,
                    onClick = { submit() },
                )
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Brush.linearGradient(listOf(AccentLight, Color(0xFFF2704F)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✉", fontSize = 34.sp, color = AccentInk)
                }
                Spacer(Modifier.height(14.dp))
                Text("Check your inbox", color = Cream, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(
                    "We sent a reset link to $sentTo. The link works once and expires in 30 minutes.",
                    color = Color(0xD1FFF3E6),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                SecondaryButton("Back to sign in", onClick = onDone)
            }
        }
    }
}
