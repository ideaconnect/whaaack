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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.AuthMode
import tech.idct.whaaack.UiState
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
    onForgot: () -> Unit,
    onSkip: () -> Unit,
) {
    val signUp = state.authMode == AuthMode.SIGN_UP
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        CircleIconButton("‹", onBack)
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
            )
            Spacer(Modifier.height(14.dp))
        }

        FieldLabel("Email")
        WhaaackField(
            value = email,
            onValueChange = { email = it },
            placeholder = "you@example.com",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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
            isPassword = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (signUp) {
            Spacer(Modifier.height(6.dp))
            Text(
                "At least 8 characters, including a number.",
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (state.busy) "Working…" else if (signUp) "Create account" else "Sign in",
            height = 58,
            enabled = !state.busy,
            onClick = {
                if (signUp) onSignUp(email, password, displayName) else onSignIn(email, password)
            },
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
                .clickableOnce(true, onSkip)
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

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        CircleIconButton("‹", onBack)

        if (sentTo == null) {
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (state.busy) "Sending…" else "Send reset link",
                height = 58,
                enabled = !state.busy,
                onClick = { onSend(email) },
            )
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
