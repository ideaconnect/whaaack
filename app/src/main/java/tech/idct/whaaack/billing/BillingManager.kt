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
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.idct.whaaack.data.EntitlementStore
import java.security.MessageDigest
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

    /** Something the player should be told about, arising outside a call they made. */
    sealed interface Event {
        /** Payment is not secured yet — cash, carrier billing or parental approval. */
        data object PurchasePending : Event

        /**
         * The sheet opened and then failed — a declined card, a network drop, a service
         * outage. Deliberately not raised for USER_CANCELED, which is a decision rather than
         * a failure and needs no commentary.
         *
         * Without this the whole thing is silent: `launchBillingFlow` already returned OK
         * because the sheet did open, so the ViewModel's synchronous error path cannot fire,
         * and the real outcome arrives here where it used to be logged and dropped. The
         * player is left looking at the same screen and the same button, concluding the
         * button is broken rather than that their payment failed.
         */
        data class PurchaseFailed(val responseCode: Int) : Event
    }

    private val app = context.applicationContext

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases.orEmpty().forEach { purchase ->
                    // PENDING is the one outcome the sheet leaves entirely unexplained. Cash,
                    // carrier billing and parental approval all complete Play's flow without
                    // granting anything, and handlePurchase returns silently for any state
                    // that is not PURCHASED — so the player pays, comes back, and finds the
                    // Remove ads button exactly where they left it with no word about why.
                    if (purchase.purchaseState == Purchase.PurchaseState.PENDING &&
                        productId in purchase.products
                    ) {
                        _events.tryEmit(Event.PurchasePending)
                    }
                    scope.launch { handlePurchase(purchase) }
                }

            // Google's own guidance: this is not an error, it is a cue to re-query. Showing
            // a failure here would strand a reinstalled paying player in front of the paywall.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                scope.launch {
                    // This is a *recovery* path, so it must not be swallowed by the settle
                    // window that exists to protect a fresh grant — clear it first.
                    purchaseLaunchedAtMs = 0L
                    refresh("already-owned")
                }

            BillingClient.BillingResponseCode.USER_CANCELED -> Unit

            else -> {
                Log.w(TAG, "Purchase flow: ${result.responseCode} ${result.debugMessage}")
                _events.tryEmit(Event.PurchaseFailed(result.responseCode))
            }
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

    /**
     * Replay-free so a rotation cannot re-toast, and buffered so an emit from the Play
     * listener never has to suspend.
     */
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Every entitlement write goes through this, so passes cannot interleave. */
    private val gate = Mutex()
    private var passSeq = 0L
    private var lastGrantSeq = 0L

    @Volatile
    private var purchaseLaunchedAtMs = 0L

    /**
     * Whether this process has already contributed a verified-online negative. The revoke
     * rule is two of them; this is what keeps the two from being the same moment twice.
     */
    private var confirmedNotOwnedThisProcess = false

    /**
     * Opaque, stable per-account handle for Play's fraud detection. Never the raw Supabase
     * id and never an email — Google's own guidance is that this value must not identify the
     * user to anyone reading an order.
     */
    @Volatile
    private var obfuscatedAccountId: String? = null

    /** Null when signed out; the Home upsell is offered to signed-out players too. */
    fun setAccount(userId: String?) {
        obfuscatedAccountId = userId?.takeIf { it.isNotBlank() }?.let { raw ->
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
                // Play caps this at 64 characters, which a hex SHA-256 exactly fills.
                .take(64)
        }
    }

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

    /**
     * Asks again because something wanted to offer the product and had no price for it.
     *
     * Without this, [price] is only ever populated at cold start and on return to the
     * foreground — so a player who launched offline and then came back onto a network
     * *without* the app going to the background never sees the upsell again for the whole
     * session, however many ads they sit through. The caller is expected to rate-limit; the
     * one that exists calls it only when an ad actually plays, which the interstitial cap
     * already bounds to one every couple of minutes.
     */
    fun refreshOffer() {
        scope.launch { refresh("offer-missing") }
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
     * reached Google rather than a local cache. Returns whether Play answered — **not**
     * whether there is anything to sell.
     *
     * The price obeys the same rule as the entitlement: it is only ever taken away on an
     * answer, never on a silence. A non-OK response means the question did not get through,
     * so whatever was on offer stays on offer — hiding a live product because one query timed
     * out costs a sale, blanks the ad-break dialog, and tells the player nothing. Only an OK
     * that came back without the product clears it, because that is Play saying it has
     * nothing for this account.
     *
     * The reason is logged in every failing case. Which of them fired is the whole diagnosis
     * when someone asks why the upsell is not showing, and it used to be entirely silent —
     * `PRODUCT_NOT_FOUND` (wrong id, inactive, or still propagating) and `NO_ELIGIBLE_OFFER`
     * (a purchase option that is not backwards compatible, so the singular
     * `oneTimePurchaseOfferDetails` this code reads is empty) are completely different
     * problems that present identically as a missing button.
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

        val (billingResult, result) = queryProducts(params)
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "product query unanswered: ${billingResult.responseCode} " +
                "${billingResult.debugMessage} — keeping the last known offer")
            return false
        }

        val details = result?.productDetailsList?.firstOrNull { it.productId == productId }
        if (details != null) {
            _productDetails.value = details
            return true
        }

        // Play answered and did not include the product. In Billing 9 the reason rides in
        // the unfetched list rather than in the response code, which is why this no longer
        // looks for ITEM_UNAVAILABLE.
        val unfetched = result?.unfetchedProductList?.firstOrNull { it.productId == productId }
        Log.i(TAG, "no offer for $productId: ${unfetchedReason(unfetched?.statusCode)}")
        _productDetails.value = null
        return true
    }

    private fun unfetchedReason(status: Int?): String = when (status) {
        null -> "absent from the response"
        UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND ->
            "PRODUCT_NOT_FOUND — no such id, or it is not Active, or it has not propagated yet"
        UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER ->
            "NO_ELIGIBLE_OFFER — the purchase option is not backwards compatible, so the " +
                "singular oneTimePurchaseOfferDetails is empty"
        UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT ->
            "INVALID_PRODUCT_ID_FORMAT — REMOVE_ADS_PRODUCT_ID is malformed"
        else -> "status $status"
    }

    /**
     * The callback form rather than the `billing-ktx` suspend helper, which is otherwise
     * exactly what this is: `ProductDetailsResult` keeps only the billing result and the
     * fetched list, dropping `unfetchedProductList` — the half that says *why* nothing came
     * back, and the only thing that makes a missing price diagnosable from a log.
     */
    private suspend fun queryProducts(
        params: QueryProductDetailsParams,
    ): Pair<BillingResult, QueryProductDetailsResult?> = suspendCancellableCoroutine { cont ->
        var resumed = false
        client.queryProductDetailsAsync(params) { billingResult, result ->
            if (!resumed) {
                resumed = true
                cont.resume(billingResult to result)
            }
        }
    }

    // ---- the one decision ----------------------------------------------------------

    private suspend fun refresh(trigger: String): Check = gate.withLock {
        val seq = ++passSeq

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

        // Returning from Play's sheet fires onResume before PurchasesUpdatedListener, so a
        // pass that started pre-purchase could land a negative on top of a grant that is
        // about to arrive. This guard belongs *here*, immediately before the only branch
        // that can take something away — not at the top of the pass.
        //
        // It used to sit at the top, which made it swallow both recovery paths: a
        // reinstalled owner who taps "Remove ads" gets ITEM_ALREADY_OWNED, whose whole job
        // is to trigger a re-query, and that re-query returned "purchase-in-flight" without
        // asking Play anything; and "Restore purchases" answered "Couldn't reach Google
        // Play — nothing has changed" for a minute after any cancelled sheet, while Play was
        // perfectly reachable. A pass that can only ever *grant* must never be suppressed.
        // The `!= 0L` matters: elapsedRealtime is uptime, so the sentinel 0 reads as "a
        // purchase was launched at boot" for the first minute of every device's uptime.
        if (purchaseLaunchedAtMs != 0L &&
            SystemClock.elapsedRealtime() - purchaseLaunchedAtMs < PURCHASE_SETTLE_MS
        ) {
            return@withLock conclude(Check.Unknown(0, "purchase-in-flight"))
        }

        // Verified online, Play said OK, the product is genuinely absent, and we currently
        // grant it. This is either a refund-and-revoke or a device that never owned it.
        // Confirm once more before taking anything away.
        //
        // At most one confirmation per process, which is what makes the second one worth
        // anything. Every cold start runs two passes within a second of each other —
        // `start()` and then MainActivity's `onResume()` — so without this guard both
        // confirmations landed on the same launch, off two calls close enough together to
        // fail the same way, and the "two verified-online negatives" rule collapsed into
        // one. It also stops a player double-tapping Restore into a revocation.
        if (confirmedNotOwnedThisProcess) {
            return@withLock conclude(Check.NotOwned)
        }
        val streak = store.bumpNotOwnedStreak()
        confirmedNotOwnedThisProcess = true
        if (streak < REVOKE_CONFIRMATIONS) {
            // Not Unknown: Play was reached and answered. Reporting this as "couldn't check"
            // told a player tapping Restore that nothing had changed, while a counter that
            // can end their entitlement had just moved.
            return@withLock conclude(Check.NotOwned)
        }

        revoke(seq)
        return@withLock conclude(Check.NotOwned)
    }

    private suspend fun conclude(check: Check): Check {
        val code = (check as? Check.Unknown)?.responseCode ?: BillingClient.BillingResponseCode.OK
        store.recordCheck(System.currentTimeMillis(), code)
        if (check is Check.Unknown) Log.i(TAG, "entitlement unresolved: ${check.reason} (${check.responseCode})")
        return check
    }

    // ---- grant / revoke ------------------------------------------------------------

    private suspend fun handlePurchase(purchase: Purchase) {
        if (productId !in purchase.products) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Grant *first*. Play has already reported this as PURCHASED, so the entitlement is
        // owed the moment we see it, and acknowledgement is a separate obligation to Google
        // rather than a condition of it. Acknowledging first put up to six seconds of backoff
        // between paying and the ads stopping — with the Remove ads button still sitting
        // there — and a process death inside that window left nothing on disk at all.
        grant(++passSeq, purchase.orderId)

        // Acknowledge on every path that can see an unacknowledged purchase, not just the
        // listener: if the process dies between buying and acknowledging, Play auto-refunds
        // after three days and the player loses what they paid for.
        if (!purchase.isAcknowledged) acknowledge(purchase)
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
            .apply { obfuscatedAccountId?.let { setObfuscatedAccountId(it) } }
            .build()
        purchaseLaunchedAtMs = SystemClock.elapsedRealtime()
        return client.launchBillingFlow(activity, params).responseCode
    }

    /** Manual "restore purchases", for a player who reinstalled or changed device. */
    fun restore(onDone: (Check) -> Unit = {}) {
        scope.launch { onDone(refresh("restore")) }
    }

    /**
     * Releases the bound connection to the Play Store. The client holds applicationContext,
     * so this is hygiene rather than a leak — but a ServiceConnection left behind by every
     * ViewModel that ever existed is still a ServiceConnection left behind.
     */
    fun close() {
        runCatching { client.endConnection() }
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
