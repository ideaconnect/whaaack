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
import kotlinx.coroutines.flow.first
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
import tech.idct.whaaack.data.DisplayName
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
import tech.idct.whaaack.games.PlayGamesManager

enum class Screen { HOME, AUTH, FORGOT, GAME, GAME_OVER, LEADERBOARD, SETTINGS, ABOUT }

enum class AuthMode { SIGN_IN, SIGN_UP }

/**
 * The one-off conversation with a Play Games player who has no Whaaack! account and has just
 * asked to play ranked.
 *
 * [ASKING] is the consent moment and the reason accounts are minted here rather than at Play
 * Games sign-in: ranked play puts a name on a public leaderboard, and that should follow from
 * something the player did on purpose. [WORKING] covers the round trip that follows, which
 * crosses two networks — Play Games for the auth code, then our own backend — and is long
 * enough that an unlabelled pause reads as a dead button.
 */
enum class RankedInvite { ASKING, WORKING }

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
    /**
     * Which board window [standing] is an answer *for*, or null when there is no answer at
     * all: nobody has asked yet, the ask failed, or the account changed under it.
     *
     * The two are read together, because `standing == null` on its own means nothing. The
     * server correctly returns no row for an account that has never posted a ranked run, and
     * reading that silence as "not loaded yet" is what let both screens fall back to
     * [Preferences.localBestMillis] — the *device's* casual best, which survives a log-out by
     * design — and print it as the account's ranked score. A player signing in on a phone
     * somebody had already played was shown 46 seconds they had never played, next to a rank
     * of "—". With the scope beside it the three cases are distinguishable: an answer for the
     * window on screen, an answer for a different window, and no answer. Only the first is
     * ever shown as a score.
     */
    val standingScope: BoardScope? = null,
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
    /**
     * Play Games, which is a different thing from [player]: that is the Whaaack! account the
     * leaderboard score belongs to, this is the Play Games profile the achievements belong
     * to. Null until the SDK's automatic attempt has resolved, for the same reason
     * [sessionResolved] exists — offering "Sign in to Play Games" to somebody who already is
     * would be a row that flashes up and then contradicts itself.
     */
    val playGamesAuthenticated: Boolean? = null,
    /**
     * Whether this device has Play Games on it at all, which is a different question from
     * [playGamesAuthenticated] and the one the provider button has to ask: being signed out of
     * Play Games is recoverable with a press, having no Play Games is not. Null until the SDK
     * has answered once. See `PlayGamesManager.onDevice`.
     */
    val playGamesOnDevice: Boolean? = null,
    /**
     * Whether this build can turn a Play Games player into a Whaaack! account at all — that
     * is, whether a Game server client id was configured. Blank is a supported state, and it
     * means only that ranked play still requires signing up, exactly as it did before.
     */
    val playGamesRankingAvailable: Boolean = false,
    /** Non-null while the mint-an-account conversation is on screen. */
    val rankedInvite: RankedInvite? = null,
) {
    /** The upsell is only offered once we know they have not already bought it. */
    val canBuyRemoveAds: Boolean get() = adsRemoved == false && removeAdsPrice != null

    val signedIn: Boolean get() = player != null

    /**
     * This player has a Play Games identity and no Whaaack! account, and we can turn the
     * first into the second. They are offered ranked play like anybody else; the account is
     * minted behind the first tap.
     */
    val canMintPlayGamesAccount: Boolean
        get() = sessionResolved && !signedIn &&
            playGamesAuthenticated == true && playGamesRankingAvailable

    /** Whether Home should show the ranked pair of buttons rather than the signed-out pair. */
    val offersRanked: Boolean get() = signedIn || canMintPlayGamesAccount

    /**
     * Whether the auth screen lists Play Games beside email and Google.
     *
     * Deliberately *not* conditional on [playGamesAuthenticated]: it used to be, and that made
     * the screen a dead end for anyone who dismissed v2's automatic prompt at launch — the
     * provider they wanted simply was not there, and the way back was a Settings row nothing
     * on the screen pointed at. The button now raises Play Games' own sign-in when it needs to,
     * so the only things that can still make it impossible are a build with no Game server
     * credential and a device with no Play Games to prompt with.
     */
    val offersPlayGamesSignIn: Boolean
        get() = playGamesRankingAvailable && playGamesOnDevice == true
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
    val playGames = PlayGamesManager()

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
        // Read once: a build either has the Game server credential or it does not, and
        // nothing about that can change while the app is running.
        _state.update {
            it.copy(playGamesRankingAvailable = BuildConfig.PGS_SERVER_CLIENT_ID.isNotBlank())
        }
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(prefs = prefs) }
                audio.soundEnabled = prefs.sound
                audio.musicEnabled = prefs.music
            }
        }
        viewModelScope.launch {
            // Tracked by id rather than by the Player itself, which is re-emitted whenever the
            // profile is refreshed — a display-name change is not an account change and must
            // not throw away a standing that is still correct.
            var account: String? = null
            auth.player.collect { player ->
                _state.update { it.copy(player = player) }
                if (player?.userId == account) return@collect
                account = player?.userId
                // A standing belongs to an account, not to the device or to this process, and
                // every way of changing accounts arrives here: the restore at launch, either
                // sign-in, the sign-out, and the deep link that swaps one session for another.
                // Clearing in one place is what stops the next player being shown the last
                // one's rank while their own is still in flight.
                _state.update { it.copy(standing = null, standingScope = null) }
                if (player != null) refreshStanding()
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
            playGames.authenticated.collect { authenticated ->
                _state.update { it.copy(playGamesAuthenticated = authenticated) }
            }
        }

        viewModelScope.launch {
            playGames.onDevice.collect { onDevice ->
                _state.update { it.copy(playGamesOnDevice = onDevice) }
            }
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
        // A Play Games player asking for their first ranked run. The account they need can be
        // made from the identity they already have, but not silently: this is the moment
        // their name starts appearing on a public board, so it is the moment to say so.
        if (ranked && _state.value.canMintPlayGamesAccount) {
            _state.update { it.copy(rankedInvite = RankedInvite.ASKING) }
            return
        }
        pendingRanked = ranked && _state.value.signedIn
        navigate(Screen.GAME)
    }

    /** The invitation was declined, or dismissed. Nothing was created and nothing changed. */
    fun declineRankedInvite() {
        _state.update { it.copy(rankedInvite = null) }
    }

    /**
     * The player accepted: mint the account from their Play Games identity and start the run.
     *
     * Two hops, and both can fail in ways the player can do nothing about — Play Games can
     * decline to issue a code, and the backend can be unreachable — so every failure lands on
     * the same promise: they are left exactly where they were, on Home, with the ordinary
     * sign-in still available. Nothing half-created survives, because the account is created
     * by the backend in one request or not at all.
     */
    fun acceptRankedInvite(activity: Activity?) = viewModelScope.launch {
        if (_state.value.rankedInvite != RankedInvite.ASKING) return@launch
        // The auth screen's own Play Games button may already be mid-flight — its busy flag
        // is that fact. Two concurrent adoptions would race two code exchanges and two
        // session saves, with whichever lands last winning the store. Lowering the dialog
        // rather than ignoring the tap: a button that silently does nothing reads as broken.
        if (_state.value.busy) {
            _state.update { it.copy(
                rankedInvite = null,
                toast = "Play Games sign-in is already in progress.",
            ) }
            return@launch
        }
        _state.update { it.copy(rankedInvite = RankedInvite.WORKING) }

        val error = adoptPlayGamesAccount(activity)
        _state.update { it.copy(rankedInvite = null, toast = error?.body ?: it.toast) }
        if (error != null) return@launch

        // Set directly rather than through `ranked && signedIn`: the player arrives in
        // UiState on the auth collector's schedule, which has not necessarily run yet.
        pendingRanked = true
        navigate(Screen.GAME)
    }

    /**
     * The same account, reached from the auth screen instead of from a ranked tap.
     *
     * Play Games is a way in beside email and Google, not a shortcut bolted onto one screen:
     * a player who signs out has to be able to sign back in, and the only place they will look
     * is the screen with the other two on it. No invitation dialog here — pressing a button
     * that says "Continue with Play Games" *is* the intent that dialog exists to collect —
     * and failures land in the error banner the other two providers already use rather than a
     * toast, because that is what this screen shows.
     *
     * Two hops, not one: Play Games itself first, and only then the account. The second hop
     * used to be all there was, which meant the button could only be offered to a player Play
     * Games had already authenticated — and so the player who dismissed the launch prompt by
     * mistake found the provider missing from the one screen that is *about* choosing a
     * provider. Both hops can be declined and neither leaves anything behind.
     */
    fun signInWithPlayGames(activity: Activity?) = viewModelScope.launch {
        // Both guards: this button's own re-tap, and the Home invitation dialog mid-mint.
        if (_state.value.busy || _state.value.rankedInvite != null) return@launch
        audio.blip()
        _state.update { it.copy(busy = true, authError = null) }
        val error = if (activity == null || !ensurePlayGamesAuthenticated(activity)) {
            // Deliberately not the sentence adoptPlayGamesAccount uses for a refused code:
            // nothing is wrong with this player's account, they are simply not signed in to
            // Play Games, and pressing the same button again is a real thing to try.
            AuthError.Unexpected(
                "Play Games sign-in didn't finish. Try again, or use email or Google.",
            )
        } else {
            adoptPlayGamesAccount(activity)
        }
        _state.update { it.copy(busy = false, authError = error) }
        if (error != null) return@launch
        _state.update { it.copy(actionSucceeded = it.actionSucceeded + 1) }
        // Only if the player is still where the flow started. busy does not freeze the whole
        // screen — the back control and "Skip — just play for fun" stay live, deliberately —
        // so a player who left during the round trip may be mid-run by the time this lands,
        // and an unconditional navigate would yank them out of it. They are signed in either
        // way; the navigation was only ever the courtesy, not the sign-in.
        if (_state.value.screen == Screen.AUTH) navigate(Screen.HOME)
    }

    /**
     * Trades this device's Play Games identity for a Whaaack! session, creating the account
     * the first time and signing into the same one every time after — the address it is keyed
     * to is derived from the player id, so this is idempotent and survives a log out.
     *
     * Returns null on success, or the error to show. Shared by both entry points so the two
     * cannot drift: the failure modes here are Play Games declining to issue a code and the
     * backend being unreachable, and neither leaves anything half-created behind, because the
     * account is made by one backend request or not at all.
     */
    private suspend fun adoptPlayGamesAccount(activity: Activity?): AuthError? = try {
        val code = activity?.let {
            playGames.serverAuthCode(it, BuildConfig.PGS_SERVER_CLIENT_ID)
        }
        if (code == null) {
            // Play Games would not vouch for them: signed out since the button appeared, or a
            // build whose signing certificate the console does not know about.
            AuthError.Unexpected(
                "Play Games couldn't confirm your account. Try email or Google instead.",
            )
        } else {
            auth.signInWithPlayGames(code)
            null
        }
    } catch (e: AuthResultException) {
        // AuthRepository.call funnels a dead connection into Offline, which is the one case
        // with advice worth giving. The rest of the AuthError copy is about a form this player
        // never filled in — passwords, taken emails, unconfirmed addresses — so anything else
        // is reported as itself rather than mistranslated.
        if (e.error == AuthError.Offline) e.error
        else AuthError.Unexpected("Play Games sign-in couldn't be completed just now.")
    } catch (e: Exception) {
        AuthError.Unexpected("Play Games sign-in couldn't be completed just now.")
    }

    fun onRunFinished(result: GameEngine.Result, activity: Activity?) {
        // Every run, ranked or not. The leaderboard is the thing you need an account and a
        // ranked run for; a survival milestone is a statement about what happened on this
        // device, and refusing it to somebody playing for fun would make the achievement mean
        // something different from what its description says. Quitting early does not need
        // excluding either — the clock stopped where it stopped.
        activity?.let { playGames.award(it, result.millisSurvived) }

        viewModelScope.launch {
            settings.recordLocalBest(result.millisSurvived)
            val prefs = _state.value.prefs
            val personalBest = maxOf(prefs.localBestMillis, result.millisSurvived)

            // Reported here rather than beside the unlock above because it needs the best the
            // run just produced, and deliberately not conditional on the unlock: a run that
            // earns no new achievement is still a run, and every Game Stats figure — runs
            // played, time survived, fruit whacked — is built out of these.
            activity?.let { playGames.reportRun(it, result, personalBest) }

            var rank: Int? = null
            var submitError: String? = null
            // A run abandoned during the countdown scores zero; there is nothing to post.
            if (result.ranked && _state.value.signedIn && result.millisSurvived > 0L) {
                try {
                    if (leaderboard.submit(result.millisSurvived, result.hits, result.topSpeedLevel)) {
                        // One call, two answers: the rank this screen is about to announce,
                        // and the standing Home and the leaderboard footer should now be
                        // showing. Publishing it here is what stops a player's *first* ranked
                        // run leaving them on the casual-best line until they think to open
                        // the board.
                        val standing = runCatching { leaderboard.myStanding(BoardScope.ALL_TIME) }
                        rank = standing.getOrNull()?.rank
                        if (standing.isSuccess) {
                            _state.update { it.copy(
                                standing = standing.getOrNull(),
                                standingScope = BoardScope.ALL_TIME,
                            ) }
                        }
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

    // ---- play games ------------------------------------------------------------------

    /**
     * Re-reads the Play Games sign-in state and back-fills anything the player has already
     * earned. Called from `onResume`, which is both when the SDK's automatic attempt has had
     * time to land and when a sign-in performed elsewhere — the Play Games app itself — comes
     * back to us.
     *
     * The back-fill is the whole reason this is not a one-shot at startup. Runs are recorded
     * to the device's best whether or not anything is signed in, so a player who has been
     * playing with Play Games unavailable has a personal best sitting in preferences that
     * nobody has claimed. Replaying it here hands them every milestone it covers the moment
     * they are authenticated, instead of making them re-earn what they already did.
     */
    fun syncPlayGames(activity: Activity) {
        playGames.refresh(activity) { authenticated ->
            if (authenticated) awardStoredBest(activity)
        }
    }

    /**
     * The player asked to sign in to Play Games from Settings, where it buys them achievements
     * and nothing else — no Whaaack! account is created here. A cancelled prompt is reported:
     * they pressed a button that visibly did nothing, and Play Games gives us no way to tell a
     * dismissal from a genuine failure, so the wording covers both without accusing them of
     * either.
     */
    fun signInToPlayGames(activity: Activity) = viewModelScope.launch {
        audio.blip()
        if (!ensurePlayGamesAuthenticated(activity)) {
            _state.update { it.copy(toast = "Play Games sign-in didn't finish.") }
        }
    }

    /**
     * Gets Play Games to vouch for this player, raising its sign-in prompt if the SDK's
     * automatic attempt at launch did not land — which for most players who need this means
     * they dismissed that prompt, quite possibly by accident.
     *
     * Shared by the two buttons that can need it, so a sign-in reached from the auth screen
     * earns exactly what one reached from Settings earns: [awardStoredBest] is the back-fill,
     * and leaving it out of one door would mean a player's milestones depended on which button
     * they happened to press. The early return saves a round trip rather than preventing a
     * second prompt — `signIn()` on an authenticated player shows no UI either way.
     */
    private suspend fun ensurePlayGamesAuthenticated(activity: Activity): Boolean {
        if (playGames.authenticated.value == true) return true
        val authenticated = playGames.signIn(activity)
        if (authenticated) awardStoredBest(activity)
        return authenticated
    }

    /**
     * Awards everything the stored personal best covers.
     *
     * Read from preferences rather than from [UiState.prefs], which is a copy that arrives on
     * DataStore's schedule. The first `onResume` of a cold start runs before that copy exists,
     * so a player whose best predates all of this — anyone updating rather than installing —
     * would have been back-filled with a zero and had to background the app once to be given
     * what they had already earned.
     */
    private fun awardStoredBest(activity: Activity) = viewModelScope.launch {
        val best = settings.flow.first().localBestMillis
        playGames.award(activity, best)
        // The Game Stats half of the same idea. Between them these are the whole of what a
        // player who has been playing signed out gets back when they finally authenticate:
        // the achievements their best already covers, and the best itself.
        playGames.reportBest(activity, best)
    }

    /**
     * Opens the Play Games achievements UI.
     *
     * A press that fails is answered with a line and *not* with a sign-in prompt. Achievements are
     * offered only to a player Play Games has already signed in — nothing here tries to talk one
     * into it, and a control that raised an account flow would be exactly the offer this app does
     * not make. The failure Play Games actually returns for an absent sign-in is
     * `ApiException: 4` (`SIGN_IN_REQUIRED`), and [PlayGamesManager.openAchievements] answers it by
     * lowering `authenticated`, which takes this control off the screen behind the toast and puts
     * Settings' "Sign in to Play Games" row in its place. That row is the one door in, and the
     * player chooses to use it.
     *
     * The other reasons a press can fail cannot be fixed from here at all: no Play Games on the
     * device, or a Play Games Services project that is not published, which only admits accounts on
     * its Testers list. `adb logcat -s PlayGames` names which — see docs/PLAY-GAMES.md §7.
     */
    fun showAchievements(activity: Activity) {
        audio.blip()
        playGames.openAchievements(activity) { message ->
            _state.update { it.copy(toast = message) }
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
        _state.update { it.copy(authMode = mode, authError = null) }
    }

    fun clearAuthError() {
        _state.update { it.copy(authError = null) }
    }

    fun signUp(email: String, password: String, displayName: String) = runAuth {
        auth.signUpWithEmail(email, password, displayName)
        val player = auth.player.value
        if (player == null) {
            // Confirmation required: tell the player to check their inbox.
            _state.update { it.copy(
                toast = "Check your inbox to confirm $email, then sign in.",
                authMode = AuthMode.SIGN_IN,
            ) }
        } else {
            // `handle_new_user` numbers a taken name rather than refusing it, which is right —
            // a blocked signup is unrecoverable and an odd name is not — but it means the
            // player can be given a name they never typed. Saying so is the difference between
            // that and finding out from the leaderboard. Compared case-insensitively because
            // display_name is citext, so a rename to differing case is the same name.
            val asked = DisplayName.normalize(displayName)
            if (!player.displayName.equals(asked, ignoreCase = true)) {
                _state.update { it.copy(
                    toast = "\"$asked\" was taken — you're ${player.displayName}. " +
                        "You can change it in Settings.",
                ) }
            }
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

    /**
     * A Google credential attempt failed for a reason worth reporting (cancellation and
     * no-account are handled where they happen and never reach here).
     *
     * Fixed copy, deliberately. The exception messages Credential Manager produces are
     * developer diagnostics — "failure response from one tap: 16: Cannot find a matching
     * credential", "[28444] Developer console is not set up correctly" — and showing them
     * verbatim was exactly the raw-server-text problem the [AuthError] catalogue exists to
     * prevent. The detail still lands in logcat at the call site, where it is useful.
     */
    fun reportGoogleFailure() {
        _state.update { it.copy(
            authError = AuthError.Unexpected(
                "Google sign-in didn't finish. Try again, or use email instead.",
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
        // "Sign in to rank" next to the rank of whoever just left. The account watcher in
        // `init` clears it too; this is the same guarantee made where it can be read.
        _state.update { it.copy(standing = null, standingScope = null) }
        navigate(Screen.HOME)
    }

    fun changeDisplayName(newName: String) = runAuth {
        auth.updateDisplayName(newName)
        _state.update { it.copy(toast = "Display name updated") }
    }

    fun changeEmail(newEmail: String) = runAuth {
        auth.updateEmail(newEmail)
        // double_confirm_changes is on: GoTrue mails BOTH addresses, and the change only
        // lands once both links are opened. The old copy named only the new inbox, so a
        // player who opened that one link waited on a change that was still holding for
        // the other half.
        _state.update { it.copy(
            toast = "Links sent to $newEmail and your current address — open both to finish.",
        ) }
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
            standingScope = null,
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
        val asked = _state.value.player?.userId
        _state.update { it.copy(boardLoading = true, boardError = null) }
        val rows = runCatching { leaderboard.board(scope) }
        val standing = runCatching { leaderboard.myStanding(scope) }
        _state.update { current ->
            // The board is public, but the standing is not: an account change while this was
            // in flight — a log-out from Home, say — would otherwise stamp the departing
            // player's rank onto whoever is signed in now, or onto nobody at all.
            val stillMine = current.player?.userId == asked
            current.copy(
                boardLoading = false,
                board = rows.getOrDefault(emptyList()),
                // Only a call that *returned* is an answer. A failed one leaves the pair
                // empty, so the footer reads "—" rather than announcing that a player with a
                // perfectly good rank has never posted a ranked run — and rather than keeping
                // an answer that belongs to the window they just switched away from.
                standing = if (stillMine) standing.getOrNull() else current.standing,
                standingScope = when {
                    !stillMine -> current.standingScope
                    standing.isSuccess -> scope
                    else -> null
                },
                boardError = rows.exceptionOrNull()?.let { "Couldn't reach the leaderboard." },
            )
        }
    }

    /**
     * Fetches the player's own position without the board around it.
     *
     * The board is only loaded when the leaderboard screen is opened, but the Home card speaks
     * for the same standing — so without this a signed-in player spent the whole session on
     * the casual-best line unless they happened to visit the board. All-time because that is
     * the window Home names, and the one the board itself opens on.
     */
    private fun refreshStanding(scope: BoardScope = BoardScope.ALL_TIME) = viewModelScope.launch {
        if (!leaderboard.isConfigured) return@launch
        val asked = _state.value.player?.userId ?: return@launch
        val standing = runCatching { leaderboard.myStanding(scope) }
        if (standing.isFailure) return@launch
        _state.update { current ->
            if (current.player?.userId != asked) current
            else current.copy(standing = standing.getOrNull(), standingScope = scope)
        }
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
            // The double-confirm halfway point: the link worked, nothing to adopt, and the
            // player needs to hear that the other inbox holds the second half.
            is AuthLink.Notice -> _state.update { it.copy(toast = link.message) }
            is AuthLink.Tokens -> adoptAuthLink(link)
        }
    }

    private fun adoptAuthLink(link: AuthLink.Tokens) {
        val access = link.accessToken
        val refresh = link.refreshToken
        val type = link.type
        val expiresAtMs = System.currentTimeMillis() + link.expiresInSeconds * 1000

        viewModelScope.launch {
            // *That* the link was asked for, before who it belongs to.
            //
            // MainActivity is an exported launcher activity, so its own note says an explicit
            // intent from another app arrives with any whaaack: URI it likes — and the check
            // below used to be the only one: /auth/v1/user proves the tokens are somebody's
            // live session, never that this player wanted them. A co-installed app could hand
            // over its own valid pair and the app would adopt it silently, putting the player
            // into an account the sender controls; every ranked run and every email or
            // password change from that point lands there. That is session fixation, and the
            // token being genuine is precisely why the server cannot catch it.
            //
            // So a callback is only believed while this device is actually waiting for one:
            // the three flows that ask GoTrue to send a link open a window, and nothing else
            // does. It is not a nonce — a link asked for on another device is refused here,
            // and an attacker who lands inside a window the player opened themselves is not
            // stopped — but it takes the attack from "any time, silently" to "only in the
            // minutes after the player requested a link, on this device". PKCE, which binds
            // the callback to a verifier this device generated, is the complete fix and needs
            // the hand-rolled client to carry a code exchange; this is the part that can be
            // held without touching the flow that emails real players their reset link.
            if (!supabase.authCallbackExpected()) {
                _state.update { it.copy(
                    toast = "That link wasn't requested from this device. Ask for a new one " +
                        "here, or open it where you requested it.",
                ) }
                return@launch
            }

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
            // Spent. The window is for the link that was asked for, not for the rest of the
            // two hours — and GoTrue's links are single-use, so nothing legitimate needs it
            // again. The double-confirm email change is unaffected: its first link comes back
            // as a Notice with no tokens and never reaches here, only the closing one does.
            supabase.forgetAuthCallback()
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
