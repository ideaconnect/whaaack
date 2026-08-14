package tech.idct.whaaack.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single full-screen interstitial shown between a finished run and whatever the player
 * picked next ("Play again" or "Back to home").
 *
 * The ad is only ever *offered*: if consent was declined, the SDK is not ready, nothing
 * filled, or the presentation fails, the callback fires straight away so navigation is
 * never blocked by advertising.
 *
 * The configured ad unit must be of type **Interstitial** in the AdMob console.
 *
 * Offered at most once every [MIN_GAP_MS]: every route off the game-over screen asks for
 * one, so without a cap a player alternating "Play again" with a thirty-second run sees an
 * ad between every single attempt. Google's own interstitial guidance is explicit that
 * stacking them on every transition is the way to lose both the player and the revenue.
 */
class AdsManager(
    context: Context,
    private val adUnitId: String,
    private val consent: ConsentManager,
) {
    private val app = context.applicationContext
    private val initialised = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    init {
        // Ceiling on what an ad may contain. Left unset, it is UNSPECIFIED and AdMob may fill
        // this game with anything up to MA — gambling, dating, alcohol — inside a cartoon
        // orchard that will carry an Everyone / PEGI 3 content rating. Google Play's Ads
        // policy requires ads to be appropriate to the rating the app declares, so a mismatch
        // here is a policy exposure rather than a matter of taste.
        //
        // Set in the constructor rather than inside initialize(): the merged manifest declares
        // MobileAdsInitProvider, so the SDK can already be up before anything in this class
        // runs, and this has to be in force before the first request goes out — not before
        // initialisation, which is a different moment. AdsManager is built on the main thread
        // with the ViewModel, well ahead of any load.
        //
        // Built from the existing configuration rather than a fresh Builder, so this cannot
        // silently clear something set elsewhere — test device ids being the obvious one.
        //
        // This is only half the control. The same ceiling has to be set in AdMob → Blocking
        // controls, because the console can serve a rating this never asked for and a rebuild
        // can override what the console says; whichever is stricter wins, and the two drifting
        // apart is how a family-friendly game ends up with a dating ad in it. Keep this, the
        // console, and the IARC questionnaire answers in step — G narrows the demand pool and
        // costs some eCPM, which is the deliberate price of not gambling the rating.
        runCatching {
            MobileAds.setRequestConfiguration(
                MobileAds.getRequestConfiguration()
                    .toBuilder()
                    .setMaxAdContentRating(MAX_AD_CONTENT_RATING)
                    .build(),
            )
        }
    }

    // Main thread only — see the note on preload(). No synchronisation needed because
    // nothing else ever touches them.
    private var ad: InterstitialAd? = null
    private var loading = false

    /** Uptime the player last actually saw an ad, or 0 if they have not yet. */
    private var lastShownAtMs = 0L

    /**
     * Whether the player bought the ad-free unlock: true = owned, false = not owned,
     * **null = not yet known**.
     *
     * The gate lives here rather than at each call site so that no future caller can forget
     * it, and null counts as suppressed: the entitlement is read from disk asynchronously,
     * and an ad slipped in during those few milliseconds is exactly the ad a paying player
     * would never forgive.
     */
    @Volatile
    var adsRemoved: Boolean? = null

    private val adsAllowed: Boolean get() = adsRemoved == false

    /** Starts the Ads SDK. Safe to call repeatedly; no-ops without consent. */
    fun initialize(onReady: () -> Unit = {}) {
        if (!adsAllowed) return
        if (!consent.canRequestAds) return
        if (initialised.getAndSet(true)) {
            onReady()
            return
        }
        // initialize() does disk and network work; keep it off the main thread.
        Thread({
            MobileAds.initialize(app) {
                onReady()
                preload()
            }
        }, "ads-init").start()
    }

    /**
     * Asks for the next ad. Always hops to the main thread first.
     *
     * `MobileAds.initialize` is the one Ads SDK call documented as safe off the main thread,
     * and [initialize] deliberately makes it from its own thread — but its completion
     * listener then calls straight back into here, so without this hop the very first
     * `InterstitialAd.load` of a session could be issued from `ads-init`. Confining it also
     * makes `ad` and `loading` main-thread-only: the load callbacks below are delivered on
     * the main thread too, so those two fields need no synchronisation at all.
     */
    fun preload() {
        main.post {
            if (!adsAllowed) return@post
            if (!consent.canRequestAds || adUnitId.isBlank()) return@post
            if (ad != null || loading) return@post
            loading = true

            InterstitialAd.load(
                app,
                adUnitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(loaded: InterstitialAd) {
                        ad = loaded
                        loading = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "Interstitial failed: ${error.code} ${error.message}")
                        ad = null
                        loading = false
                    }
                },
            )
        }
    }

    /**
     * Shows the ad if one is ready, then invokes [onFinished]. When no ad is available the
     * callback runs immediately, so the caller can treat this as "continue when done".
     */
    fun showThen(activity: Activity, onFinished: () -> Unit) {
        // Paid, or not yet known to be unpaid: continue without so much as a preload.
        if (!adsAllowed) {
            onFinished()
            return
        }

        val ready = ad
        val capped = lastShownAtMs != 0L &&
            SystemClock.elapsedRealtime() - lastShownAtMs < MIN_GAP_MS

        if (!consent.canRequestAds || ready == null || capped) {
            preload()
            onFinished()
            return
        }

        // Retired before it is shown rather than in the callbacks. An interstitial is single
        // use — the SDK will refuse a second show() — and leaving it in the field let a second
        // showThen re-point its content callback at a new closure, stranding the first caller
        // whose continuation would then never run.
        ad = null
        lastShownAtMs = SystemClock.elapsedRealtime()

        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            onFinished()
        }

        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload()
                finishOnce()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Show failed: ${error.code} ${error.message}")
                // Nothing was actually seen, so it should not count against the cap.
                lastShownAtMs = 0L
                preload()
                finishOnce()
            }
        }

        // Navigation is never allowed to depend on the SDK calling us back: if show() throws
        // or the callbacks never arrive, the player would be stranded on the game-over
        // screen with two dead buttons.
        val shown = runCatching { ready.show(activity) }.isSuccess
        if (!shown) {
            lastShownAtMs = 0L
            preload()
            finishOnce()
        }
    }

    private companion object {
        const val TAG = "AdsManager"

        /**
         * G — general audiences. Matches the Everyone / PEGI 3 rating this game expects and
         * the 13+ target audience declared on Play. Change it here *and* in AdMob → Blocking
         * controls together, and only if the IARC answers change to justify it.
         */
        const val MAX_AD_CONTENT_RATING = RequestConfiguration.MAX_AD_CONTENT_RATING_G

        /**
         * Shortest gap between two ads the player is actually shown. A run lasts well under
         * a minute, so without this every "Play again" is fenced by advertising.
         */
        const val MIN_GAP_MS = 120_000L
    }
}
