package tech.idct.whaaack

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import tech.idct.whaaack.ads.AdsManager
import tech.idct.whaaack.ads.ConsentManager
import tech.idct.whaaack.audio.AudioEngine
import tech.idct.whaaack.data.AuthError
import tech.idct.whaaack.data.AuthRepository
import tech.idct.whaaack.data.AuthResultException
import tech.idct.whaaack.data.BoardRow
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.data.GameSettings
import tech.idct.whaaack.data.LeaderboardRepository
import tech.idct.whaaack.data.Player
import tech.idct.whaaack.data.Preferences
import tech.idct.whaaack.data.Session
import tech.idct.whaaack.data.SessionStore
import tech.idct.whaaack.data.Standing
import tech.idct.whaaack.data.SupabaseClient
import tech.idct.whaaack.data.str
import tech.idct.whaaack.game.GameAssets
import tech.idct.whaaack.game.GameEngine
import java.net.URLDecoder

enum class Screen { HOME, AUTH, FORGOT, GAME, GAME_OVER, LEADERBOARD, SETTINGS, ABOUT }

enum class AuthMode { SIGN_IN, SIGN_UP }

data class RunSummary(
    val millis: Long,
    val hits: Int,
    val topSpeed: Int,
    val ranked: Boolean,
    val personalBest: Long,
    val newRank: Int?,
    /** The player ended this run themselves; it is a stopping point, not a defeat. */
    val quit: Boolean,
)

/**
 * [Immutable] is a promise rather than an inference: `board` is a read-only `List`, which
 * Compose has to assume is mutable, and without the annotation every screen taking a
 * [UiState] can only be skipped by reference identity instead of by value.
 */
@Immutable
data class UiState(
    val screen: Screen = Screen.HOME,
    val authMode: AuthMode = AuthMode.SIGN_IN,
    val authError: AuthError? = null,
    val busy: Boolean = false,
    val player: Player? = null,
    /**
     * False until the persisted session has been read. Screens must show a loader rather
     * than guess: treating "not loaded yet" as "signed out" is what made the launch flash
     * the wrong buttons.
     */
    val sessionResolved: Boolean = false,
    val prefs: Preferences = Preferences(),
    val boardScope: BoardScope = BoardScope.ALL_TIME,
    val board: List<BoardRow> = emptyList(),
    val boardLoading: Boolean = false,
    val boardError: String? = null,
    val standing: Standing? = null,
    val lastRun: RunSummary? = null,
    val assetsReady: Boolean = false,
    val resetEmailSent: String? = null,
    val toast: String? = null,
    val backendConfigured: Boolean = true,
    /** Increments once per account operation that completed without error. */
    val actionSucceeded: Int = 0,
    /** Mirrors ConsentManager, which is not observable and so cannot be read in composition. */
    val adsAvailable: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
) {
    val signedIn: Boolean get() = player != null
}

class WhaaackViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = GameSettings(app)
    private val supabase = SupabaseClient(
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        sessions = SessionStore(app),
    )
    private val auth = AuthRepository(supabase)
    private val leaderboard = LeaderboardRepository(supabase)

    val audio = AudioEngine(app)
    val consent = ConsentManager(app)
    val ads = AdsManager(app, BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID, consent)

    private val _state = MutableStateFlow(UiState(backendConfigured = supabase.isConfigured))
    val state: StateFlow<UiState> = _state.asStateFlow()

    var assets: GameAssets? = null
        private set

    /** Set when the player taps Play; consumed by the game screen when it appears. */
    var pendingRanked: Boolean = false
        private set

    init {
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(prefs = prefs) }
                audio.soundEnabled = prefs.sound
                audio.musicEnabled = prefs.music
            }
        }
        viewModelScope.launch {
            auth.player.collect { player ->
                _state.update { it.copy(player = player) }
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                audio.preload()
                assets = runCatching { GameAssets.load(getApplication()) }.getOrNull()
            }
            _state.update { it.copy(assetsReady = assets != null) }
        }
        viewModelScope.launch {
            val restored = auth.restore()
            // Both in one update: a frame that said "resolved" while `player` was still the
            // initial null is exactly the wrong-buttons flash this flag exists to prevent,
            // and the collector above delivers the player on its own schedule.
            _state.update { it.copy(player = auth.player.value, sessionResolved = true) }
            if (restored) auth.refreshProfile()
        }
    }

    // ---- navigation ----------------------------------------------------------------

    fun go(screen: Screen) {
        audio.blip()
        navigate(screen)
    }

    /**
     * The music track is not switched here. It follows `screen` from a single effect in the
     * composition, which also covers the first screen of a session; driving it from both
     * places posted two `playTrack`s per navigation, and the second one used to land on a
     * MediaPlayer that was still preparing.
     */
    private fun navigate(screen: Screen) {
        _state.update { it.copy(screen = screen, authError = null) }
        if (screen == Screen.LEADERBOARD) refreshBoard(_state.value.boardScope)
    }

    fun onBack(): Boolean {
        val current = _state.value.screen
        val target = when (current) {
            Screen.HOME, Screen.GAME -> return false
            Screen.ABOUT -> Screen.SETTINGS
            Screen.FORGOT -> Screen.AUTH
            else -> Screen.HOME
        }
        navigate(target)
        return true
    }

    // ---- game ----------------------------------------------------------------------

    fun startGame(ranked: Boolean) {
        audio.blip()
        pendingRanked = ranked && _state.value.signedIn
        navigate(Screen.GAME)
    }

    fun onRunFinished(result: GameEngine.Result) {
        viewModelScope.launch {
            settings.recordLocalBest(result.millisSurvived)
            val prefs = _state.value.prefs
            val personalBest = maxOf(prefs.localBestMillis, result.millisSurvived)

            var rank: Int? = null
            var submitFailed = false
            // A run abandoned during the countdown scores zero; there is nothing to post.
            if (result.ranked && _state.value.signedIn && result.millisSurvived > 0L) {
                val posted = runCatching {
                    leaderboard.submit(result.millisSurvived, result.hits, result.topSpeedLevel)
                }.getOrDefault(false)
                if (posted) {
                    rank = runCatching { leaderboard.myStanding(BoardScope.ALL_TIME)?.rank }
                        .getOrNull()
                } else {
                    submitFailed = true
                }
            }

            _state.update { it.copy(
                screen = Screen.GAME_OVER,
                lastRun = RunSummary(
                    millis = result.millisSurvived,
                    hits = result.hits,
                    // Clamped the same way the in-run HUD is, so a long run does not end on
                    // "TOP SPEED 31" after the HUD spent the last two minutes saying it was
                    // already at top speed.
                    topSpeed = GameEngine.displaySpeed(result.topSpeedLevel),
                    ranked = result.ranked,
                    personalBest = personalBest,
                    newRank = rank,
                    quit = result.quit,
                ),
                // Silently dropping a ranked score is the one failure a player would
                // definitely want to know about.
                toast = if (submitFailed) "Couldn't post that score — check your connection." else it.toast,
            ) }
        }
    }

    fun onLose() = audio.lose()

    fun onHitFeedback() = audio.splat()

    fun onStrikeFeedback() = audio.hurt()

    // ---- ads -----------------------------------------------------------------------

    /** Runs [then] after the interstitial, or immediately when none is available. */
    fun withAd(activity: Activity, then: () -> Unit) {
        audio.blip()
        ads.showThen(activity, then)
    }

    fun playAgain(activity: Activity) = withAd(activity) {
        pendingRanked = _state.value.lastRun?.ranked == true && _state.value.signedIn
        navigate(Screen.GAME)
    }

    fun backHome(activity: Activity) = withAd(activity) { navigate(Screen.HOME) }

    fun gatherConsent(activity: Activity, debugDeviceHashedId: String? = null) {
        consent.gather(activity, debugDeviceHashedId) { canRequest ->
            if (canRequest) ads.initialize()
            publishConsentState()
        }
    }

    fun showPrivacyOptions(activity: Activity) {
        consent.showPrivacyOptions(activity) { publishConsentState() }
    }

    /**
     * Copies the consent flags into [UiState]. ConsentManager reads them straight off the
     * UMP SDK, which Compose cannot observe, so a screen composed before consent resolved
     * would otherwise never learn about it.
     */
    private fun publishConsentState() {
        _state.update { it.copy(
            adsAvailable = consent.canRequestAds,
            privacyOptionsRequired = consent.isPrivacyOptionsRequired,
        ) }
    }

    // ---- settings ------------------------------------------------------------------

    fun toggleSound(value: Boolean) = viewModelScope.launch { settings.setSound(value) }

    fun toggleMusic(value: Boolean) = viewModelScope.launch { settings.setMusic(value) }

    fun toggleHaptics(value: Boolean) = viewModelScope.launch { settings.setHaptics(value) }

    fun toggleParallax(value: Boolean) = viewModelScope.launch { settings.setParallax(value) }

    // ---- auth ----------------------------------------------------------------------

    fun setAuthMode(mode: AuthMode) {
        audio.blip()
        _state.update { it.copy(authMode = mode, authError = null) }
    }

    fun clearAuthError() {
        _state.update { it.copy(authError = null) }
    }

    fun signUp(email: String, password: String, displayName: String) = runAuth {
        auth.signUpWithEmail(email, password, displayName)
        if (auth.player.value == null) {
            // Confirmation required: tell the player to check their inbox.
            _state.update { it.copy(
                toast = "Check your inbox to confirm $email, then sign in.",
                authMode = AuthMode.SIGN_IN,
            ) }
        } else {
            navigate(Screen.HOME)
        }
    }

    fun signIn(email: String, password: String) = runAuth {
        auth.signInWithEmail(email, password)
        navigate(Screen.HOME)
    }

    fun signInWithGoogle(idToken: String, nonce: String?) = runAuth {
        auth.signInWithGoogle(idToken, nonce)
        navigate(Screen.HOME)
    }

    fun reportGoogleFailure(message: String?) {
        _state.update { it.copy(
            authError = AuthError.Unexpected(
                message ?: "Google sign-in was not completed.",
            ),
        ) }
    }

    fun sendPasswordReset(email: String) = runAuth {
        auth.sendPasswordReset(email)
        _state.update { it.copy(resetEmailSent = email) }
    }

    fun signOut() = viewModelScope.launch {
        audio.blip()
        auth.signOut()
        // Keeps the device's casual best — this is a log out, not a delete — but the ranked
        // standing belongs to the account, and the leaderboard footer would otherwise read
        // "Sign in to rank" next to the rank of whoever just left.
        _state.update { it.copy(standing = null) }
        navigate(Screen.HOME)
    }

    fun changeDisplayName(newName: String) = runAuth {
        auth.updateDisplayName(newName)
        _state.update { it.copy(toast = "Display name updated") }
    }

    fun changeEmail(newEmail: String) = runAuth {
        auth.updateEmail(newEmail)
        _state.update { it.copy(toast = "Confirmation link sent to $newEmail") }
    }

    fun changePassword(newPassword: String) = runAuth {
        auth.updatePassword(newPassword)
        _state.update { it.copy(toast = "Password updated") }
    }

    fun deleteAccount() = runAuth {
        auth.deleteAccount()
        // The server row is gone, but the score also lives on the device (the casual best)
        // and in this state (the standing, the last run summary, the cached board with the
        // player's own row in it). Without this, Home and the leaderboard keep showing the
        // best of an account that no longer exists.
        settings.clearLocalBest()
        _state.update { it.copy(
            standing = null,
            board = emptyList(),
            lastRun = null,
            toast = "Account deleted",
        ) }
        navigate(Screen.HOME)
    }

    private fun runAuth(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, authError = null) }
        try {
            block()
            // Ticked rather than flagged so a screen can react to *this* success without
            // having to clear the signal afterwards.
            _state.update { it.copy(actionSucceeded = it.actionSucceeded + 1) }
        } catch (e: AuthResultException) {
            _state.update { it.copy(authError = e.error) }
        } catch (e: Exception) {
            _state.update { it.copy(
                authError = AuthError.Unexpected(e.message ?: "Unknown error"),
            ) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // ---- leaderboard ---------------------------------------------------------------

    fun setBoardScope(scope: BoardScope) {
        audio.blip()
        _state.update { it.copy(boardScope = scope) }
        refreshBoard(scope)
    }

    fun refreshBoard(scope: BoardScope = _state.value.boardScope) = viewModelScope.launch {
        if (!leaderboard.isConfigured) {
            _state.update { it.copy(boardError = "Leaderboard is not configured yet.") }
            return@launch
        }
        _state.update { it.copy(boardLoading = true, boardError = null) }
        val rows = runCatching { leaderboard.board(scope) }
        val standing = runCatching { leaderboard.myStanding(scope) }.getOrNull()
        _state.update { it.copy(
            boardLoading = false,
            board = rows.getOrDefault(emptyList()),
            standing = standing,
            boardError = rows.exceptionOrNull()?.let { "Couldn't reach the leaderboard." },
        ) }
    }

    /**
     * Handles the `whaaack://auth#...` callback Supabase sends for password recovery and
     * email confirmation. The tokens ride in the URL fragment, not the query.
     */
    fun handleAuthDeepLink(fragment: String?) {
        val params = fragment
            ?.split('&')
            ?.mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
            }
            ?.toMap()
            ?: return

        val access = params["access_token"] ?: return
        val refresh = params["refresh_token"] ?: return
        val type = params["type"]
        val expiresAtMs = System.currentTimeMillis() +
            (params["expires_in"]?.toLongOrNull() ?: 3600L) * 1000

        viewModelScope.launch {
            // Who this link belongs to is settled *before* anything is written. The previous
            // order — save with an empty user id, then try to fill it in — left a session
            // behind that no later request could recover from if that second call failed:
            // every profile read and write became `?id=eq.`, which comes back 400, and only
            // a 401 signs the player out. They stayed signed in and permanently broken.
            val user = runCatching {
                supabase.json.parseToJsonElement(supabase.getAs("/auth/v1/user", access))
            }.getOrNull() as? JsonObject
            val userId = user?.str("id")
            if (user == null || userId.isNullOrBlank()) {
                _state.update { it.copy(
                    toast = "That link couldn't be verified — open it again, or sign in.",
                ) }
                return@launch
            }

            supabase.saveSession(
                Session(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtMs = expiresAtMs,
                    userId = userId,
                    email = user.str("email"),
                    provider = user["app_metadata"]?.str("provider") ?: "email",
                ),
            )
            auth.restore()
            auth.refreshProfile()
            _state.update { it.copy(sessionResolved = true) }

            if (type == "recovery") {
                _state.update { it.copy(
                    toast = "Signed in — set a new password below.",
                ) }
                navigate(Screen.SETTINGS)
            } else {
                _state.update { it.copy(toast = "Email confirmed.") }
                navigate(Screen.HOME)
            }
        }
    }

    /**
     * Fragment values arrive percent-encoded — `error_description` in particular reads as
     * gibberish without this. Safe for the tokens too: they are base64url, whose alphabet
     * contains neither `%` nor `+`.
     */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun consumeResetSent() {
        _state.update { it.copy(resetEmailSent = null) }
    }

    override fun onCleared() {
        audio.release()
        // The bitmaps are deliberately not freed here: Compose still holds ImageBitmap
        // wrappers around them and disposes its composition when the window detaches, which
        // is after this runs. See GameAssets.
        assets = null
        super.onCleared()
    }
}
