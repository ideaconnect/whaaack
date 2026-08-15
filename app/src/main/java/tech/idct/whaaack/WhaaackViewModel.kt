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
import tech.idct.whaaack.billing.BillingManager
import tech.idct.whaaack.data.AuthError
import tech.idct.whaaack.data.AuthLink
import tech.idct.whaaack.data.AuthRepository
import tech.idct.whaaack.data.AuthResultException
import tech.idct.whaaack.data.BoardRow
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.data.DataStoreSessionStore
import tech.idct.whaaack.data.EntitlementStore
import tech.idct.whaaack.data.GameSettings
import tech.idct.whaaack.data.LeaderboardRepository
import tech.idct.whaaack.data.Player
import tech.idct.whaaack.data.Preferences
import tech.idct.whaaack.data.Session
import tech.idct.whaaack.data.Standing
import tech.idct.whaaack.data.SupabaseClient
import tech.idct.whaaack.data.parseAuthFragment
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
    val resetEmailSent: String? = null,
    val toast: String? = null,
    /** Increments once per account operation that completed without error. */
    val actionSucceeded: Int = 0,
    /** Mirrors ConsentManager, which is not observable and so cannot be read in composition. */
    val adsAvailable: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
    /**
     * Null while the stored entitlement is still being read. Screens treat null as "not yet
     * known" rather than as either answer, which is why the upsell does not flash on launch
     * for someone who already paid.
     */
    val adsRemoved: Boolean? = null,
    /** Localised price from Play, or null when there is nothing to sell. */
    val removeAdsPrice: String? = null,
    /**
     * The ad-break prompt is up: an ad is about to play and the player is being told so,
     * and offered the way out. Only ever raised when both halves of that are true — see
     * [WhaaackViewModel.withAd].
     */
    val adPrompt: Boolean = false,
    /** A "Restore purchases" pass is in flight. It can take ten seconds; it has to show. */
    val restoringPurchases: Boolean = false,
) {
    /** The upsell is only offered once we know they have not already bought it. */
    val canBuyRemoveAds: Boolean get() = adsRemoved == false && removeAdsPrice != null

    val signedIn: Boolean get() = player != null
}

class WhaaackViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = GameSettings(app)
    private val supabase = SupabaseClient(
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        sessions = DataStoreSessionStore(app),
    )
    private val auth = AuthRepository(supabase)
    private val leaderboard = LeaderboardRepository(supabase)

    val audio = AudioEngine(app)
    val consent = ConsentManager(app)
    val ads = AdsManager(app, BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID, consent)

    private val entitlements = EntitlementStore(app)
    val billing = BillingManager(
        context = app,
        productId = BuildConfig.REMOVE_ADS_PRODUCT_ID,
        store = entitlements,
        scope = viewModelScope,
    )

    private val _state = MutableStateFlow(UiState())
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
            // Nothing to publish: every screen branches on `vm.assets == null` directly.
        }
        // The entitlement drives the ad gate directly. AdsManager starts at null (ads
        // suppressed), so nothing can slip through between launch and the first answer.
        viewModelScope.launch {
            billing.adsRemoved.collect { removed ->
                ads.adsRemoved = removed
                _state.update { it.copy(
                    adsRemoved = removed,
                    // Not adsAvailable(): inside this block _state still holds the previous
                    // adsRemoved, so it would answer for the state we are replacing.
                    adsAvailable = consent.canRequestAds && removed == false,
                ) }
                // initialize(), not preload(). AdsManager refuses to initialise while the
                // entitlement is unknown — the deliberate paid-player guard — and the only
                // other caller is the consent callback, which can land first. When it does,
                // `initialised` stays false and nothing ever sets it, so InterstitialAd.load
                // goes out with MobileAds.initialize never having run: a documented
                // precondition violated, and any mediation adapter left uninitialised.
                // initialize() is idempotent and preloads once ready.
                if (removed == false) ads.initialize()
            }
        }
        viewModelScope.launch {
            billing.price.collect { details ->
                val formatted = details?.oneTimePurchaseOfferDetails?.formattedPrice
                    // Debug builds only, and only when asked for in local.properties: Play
                    // does not price a product for a sideloaded APK, so without this the
                    // upsell can never be seen on a development device. Empty in release.
                    ?: BuildConfig.REMOVE_ADS_PLACEHOLDER_PRICE.takeIf { it.isNotBlank() }
                _state.update { it.copy(removeAdsPrice = formatted) }
            }
        }
        // A pending purchase completes Play's sheet and grants nothing; without this the
        // player is shown no acknowledgement at all and the Remove ads button is still there.
        viewModelScope.launch {
            billing.events.collect { event ->
                val message = when (event) {
                    is BillingManager.Event.PurchasePending ->
                        "That purchase is still being confirmed by Google Play."
                    // Deliberately does not quote the response code at the player: none of
                    // them are actionable, and Play has usually shown its own reason already.
                    // What this has to do is break the silence, so a failed payment does not
                    // read as a dead button.
                    is BillingManager.Event.PurchaseFailed ->
                        "That purchase didn't go through. Nothing has been charged."
                }
                _state.update { it.copy(toast = message) }
            }
        }
        // 3.16: Play's fraud detection correlates orders with in-app accounts through an
        // opaque handle. Kept in step with the session rather than read at purchase time, so
        // signing out cannot leave the previous player's id attached to the next order.
        viewModelScope.launch {
            auth.player.collect { billing.setAccount(it?.userId) }
        }
        billing.start()

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
            var submitError: String? = null
            // A run abandoned during the countdown scores zero; there is nothing to post.
            if (result.ranked && _state.value.signedIn && result.millisSurvived > 0L) {
                try {
                    if (leaderboard.submit(result.millisSurvived, result.hits, result.topSpeedLevel)) {
                        rank = runCatching { leaderboard.myStanding(BoardScope.ALL_TIME)?.rank }
                            .getOrNull()
                    }
                } catch (e: SupabaseClient.SupabaseException) {
                    // The server saw the score and said no — a plausibility constraint, the
                    // rate-limit trigger, or a session that has gone stale. Telling the player
                    // to check their connection would be actively misleading: the connection
                    // is fine, and there is nothing for them to retry.
                    submitError = "That run couldn't be recorded on the leaderboard."
                } catch (e: java.io.IOException) {
                    submitError = "Couldn't post that score — check your connection."
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
                toast = submitError ?: it.toast,
            ) }
        }
    }

    /**
     * A run was suspended without finishing — backgrounded, or the window shrank below a
     * playable size. The run itself is deliberately not resumable across process death: this
     * is a session-length arcade game and restoring a half-finished reflex run would be worse
     * than starting over. What is *not* acceptable is losing the score too, which is what
     * happened while `recordLocalBest` was only reached from `onRunFinished` — a backgrounded
     * process holding ~4.6 MB of bitmaps is a routine kill on a low-memory device.
     *
     * Ranked scores are not posted here: an interrupted run has not ended, and submitting it
     * would put a partial run on the board and burn a rate-limit slot for a run the player is
     * about to come back and finish.
     */
    fun onRunInterrupted(millisSurvived: Long) {
        viewModelScope.launch { settings.recordLocalBest(millisSurvived) }
    }

    /**
     * The End-run control was pressed once and is now waiting for a confirming press. Worth a
     * sound: it is the only cue that the press registered but deliberately did not act, and
     * silence there reads as an unresponsive button rather than a safety catch.
     */
    fun onQuitArmed() = audio.blip()

    fun onLose() = audio.lose()

    fun onHitFeedback() = audio.splat()

    fun onStrikeFeedback() = audio.hurt()

    // ---- ads -----------------------------------------------------------------------

    /**
     * Where the player was going when the ad break was raised. Held here rather than in
     * [UiState] because it is a continuation, not something to render, and because it must
     * survive a recomposition without ever being run twice.
     */
    private var afterAdBreak: (() -> Unit)? = null

    /**
     * Runs [then] after the interstitial, or immediately when none is available.
     *
     * When an ad genuinely is about to play *and* there is an unlock to sell, the player is
     * told first. Both halves are required: a prompt in front of a screen that was never
     * going to show an ad is an extra tap for nothing, and a prompt with nothing to offer is
     * an apology with no way to act on it — the game-over caption already covers that case.
     */
    fun withAd(activity: Activity, then: () -> Unit) {
        audio.blip()
        val adComing = ads.wouldShow()
        if (adComing && _state.value.canBuyRemoveAds) {
            afterAdBreak = then
            _state.update { it.copy(adPrompt = true) }
            return
        }
        // An ad is about to play and there was nothing to offer instead of it. The only way
        // to reach here is a missing price — an ad cannot play at all unless the entitlement
        // is known to be absent — so ask Play again, in time for the next break. Bounded by
        // the interstitial cap rather than by a timer of its own.
        if (adComing) billing.refreshOffer()
        ads.showThen(activity, then)
    }

    /** The player accepted the ad break. The ad plays, then they go where they asked. */
    fun continueThroughAdBreak(activity: Activity?) {
        val next = consumeAdBreak() ?: return
        // No Activity means nothing can be presented; the navigation still has to happen.
        if (activity == null) next() else ads.showThen(activity, next)
    }

    /**
     * The prompt was dismissed without a choice — the back gesture, or a tap outside it. That
     * is a cancel: no ad, no navigation, and the player is left exactly where they were with
     * both buttons still live.
     *
     * The alternative — treating dismissal as "continue" — meant an accidental back gesture
     * was answered with a full-screen ad nobody asked for, which is a worse outcome than any
     * it prevents. It cannot be used to dodge advertising either: every route off the
     * game-over screen still raises this prompt while an ad is pending, so cancelling buys
     * the player nothing but the screen they were already on.
     */
    fun cancelAdBreak() {
        consumeAdBreak()
    }

    /**
     * The player bought their way out instead. The ad is skipped rather than raced against
     * Play's sheet, and the navigation is not held behind the purchase: the entitlement
     * arrives through [BillingManager] whenever it arrives, and by then the player is
     * wherever they were trying to go.
     */
    fun buyRemoveAdsFromAdBreak(activity: Activity?) {
        val next = consumeAdBreak() ?: return
        if (activity != null) buyRemoveAds(activity)
        next()
    }

    /** Takes the pending navigation and lowers the prompt, so neither can be used twice. */
    private fun consumeAdBreak(): (() -> Unit)? {
        val next = afterAdBreak
        afterAdBreak = null
        _state.update { it.copy(adPrompt = false) }
        return next
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
        consent.showPrivacyOptions(activity) {
            // A player who declined at launch and accepts here would otherwise get no SDK
            // initialisation until the next cold start.
            if (consent.canRequestAds) ads.initialize()
            publishConsentState()
        }
    }

    // ---- remove ads ------------------------------------------------------------------

    fun buyRemoveAds(activity: Activity) {
        audio.blip()
        val code = billing.launchPurchase(activity)
        if (code != 0) {
            _state.update { it.copy(
                toast = "Google Play couldn't open the purchase — try again in a moment.",
            ) }
        }
    }

    /** For a player who reinstalled or switched device and wants their unlock back. */
    fun restorePurchases() {
        audio.blip()
        // Guarded as well as flagged: the row disables itself, but a second call would
        // otherwise queue another pass behind the billing mutex and answer twice.
        if (_state.value.restoringPurchases) return
        _state.update { it.copy(restoringPurchases = true) }
        billing.restore { check ->
            val message = when (check) {
                is BillingManager.Check.Owned -> "Ad-free restored. Thanks for the support!"
                is BillingManager.Check.Pending -> "That purchase is still being confirmed by Google Play."
                is BillingManager.Check.NotOwned -> "No purchase found on this Google account."
                // Never phrased as "you don't own it": we could not ask, and saying otherwise
                // to someone who paid is exactly the wrong answer.
                is BillingManager.Check.Unknown -> "Couldn't reach Google Play — nothing has changed."
            }
            _state.update { it.copy(toast = message, restoringPurchases = false) }
        }
    }

    fun onAppResumed() {
        audio.resumeMusic()
        // Re-checks the entitlement, which is also how a purchase completed while the app was
        // in the background gets noticed.
        billing.onResume()
        // Refills the ad slot. A load that failed — no fill, or offline at launch — is never
        // retried on its own, so without this a session that started without a network never
        // has an ad to show again however long it runs. No-ops when one is already loaded,
        // when consent is missing, and when the player has paid.
        ads.preload()
    }

    /**
     * Copies the consent flags into [UiState]. ConsentManager reads them straight off the
     * UMP SDK, which Compose cannot observe, so a screen composed before consent resolved
     * would otherwise never learn about it.
     */
    private fun publishConsentState() {
        _state.update { it.copy(
            adsAvailable = adsAvailable(),
            privacyOptionsRequired = consent.isPrivacyOptionsRequired,
        ) }
    }

    /**
     * Whether an ad could actually play. Consent alone is not the answer: a player who bought
     * the ad-free unlock and also consented would otherwise read "AD MAY PLAY BEFORE NEXT
     * SCREEN" after every run, for ever, while no ad ever plays. Gated here rather than at
     * the call site so no future caller repeats it — the same reasoning AdsManager applies to
     * the ad gate itself.
     */
    private fun adsAvailable(): Boolean =
        consent.canRequestAds && _state.value.adsRemoved == false

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
     *
     * A link that cannot be used is *said out loud*. An expired or already-used one comes back
     * carrying an error instead of tokens, and answering that with silence — which is what
     * returning early amounted to — leaves the player watching the app come to the front and
     * do nothing, with no way to tell that the link was at fault rather than the app.
     *
     * A failure deliberately does not navigate. The link brings the app to the foreground
     * whatever it was doing, and pulling someone out of a live run to show them a dead link is
     * a worse answer than the toast.
     */
    fun handleAuthDeepLink(fragment: String?) {
        when (val link = parseAuthFragment(fragment)) {
            is AuthLink.Ignored -> return
            is AuthLink.Failed -> _state.update { it.copy(toast = link.message) }
            is AuthLink.Tokens -> adoptAuthLink(link)
        }
    }

    private fun adoptAuthLink(link: AuthLink.Tokens) {
        val access = link.accessToken
        val refresh = link.refreshToken
        val type = link.type
        val expiresAtMs = System.currentTimeMillis() + link.expiresInSeconds * 1000

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
                // Settings only shows the password row for an email account (it is gated on
                // `!player.isGoogle`), so sending a Google player there would land them on a
                // screen with nothing to do and a toast telling them to do it. GoTrue sends a
                // recovery mail for any address that exists, precisely so the endpoint cannot
                // be used to enumerate accounts — which means this is reachable whenever
                // somebody with a Google account taps "Forgot your password?".
                if (auth.player.value?.isGoogle == true) {
                    _state.update { it.copy(
                        toast = "You're signed in. This account uses Google, so there's no password to reset.",
                    ) }
                    navigate(Screen.HOME)
                } else {
                    _state.update { it.copy(
                        toast = "Signed in — set a new password below.",
                    ) }
                    navigate(Screen.SETTINGS)
                }
            } else {
                _state.update { it.copy(toast = "Email confirmed.") }
                navigate(Screen.HOME)
            }
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun consumeResetSent() {
        _state.update { it.copy(resetEmailSent = null) }
    }

    override fun onCleared() {
        audio.release()
        billing.close()
        // The bitmaps are deliberately not freed here: Compose still holds ImageBitmap
        // wrappers around them and disposes its composition when the window detaches, which
        // is after this runs. See GameAssets.
        assets = null
        super.onCleared()
    }
}
