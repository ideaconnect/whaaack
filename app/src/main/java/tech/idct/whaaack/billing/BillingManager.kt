package tech.idct.whaaack.billing

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.idct.whaaack.data.EntitlementStore
import kotlin.coroutines.resume

/**
 * The one-time "remove ads" purchase, and the question that actually matters: does this
 * player still own it?
 *
 * ### Why this is not a one-liner
 *
 * `queryPurchasesAsync` returns only currently-owned items, and a [Purchase] carries no
 * "refunded" flag. So a player who never bought the product and a player who bought it and
 * had it refunded-and-revoked look **identical**: `OK` plus a list with no matching entry.
 *
 * That would be fine, except a third case looks identical too. Google documents nothing
 * about what this call does offline, and the Play Store app has historically served it from
 * its own on-device cache without touching the network. So `OK` + empty is *not* proof that
 * the player does not own the product — it may equally mean nobody asked Google.
 *
 * Getting that wrong is not symmetric. Wrongly revoking costs a paying customer their
 * purchase, a refund request and a one-star review; wrongly granting costs one missed
 * interstitial in a single-player game with no server bill. So this class is built to
 * **favour the player**, and only ever lowers an entitlement when it can prove it spoke to
 * Google and got a negative answer twice.
 *
 * ### The rules
 *
 *  - Only [BillingClient.BillingResponseCode.OK] permits *any* reading of the purchase list.
 *    Every other code — present or future — means "could not check" and changes nothing.
 *  - Reaching Play's servers is proven separately, by a `queryProductDetails` call, which is
 *    the one operation Google documents as performing a network query. It is probed before
 *    the ownership read and again after it, so a link that dies mid-pass cannot leave a
 *    cached list looking authoritative.
 *  - A verified-online negative must happen **twice** before the entitlement drops. The
 *    counter is persisted, so the two may fall in different sessions.
 *  - The offline grace period is **indefinite**. There is deliberately no TTL: a bound would
 *    fire precisely for the long-offline legitimate owner — the player on a plane, in a
 *    tunnel, on a wifi-off tablet — which is the exact population it is meant to protect.
 *  - `PENDING` (cash, carrier billing, parental approval) neither grants nor revokes, and
 *    `UNSPECIFIED_STATE` is an absence of information, not a negative.
 *
 * The deliberate consequence: on a *fresh install* with no connectivity, a paying player
 * sees ads until Play is reachable once. The optimism only extends to a device where
 * ownership was previously confirmed, and seeding it any other way would hand the product
 * to everyone.
 */
class BillingManager(
    context: Context,
    private val productId: String,
    private val store: EntitlementStore,
    private val scope: CoroutineScope,
) {

    /** What the last completed check concluded. */
    sealed interface Check {
        data object Owned : Check
        data object Pending : Check
        data object NotOwned : Check
        /** Could not establish ownership either way; the stored entitlement stands. */
        data class Unknown(val responseCode: Int, val reason: String) : Check
    }

    private val app = context.applicationContext

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases.orEmpty().forEach { scope.launch { handlePurchase(it) } }

            // Google's own guidance: this is not an error, it is a cue to re-query. Showing
            // a failure here would strand a reinstalled paying player in front of the paywall.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                scope.launch { refresh("already-owned") }

            BillingClient.BillingResponseCode.USER_CANCELED -> Unit

            else -> Log.w(TAG, "Purchase flow: ${result.responseCode} ${result.debugMessage}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(purchasesListener)
        // Mandatory since Play Billing 7: build() throws IllegalArgumentException without it
        // whenever a PurchasesUpdatedListener is set.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    /**
     * Null until the stored value has been read off disk. Ads stay suppressed while it is
     * null: the read takes a few milliseconds, and an ad shown to a paying player in those
     * milliseconds is a one-star review.
     */
    private val _adsRemoved = MutableStateFlow<Boolean?>(null)
    val adsRemoved: StateFlow<Boolean?> = _adsRemoved.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)

    /** Formatted local price, or null when Play has nothing to sell (or has not answered). */
    val price: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _lastCheck = MutableStateFlow<Check?>(null)
    val lastCheck: StateFlow<Check?> = _lastCheck.asStateFlow()

    /** Every entitlement write goes through this, so passes cannot interleave. */
    private val gate = Mutex()
    private var passSeq = 0L
    private var lastGrantSeq = 0L
    private var purchaseLaunchedAtMs = 0L

    /** Reads the stored entitlement, then asks Play. The disk value is authoritative first. */
    fun start() {
        scope.launch {
            _adsRemoved.value = store.current().adsRemoved
            refresh("cold-start")
        }
    }

    /**
     * Re-checks on return to the foreground, which is also how a purchase completed while
     * the app was away gets noticed.
     */
    fun onResume() {
        scope.launch { refresh("resume") }
    }

    // ---- connection ----------------------------------------------------------------

    private suspend fun connect(): BillingResult = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            cont.resume(ok())
            return@suspendCancellableCoroutine
        }
        client.startConnection(object : BillingClientStateListener {
            private var resumed = false
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!resumed) { resumed = true; cont.resume(result) }
            }
            override fun onBillingServiceDisconnected() {
                // Auto-reconnection is enabled on the client, so this is informational. If
                // setup never finished, report it as disconnected rather than hanging.
                if (!resumed) {
                    resumed = true
                    cont.resume(
                        BillingResult.newBuilder()
                            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                            .build(),
                    )
                }
            }
        })
    }

    private fun ok(): BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    /**
     * Asks Play for the product. Doubles as the liveness probe: this is the one call Google
     * documents as "performs a network query", so its OK is what proves the pass actually
     * reached Google rather than a local cache.
     */
    private suspend fun probe(): Boolean {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _productDetails.value = result.productDetailsList
                ?.firstOrNull { it.productId == productId }
            return true
        }
        // ITEM_UNAVAILABLE is the expected answer while the product does not exist in the
        // console yet: nothing to sell, so the button hides itself. It is emphatically NOT
        // evidence about ownership, so it fails the probe like any other non-OK code.
        _productDetails.value = null
        return false
    }

    // ---- the one decision ----------------------------------------------------------

    private suspend fun refresh(trigger: String): Check = gate.withLock {
        val seq = ++passSeq

        // Returning from Play's sheet fires onResume before PurchasesUpdatedListener, so a
        // pass started pre-purchase could otherwise land a negative on top of the grant.
        if (SystemClock.elapsedRealtime() - purchaseLaunchedAtMs < PURCHASE_SETTLE_MS) {
            return@withLock conclude(Check.Unknown(0, "purchase-in-flight"))
        }

        val setup = connect()
        if (setup.responseCode != BillingClient.BillingResponseCode.OK) {
            return@withLock conclude(Check.Unknown(setup.responseCode, "setup"))
        }

        val onlineBefore = probe()

        val purchases = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val code = purchases.billingResult.responseCode
        if (code != BillingClient.BillingResponseCode.OK) {
            return@withLock conclude(Check.Unknown(code, "query"))
        }

        // getProducts(), not the legacy skus, and `contains` rather than equality: a single
        // order can carry more than one product.
        val mine = purchases.purchasesList.firstOrNull { productId in it.products }

        when {
            mine != null && mine.purchaseState == Purchase.PurchaseState.PURCHASED -> {
                handlePurchase(mine)
                return@withLock conclude(Check.Owned)
            }

            // Payment not secured yet. Grants nothing, and must not disturb what the player
            // already has.
            mine != null && mine.purchaseState == Purchase.PurchaseState.PENDING -> {
                store.clearNotOwnedStreak()
                return@withLock conclude(Check.Pending)
            }

            // UNSPECIFIED_STATE: a purchase object exists but says nothing. Absence of
            // information is not a negative.
            mine != null -> {
                store.clearNotOwnedStreak()
                return@withLock conclude(Check.Unknown(code, "unspecified-state"))
            }
        }

        // Nothing owned. Before that means anything, the pass has to prove it was online —
        // probing again, because the link may have died between the first probe and the
        // query, which would leave a cached list looking authoritative. Only paid for when
        // there is actually an entitlement to lose.
        val holding = _adsRemoved.value == true
        val onlineProven = onlineBefore && (!holding || probe())

        if (!onlineProven) {
            return@withLock conclude(Check.Unknown(code, "no-proof-of-network"))
        }
        if (!holding) {
            // Nothing to take away; do not touch the confirmation counter.
            return@withLock conclude(Check.NotOwned)
        }

        // Verified online, Play said OK, the product is genuinely absent, and we currently
        // grant it. This is either a refund-and-revoke or a device that never owned it.
        // Confirm once more before taking anything away.
        val streak = store.bumpNotOwnedStreak()
        if (streak < REVOKE_CONFIRMATIONS) {
            return@withLock conclude(Check.Unknown(code, "awaiting-revoke-confirmation"))
        }

        revoke(seq)
        return@withLock conclude(Check.NotOwned)
    }

    private suspend fun conclude(check: Check): Check {
        val code = (check as? Check.Unknown)?.responseCode ?: BillingClient.BillingResponseCode.OK
        store.recordCheck(System.currentTimeMillis(), code)
        _lastCheck.value = check
        if (check is Check.Unknown) Log.i(TAG, "entitlement unresolved: ${check.reason} (${check.responseCode})")
        return check
    }

    // ---- grant / revoke ------------------------------------------------------------

    private suspend fun handlePurchase(purchase: Purchase) {
        if (productId !in purchase.products) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Acknowledge on every path that can see an unacknowledged purchase, not just the
        // listener: if the process dies between buying and acknowledging, Play auto-refunds
        // after three days and the player loses what they paid for.
        if (!purchase.isAcknowledged) acknowledge(purchase)

        grant(++passSeq, purchase.orderId)
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        var backoff = 2_000L
        repeat(ACK_ATTEMPTS) { attempt ->
            val result = client.acknowledgePurchase(params)
            if (result.responseCode == BillingClient.BillingResponseCode.OK) return
            Log.w(TAG, "acknowledge failed (${result.responseCode}), attempt ${attempt + 1}")
            if (attempt < ACK_ATTEMPTS - 1) {
                delay(backoff)
                backoff *= 2
            }
        }
        // isAcknowledged stays false, so the next pass retries. There are three days of them.
    }

    private suspend fun grant(seq: Long, orderId: String?) {
        lastGrantSeq = seq
        _adsRemoved.value = true
        store.grant(System.currentTimeMillis(), orderId)
    }

    private suspend fun revoke(seq: Long) {
        // A pass that began before the most recent grant must never clobber it.
        if (seq < lastGrantSeq) return
        _adsRemoved.value = false
        store.revoke()
    }

    // ---- purchase ------------------------------------------------------------------

    /** True when Play has something to sell — i.e. the product exists and is purchasable. */
    fun isPurchasable(): Boolean = _productDetails.value != null && _adsRemoved.value != true

    /**
     * Opens Play's purchase sheet. Returns the response code, or OK when the sheet opened;
     * the outcome itself arrives through [purchasesListener].
     */
    fun launchPurchase(activity: Activity): Int {
        val details = _productDetails.value ?: return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        purchaseLaunchedAtMs = SystemClock.elapsedRealtime()
        return client.launchBillingFlow(activity, params).responseCode
    }

    /** Manual "restore purchases", for a player who reinstalled or changed device. */
    fun restore(onDone: (Check) -> Unit = {}) {
        scope.launch { onDone(refresh("restore")) }
    }

    private companion object {
        const val TAG = "BillingManager"

        /** Verified-online negatives needed before an entitlement is taken away. */
        const val REVOKE_CONFIRMATIONS = 2

        /** How long after opening Play's sheet a revoke is suppressed. */
        const val PURCHASE_SETTLE_MS = 60_000L

        const val ACK_ATTEMPTS = 3
    }
}
