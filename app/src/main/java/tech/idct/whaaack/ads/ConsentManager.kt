package tech.idct.whaaack.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GDPR / IAB TCF consent, via Google's User Messaging Platform.
 *
 * The consent message itself is authored in the AdMob console (Privacy & messaging) and
 * is fetched at runtime using the app's **AdMob application id** — the same one already
 * declared in the manifest. There is no separate "message id" to embed here: publishing
 * the GDPR message against this app in AdMob is what wires the two together.
 *
 * For testing, [debugDeviceHashedId] forces the EEA experience on a specific device. The
 * Ads SDK prints the hashed id to logcat on first run ("Use ConsentDebugSettings...").
 */
class ConsentManager(context: Context) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    private val gathering = AtomicBoolean(false)

    /** True once the player's choice allows ad requests (or consent was never required). */
    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    /** True when a privacy-options entry point should be shown in Settings. */
    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Refreshes consent state and, if a form is required, shows it. Invokes [onResolved]
     * once the player has answered (or immediately when nothing is required).
     */
    fun gather(activity: Activity, debugDeviceHashedId: String? = null, onResolved: (Boolean) -> Unit) {
        if (!gathering.compareAndSet(false, true)) {
            onResolved(canRequestAds)
            return
        }

        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .apply {
                if (!debugDeviceHashedId.isNullOrBlank()) {
                    setConsentDebugSettings(
                        ConsentDebugSettings.Builder(activity)
                            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                            .addTestDeviceHashedId(debugDeviceHashedId)
                            .build(),
                    )
                }
            }
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form: ${formError.errorCode} ${formError.message}")
                    }
                    gathering.set(false)
                    onResolved(canRequestAds)
                }
            },
            { requestError ->
                Log.w(TAG, "Consent update: ${requestError.errorCode} ${requestError.message}")
                gathering.set(false)
                // Without a consent decision we must not request personalised ads; the
                // caller treats `false` as "skip the ad this time".
                onResolved(canRequestAds)
            },
        )
    }

    /** Re-opens the privacy options form from Settings so a player can change their mind. */
    fun showPrivacyOptions(activity: Activity, onDone: (String?) -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error: com.google.android.ump.FormError? ->
            onDone(error?.message)
        }
    }

    /** Test helper: wipes the stored consent decision so the form shows again. */
    fun reset() = consentInformation.reset()

    private companion object {
        const val TAG = "ConsentManager"
    }
}
