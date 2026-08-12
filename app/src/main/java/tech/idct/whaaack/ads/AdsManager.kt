package tech.idct.whaaack.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single full-screen rewarded interstitial shown between a finished run and whatever
 * the player picked next ("Play again" or "Back to home").
 *
 * The ad is only ever *offered*: if consent was declined, the SDK is not ready, or nothing
 * filled, the callback fires straight away so navigation is never blocked by advertising.
 *
 * The configured ad unit must be of type **Rewarded Interstitial** in the AdMob console.
 */
class AdsManager(
    context: Context,
    private val adUnitId: String,
    private val consent: ConsentManager,
) {
    private val app = context.applicationContext
    private val initialised = AtomicBoolean(false)
    private var ad: RewardedInterstitialAd? = null
    private var loading = false

    /** Set once the player earns the reward in the current presentation. */
    var lastRewardEarned: Boolean = false
        private set

    /** Starts the Ads SDK. Safe to call repeatedly; no-ops without consent. */
    fun initialize(onReady: () -> Unit = {}) {
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

    fun preload() {
        if (!consent.canRequestAds || adUnitId.isBlank()) return
        if (ad != null || loading) return
        loading = true

        RewardedInterstitialAd.load(
            app,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedInterstitialAd) {
                    ad = loaded
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Rewarded interstitial failed: ${error.code} ${error.message}")
                    ad = null
                    loading = false
                }
            },
        )
    }

    /**
     * Shows the ad if one is ready, then invokes [onFinished]. When no ad is available the
     * callback runs immediately, so the caller can treat this as "continue when done".
     */
    fun showThen(activity: Activity, onFinished: () -> Unit) {
        val ready = ad
        lastRewardEarned = false

        if (!consent.canRequestAds || ready == null) {
            preload()
            onFinished()
            return
        }

        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            onFinished()
        }

        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                preload()
                finishOnce()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Show failed: ${error.code} ${error.message}")
                ad = null
                preload()
                finishOnce()
            }
        }

        ready.show(activity) { lastRewardEarned = true }
    }

    private companion object {
        const val TAG = "AdsManager"
    }
}
