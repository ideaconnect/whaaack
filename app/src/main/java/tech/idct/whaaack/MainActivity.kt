package tech.idct.whaaack

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import tech.idct.whaaack.audio.AudioEngine
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.ui.AboutScreen
import tech.idct.whaaack.ui.AuthScreen
import tech.idct.whaaack.ui.ForgotPasswordScreen
import tech.idct.whaaack.ui.GameOverScreen
import tech.idct.whaaack.ui.GameScreen
import tech.idct.whaaack.ui.HomeScreen
import tech.idct.whaaack.ui.LeaderboardScreen
import tech.idct.whaaack.ui.OrchardBackdrop
import tech.idct.whaaack.ui.SettingsScreen
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.WhaaackTheme
import java.security.MessageDigest
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val vm: WhaaackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // GDPR consent has to be resolved before the Ads SDK may request anything.
        vm.gatherConsent(this, debugDeviceHashedId = null)

        handleDeepLink(intent)

        setContent {
            WhaaackTheme {
                WhaaackApp(vm, ::signInWithGoogle)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "whaaack") return
        vm.handleAuthDeepLink(data.fragment)
    }

    override fun onResume() {
        super.onResume()
        vm.audio.resumeMusic()
    }

    override fun onPause() {
        vm.audio.pauseMusic()
        super.onPause()
    }

    /**
     * Native Google sign-in through Credential Manager.
     *
     * Google signs an ID token containing a SHA-256 hash of [rawNonce]; Supabase verifies
     * that hash against the raw value we send alongside the token, which is what stops a
     * stolen token being replayed.
     */
    private fun signInWithGoogle() {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) return

        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setNonce(hashedNonce)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        lifecycleScope.launch {
            try {
                val response = CredentialManager.create(this@MainActivity)
                    .getCredential(this@MainActivity, request)
                val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
                vm.signInWithGoogle(credential.idToken, rawNonce)
            } catch (_: GetCredentialCancellationException) {
                // Player dismissed the sheet; nothing to report.
            } catch (e: GetCredentialException) {
                Log.w("MainActivity", "Google sign-in failed", e)
                vm.reportGoogleFailure(e.message)
            }
        }
    }
}

@Composable
private fun WhaaackApp(vm: WhaaackViewModel, onGoogleSignIn: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context as? android.app.Activity }
    val googleAvailable = remember { BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() }

    BackHandler(enabled = state.screen != Screen.HOME) { vm.onBack() }

    // Menus share the orchard backdrop; the game screen paints its own.
    if (state.screen == Screen.GAME) {
        val assets = vm.assets
        if (assets == null) {
            LoadingScreen()
        } else {
            GameScreen(
                assets = assets,
                ranked = vm.pendingRanked,
                hapticsEnabled = state.prefs.haptics,
                onHit = { vm.onHitFeedback() },
                onStrike = { vm.onStrikeFeedback() },
                onGameOver = { vm.onRunFinished(it) },
                onLose = { vm.onLose() },
                onQuit = { },
            )
        }
        return
    }

    OrchardBackdrop(assets = vm.assets, animate = state.prefs.parallax) {
        Box(Modifier.fillMaxSize()) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(
                    state = state,
                    assets = vm.assets,
                    onPlayRanked = { vm.startGame(ranked = true) },
                    onPlayCasual = { vm.startGame(ranked = false) },
                    onSignIn = { vm.go(Screen.AUTH) },
                    onCreateAccount = {
                        vm.setAuthMode(AuthMode.SIGN_UP)
                        vm.go(Screen.AUTH)
                    },
                    onLeaderboard = { vm.go(Screen.LEADERBOARD) },
                    onSettings = { vm.go(Screen.SETTINGS) },
                    onLogout = { vm.signOut() },
                )

                Screen.AUTH -> AuthScreen(
                    state = state,
                    googleAvailable = googleAvailable,
                    onBack = { vm.go(Screen.HOME) },
                    onModeChange = { vm.setAuthMode(it) },
                    onSignIn = { email, pass -> vm.signIn(email, pass) },
                    onSignUp = { email, pass, name -> vm.signUp(email, pass, name) },
                    onGoogle = onGoogleSignIn,
                    onForgot = { vm.go(Screen.FORGOT) },
                    onSkip = { vm.startGame(ranked = false) },
                )

                Screen.FORGOT -> ForgotPasswordScreen(
                    state = state,
                    onBack = { vm.go(Screen.AUTH) },
                    onSend = { vm.sendPasswordReset(it) },
                    onDone = {
                        vm.consumeResetSent()
                        vm.go(Screen.AUTH)
                    },
                )

                Screen.GAME_OVER -> state.lastRun?.let { run ->
                    GameOverScreen(
                        run = run,
                        adsAvailable = vm.consent.canRequestAds,
                        onPlayAgain = { activity?.let { vm.playAgain(it) } },
                        onHome = { activity?.let { vm.backHome(it) } },
                        onSignIn = { vm.go(Screen.AUTH) },
                    )
                } ?: LoadingScreen()

                Screen.LEADERBOARD -> LeaderboardScreen(
                    state = state,
                    onBack = { vm.go(Screen.HOME) },
                    onScopeChange = { vm.setBoardScope(it) },
                    onSignIn = { vm.go(Screen.AUTH) },
                )

                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    privacyOptionsRequired = vm.consent.isPrivacyOptionsRequired,
                    onBack = { vm.go(Screen.HOME) },
                    onAbout = { vm.go(Screen.ABOUT) },
                    onToggleSound = { vm.toggleSound(it) },
                    onToggleMusic = { vm.toggleMusic(it) },
                    onToggleHaptics = { vm.toggleHaptics(it) },
                    onToggleParallax = { vm.toggleParallax(it) },
                    onPrivacyOptions = { activity?.let { vm.consent.showPrivacyOptions(it) } },
                    onChangeName = { vm.changeDisplayName(it) },
                    onChangeEmail = { vm.changeEmail(it) },
                    onChangePassword = { vm.changePassword(it) },
                    onDeleteAccount = { vm.deleteAccount() },
                    onLogout = { vm.signOut() },
                )

                Screen.ABOUT -> AboutScreen(onBack = { vm.go(Screen.SETTINGS) })

                Screen.GAME -> Unit
            }

            Toast(state.toast) { vm.consumeToast() }
        }
    }

    LaunchedEffect(state.screen) {
        if (state.screen != Screen.GAME) vm.audio.playTrack(AudioEngine.Track.MENU)
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) vm.refreshBoard(BoardScope.ALL_TIME)
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Whaaack!", color = Cream, fontSize = 34.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Toast(
    message: String?,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xEB091428))
                .padding(16.dp),
        ) {
            Text(
                message.orEmpty(),
                color = Cream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3_500)
            onDismiss()
        }
    }
}
