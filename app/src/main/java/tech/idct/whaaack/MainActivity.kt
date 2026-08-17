package tech.idct.whaaack

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.credentials.exceptions.NoCredentialException
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.idct.whaaack.audio.AudioEngine
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.ui.AboutScreen
import tech.idct.whaaack.ui.AdBreakDialog
import tech.idct.whaaack.ui.AuthScreen
import tech.idct.whaaack.ui.ForgotPasswordScreen
import tech.idct.whaaack.ui.GameOverScreen
import tech.idct.whaaack.ui.GameScreen
import tech.idct.whaaack.ui.HomeScreen
import tech.idct.whaaack.ui.LeaderboardScreen
import tech.idct.whaaack.ui.OrchardBackdrop
import tech.idct.whaaack.ui.PRIVACY_URL
import tech.idct.whaaack.ui.RankedInviteDialog
import tech.idct.whaaack.ui.SettingsScreen
import tech.idct.whaaack.ui.TERMS_URL
import tech.idct.whaaack.ui.menuColumnWidth
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.WhaaackTheme
import java.security.MessageDigest
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val vm: WhaaackViewModel by viewModels()

    /**
     * A recovery link must not be consumed twice. The launch Intent is redelivered on every
     * recreation, so without this a rotation re-applied the tokens and re-navigated.
     */
    private var deepLinkHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)

        // GDPR consent has to be resolved before the Ads SDK may request anything.
        vm.gatherConsent(this, debugDeviceHashedId = null)

        deepLinkHandled = savedInstanceState?.getBoolean(KEY_DEEP_LINK_HANDLED) == true
        if (!deepLinkHandled) {
            handleDeepLink(intent)
            deepLinkHandled = true
        }

        setContent {
            WhaaackTheme {
                WhaaackApp(vm, ::signInWithGoogle)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DEEP_LINK_HANDLED, deepLinkHandled)
    }

    /**
     * Edge-to-edge, without the window APIs Android 15 deprecated.
     *
     * androidx's `enableEdgeToEdge()` used to do this, and Play Console flagged it for three
     * deprecated usages: its per-API implementations assign `Window.setStatusBarColor` and
     * `setNavigationBarColor`, and set `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`. Upgrading the
     * library does not help — even the newest `EdgeToEdgeApi35` still assigns both colours under a
     * `@Suppress("DEPRECATION")` — and neither would a version guard around the call, because the
     * Console reads the DEX and those classes ship whether or not the branch that uses them can
     * run. The call site had to go so R8 could strip them.
     *
     * What replaces it is less work than the library was doing:
     *  - From API 35 the window is edge-to-edge by force. Bar colours are ignored, every cutout
     *    mode is interpreted as ALWAYS, and `windowOptOutEdgeToEdgeEnforcement` is disabled for
     *    apps targeting 36 — which this one does. There is nothing left to ask for.
     *  - Below 35 the window still has to be told not to inset its content for the system bars.
     *    Transparency and the cutout mode come from the theme instead of from here —
     *    `android:statusBarColor`, `android:navigationBarColor`, and `values-v27`'s
     *    `windowLayoutInDisplayCutoutMode` — because a theme attribute is not a DEX reference and
     *    is what the platform ignores by itself once it stops honouring them.
     *
     * Bar icon appearance is set on every level rather than left to `windowLightStatusBar`: it
     * covers the navigation bar too, and it is not deprecated on any of them.
     */
    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 35) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh Intent is a fresh link, so this one is always ours to handle.
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        // Host as well as scheme. The manifest filter's host="auth" constrains only
        // *implicit* intents — an explicit intent from another app arrives here with any
        // whaaack: URI it likes, and the fragment it carries is a set of tokens this app
        // would verify and adopt. Verification proves they are SOMEBODY'S live session, not
        // that this player asked for it: adopting tokens minted for an attacker's account
        // signs the player into it silently. Checking the host does not close that on its
        // own, but it holds the door to links shaped exactly like the ones GoTrue sends.
        if (data.scheme != "whaaack" || data.host != "auth") return
        vm.handleAuthDeepLink(data.fragment)
    }

    override fun onResume() {
        super.onResume()
        // Resumes the music and re-checks the ad-free entitlement — the latter is how a
        // purchase completed while we were backgrounded gets picked up, and how a refund
        // that was granted elsewhere eventually lands.
        vm.onAppResumed()
        // Play Games needs an Activity, so it is asked here rather than from the ViewModel's
        // init: this is also the moment a sign-in made in the Play Games app itself, or the
        // SDK's own automatic attempt at startup, has had time to land.
        vm.syncPlayGames(this)
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
            } catch (_: NoCredentialException) {
                // No Google account on the device: an empty picker, not a failure. Point
                // them at the form instead of showing an error they cannot act on.
                vm.setAuthMode(AuthMode.SIGN_UP)
                vm.go(Screen.AUTH)
            } catch (e: GetCredentialException) {
                // The exception carries developer diagnostics, not player-facing copy; it
                // goes to logcat and the ViewModel shows its own fixed line.
                Log.w("MainActivity", "Google sign-in failed", e)
                vm.reportGoogleFailure()
            }
        }
    }

    private companion object {
        const val KEY_DEEP_LINK_HANDLED = "deep_link_handled"
    }
}

@Composable
private fun WhaaackApp(vm: WhaaackViewModel, onGoogleSignIn: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context as? android.app.Activity }
    val googleAvailable = remember { BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() }

    // Opening a browser can fail on a device with none configured; a dead link is a better
    // outcome than a crash on the screen where an account is about to be created.
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    // PredictiveBackHandler rather than BackHandler: with targetSdk 36 predictive back is on
    // and cannot meaningfully be opted out of, and a plain BackHandler simply swallows the
    // gesture — so on every screen except Home the system had no preview to draw and the
    // transition was instant. Consuming the progress flow is what lets the platform animate;
    // the navigation itself still happens once, on completion.
    PredictiveBackHandler(enabled = state.screen != Screen.HOME) { progress ->
        try {
            progress.collect { /* the platform draws the preview; nothing to do per event */ }
            vm.onBack()
        } catch (_: CancellationException) {
            // Gesture abandoned mid-swipe. Staying put is the whole point of the preview.
        }
    }

    // Above the branch below, which returns early for the game screen: this is the one place
    // the music track is chosen, so it has to be reached whatever is on screen.
    LaunchedEffect(state.screen) {
        vm.audio.playTrack(
            if (state.screen == Screen.GAME) AudioEngine.Track.GAME else AudioEngine.Track.MENU,
        )
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) vm.refreshBoard(BoardScope.ALL_TIME)
    }

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
                onGameOver = { vm.onRunFinished(it, activity) },
                onLose = { vm.onLose() },
                onRunInterrupted = { vm.onRunInterrupted(it) },
                onQuitArmed = { vm.onQuitArmed() },
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
                    onAchievements = { activity?.let { vm.showAchievements(it) } },
                    onSettings = { vm.go(Screen.SETTINGS) },
                    onLogout = { vm.signOut() },
                    onRemoveAds = { activity?.let { vm.buyRemoveAds(it) } },
                )

                Screen.AUTH -> AuthScreen(
                    state = state,
                    googleAvailable = googleAvailable,
                    onBack = { vm.go(Screen.HOME) },
                    onModeChange = { vm.setAuthMode(it) },
                    onSignIn = { email, pass -> vm.signIn(email, pass) },
                    onSignUp = { email, pass, name -> vm.signUp(email, pass, name) },
                    onGoogle = onGoogleSignIn,
                    // Being signed out of Play Games is no longer one of the things that hides
                    // this — the button raises that sign-in itself. What is left is the pair
                    // that no press can fix: a build with no Game server credential to exchange
                    // a code with, and a device with no Play Games to prompt with.
                    playGamesAvailable = state.offersPlayGamesSignIn,
                    onPlayGames = { vm.signInWithPlayGames(activity) },
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
                        signedIn = state.signedIn,
                        adsAvailable = state.adsAvailable,
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
                    privacyOptionsRequired = state.privacyOptionsRequired,
                    onBack = { vm.go(Screen.HOME) },
                    onAbout = { vm.go(Screen.ABOUT) },
                    onToggleSound = { vm.toggleSound(it) },
                    onToggleMusic = { vm.toggleMusic(it) },
                    onToggleHaptics = { vm.toggleHaptics(it) },
                    onToggleParallax = { vm.toggleParallax(it) },
                    onPrivacyOptions = { activity?.let { vm.showPrivacyOptions(it) } },
                    onRemoveAds = { activity?.let { vm.buyRemoveAds(it) } },
                    onRestorePurchases = { vm.restorePurchases() },
                    onPlayGamesSignIn = { activity?.let { vm.signInToPlayGames(it) } },
                    onAchievements = { activity?.let { vm.showAchievements(it) } },
                    onChangeName = { vm.changeDisplayName(it) },
                    onChangeEmail = { vm.changeEmail(it) },
                    onChangePassword = { vm.changePassword(it) },
                    onDeleteAccount = { vm.deleteAccount() },
                    onLogout = { vm.signOut() },
                    onClearError = { vm.clearAuthError() },
                )

                Screen.ABOUT -> AboutScreen(onBack = { vm.go(Screen.SETTINGS) })

                Screen.GAME -> Unit
            }

            // Above every screen, because the navigation that raised it is waiting behind it.
            if (state.adPrompt) {
                AdBreakDialog(
                    price = state.removeAdsPrice.orEmpty(),
                    onBuy = { vm.buyRemoveAdsFromAdBreak(activity) },
                    onContinue = { vm.continueThroughAdBreak(activity) },
                    onCancel = { vm.cancelAdBreak() },
                )
            }

            state.rankedInvite?.let { invite ->
                RankedInviteDialog(
                    state = invite,
                    onAccept = { vm.acceptRankedInvite(activity) },
                    onDecline = { vm.declineRankedInvite() },
                    // The account is created from this dialog, so both documents are reachable
                    // from it rather than only from Settings → About.
                    onTerms = { openUrl(TERMS_URL) },
                    onPrivacy = { openUrl(PRIVACY_URL) },
                )
            }

            Toast(state.toast) { vm.consumeToast() }
        }
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
                .displayCutoutPadding()
                .menuColumnWidth()
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
