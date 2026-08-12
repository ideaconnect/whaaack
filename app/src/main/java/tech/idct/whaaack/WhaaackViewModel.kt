package tech.idct.whaaack

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import tech.idct.whaaack.data.SessionStore
import tech.idct.whaaack.data.Standing
import tech.idct.whaaack.data.SupabaseClient
import tech.idct.whaaack.data.str
import tech.idct.whaaack.game.GameAssets
import tech.idct.whaaack.game.GameEngine

enum class Screen { HOME, AUTH, FORGOT, GAME, GAME_OVER, LEADERBOARD, SETTINGS, ABOUT }

enum class AuthMode { SIGN_IN, SIGN_UP }

data class RunSummary(
    val millis: Long,
    val hits: Int,
    val topSpeed: Int,
    val ranked: Boolean,
    val personalBest: Long,
    val newRank: Int?,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val authMode: AuthMode = AuthMode.SIGN_IN,
    val authError: AuthError? = null,
    val busy: Boolean = false,
    val player: Player? = null,
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
    val showingAd: Boolean = false,
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
    val ads = AdsManager(app, BuildConfig.ADMOB_REWARDED_AD_UNIT_ID, consent)

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
                _state.value = _state.value.copy(prefs = prefs)
                audio.soundEnabled = prefs.sound
                audio.musicEnabled = prefs.music
            }
        }
        viewModelScope.launch {
            auth.player.collect { player ->
                _state.value = _state.value.copy(player = player)
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                audio.preload()
                assets = runCatching { GameAssets.load(getApplication()) }.getOrNull()
            }
            _state.value = _state.value.copy(assetsReady = assets != null)
        }
        viewModelScope.launch { auth.restore() }
    }

    // ---- navigation ----------------------------------------------------------------

    fun go(screen: Screen) {
        audio.blip()
        navigate(screen)
    }

    private fun navigate(screen: Screen) {
        _state.value = _state.value.copy(screen = screen, authError = null)
        audio.playTrack(if (screen == Screen.GAME) AudioEngine.Track.GAME else AudioEngine.Track.MENU)
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
            if (result.ranked && _state.value.signedIn) {
                runCatching {
                    leaderboard.submit(result.millisSurvived, result.hits, result.topSpeedLevel)
                    leaderboard.myStanding(BoardScope.ALL_TIME)?.rank
                }.onSuccess { rank = it }
            }

            _state.value = _state.value.copy(
                screen = Screen.GAME_OVER,
                lastRun = RunSummary(
                    millis = result.millisSurvived,
                    hits = result.hits,
                    topSpeed = result.topSpeedLevel + 1,
                    ranked = result.ranked,
                    personalBest = personalBest,
                    newRank = rank,
                ),
            )
            audio.playTrack(AudioEngine.Track.MENU)
        }
    }

    fun onLose() = audio.lose()

    fun onHitFeedback() = audio.splat()

    fun onStrikeFeedback() = audio.hurt()

    // ---- ads -----------------------------------------------------------------------

    /** Runs [then] after the rewarded interstitial, or immediately when none is available. */
    fun withAd(activity: Activity, then: () -> Unit) {
        audio.blip()
        _state.value = _state.value.copy(showingAd = true)
        ads.showThen(activity) {
            _state.value = _state.value.copy(showingAd = false)
            then()
        }
    }

    fun playAgain(activity: Activity) = withAd(activity) {
        pendingRanked = _state.value.lastRun?.ranked == true && _state.value.signedIn
        navigate(Screen.GAME)
    }

    fun backHome(activity: Activity) = withAd(activity) { navigate(Screen.HOME) }

    fun gatherConsent(activity: Activity, debugDeviceHashedId: String? = null) {
        consent.gather(activity, debugDeviceHashedId) { canRequest ->
            if (canRequest) ads.initialize()
        }
    }

    // ---- settings ------------------------------------------------------------------

    fun toggleSound(value: Boolean) = viewModelScope.launch { settings.setSound(value) }

    fun toggleMusic(value: Boolean) = viewModelScope.launch { settings.setMusic(value) }

    fun toggleHaptics(value: Boolean) = viewModelScope.launch { settings.setHaptics(value) }

    fun toggleParallax(value: Boolean) = viewModelScope.launch { settings.setParallax(value) }

    // ---- auth ----------------------------------------------------------------------

    fun setAuthMode(mode: AuthMode) {
        audio.blip()
        _state.value = _state.value.copy(authMode = mode, authError = null)
    }

    fun clearAuthError() {
        _state.value = _state.value.copy(authError = null)
    }

    fun signUp(email: String, password: String, displayName: String) = runAuth {
        auth.signUpWithEmail(email, password, displayName)
        if (auth.player.value == null) {
            // Confirmation required: tell the player to check their inbox.
            _state.value = _state.value.copy(
                toast = "Check your inbox to confirm $email, then sign in.",
                authMode = AuthMode.SIGN_IN,
            )
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
        _state.value = _state.value.copy(
            authError = AuthError.Unexpected(
                message ?: "Google sign-in was not completed.",
            ),
        )
    }

    fun sendPasswordReset(email: String) = runAuth {
        auth.sendPasswordReset(email)
        _state.value = _state.value.copy(resetEmailSent = email)
    }

    fun signOut() = viewModelScope.launch {
        audio.blip()
        auth.signOut()
        navigate(Screen.HOME)
    }

    fun changeDisplayName(newName: String) = runAuth {
        auth.updateDisplayName(newName)
        _state.value = _state.value.copy(toast = "Display name updated")
    }

    fun changeEmail(newEmail: String) = runAuth {
        auth.updateEmail(newEmail)
        _state.value = _state.value.copy(toast = "Confirmation link sent to $newEmail")
    }

    fun changePassword(newPassword: String) = runAuth {
        auth.updatePassword(newPassword)
        _state.value = _state.value.copy(toast = "Password updated")
    }

    fun deleteAccount() = runAuth {
        auth.deleteAccount()
        _state.value = _state.value.copy(toast = "Account deleted")
        navigate(Screen.HOME)
    }

    private fun runAuth(block: suspend () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, authError = null)
        try {
            block()
        } catch (e: AuthResultException) {
            _state.value = _state.value.copy(authError = e.error)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                authError = AuthError.Unexpected(e.message ?: "Unknown error"),
            )
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    // ---- leaderboard ---------------------------------------------------------------

    fun setBoardScope(scope: BoardScope) {
        audio.blip()
        _state.value = _state.value.copy(boardScope = scope)
        refreshBoard(scope)
    }

    fun refreshBoard(scope: BoardScope = _state.value.boardScope) = viewModelScope.launch {
        if (!leaderboard.isConfigured) {
            _state.value = _state.value.copy(boardError = "Leaderboard is not configured yet.")
            return@launch
        }
        _state.value = _state.value.copy(boardLoading = true, boardError = null)
        val rows = runCatching { leaderboard.board(scope) }
        val standing = runCatching { leaderboard.myStanding(scope) }.getOrNull()
        _state.value = _state.value.copy(
            boardLoading = false,
            board = rows.getOrDefault(emptyList()),
            standing = standing,
            boardError = rows.exceptionOrNull()?.let { "Couldn't reach the leaderboard." },
        )
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
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            ?.toMap()
            ?: return

        val access = params["access_token"] ?: return
        val refresh = params["refresh_token"] ?: return
        val type = params["type"]

        viewModelScope.launch {
            supabase.saveSession(
                tech.idct.whaaack.data.Session(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtMs = System.currentTimeMillis() +
                        (params["expires_in"]?.toLongOrNull() ?: 3600L) * 1000,
                    userId = "",
                    email = null,
                    provider = "email",
                ),
            )
            // The stored session has no user id yet; ask the server who this is.
            runCatching {
                val raw = supabase.request("GET", "/auth/v1/user", authorized = true)
                val user = supabase.json.parseToJsonElement(raw)
                val id = user.str("id").orEmpty()
                val email = user.str("email")
                supabase.saveSession(
                    tech.idct.whaaack.data.Session(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = System.currentTimeMillis() +
                            (params["expires_in"]?.toLongOrNull() ?: 3600L) * 1000,
                        userId = id,
                        email = email,
                        provider = "email",
                    ),
                )
            }
            auth.restore()

            if (type == "recovery") {
                _state.value = _state.value.copy(
                    toast = "Signed in — set a new password below.",
                )
                navigate(Screen.SETTINGS)
            } else {
                _state.value = _state.value.copy(toast = "Email confirmed.")
                navigate(Screen.HOME)
            }
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun consumeResetSent() {
        _state.value = _state.value.copy(resetEmailSent = null)
    }

    override fun onCleared() {
        audio.release()
        assets?.recycle()
        assets = null
        super.onCleared()
    }
}
