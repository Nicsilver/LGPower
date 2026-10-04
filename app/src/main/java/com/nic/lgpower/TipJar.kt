package com.nic.lgpower

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** One tip tier as shown in the sheet; [price] is already localised by Play. */
data class TipOption(val productId: String, val label: String, val price: String)

/** What to do with a purchase Play reported, kept free of billing types so it can be unit tested. */
enum class TipAction { CONSUME, PENDING, IGNORE }

object TipRules {
    val productIds = listOf("tip_small", "tip_medium", "tip_large")
    val labels = mapOf("tip_small" to "Small tip", "tip_medium" to "Medium tip", "tip_large" to "Large tip")

    // Constants mirror Purchase.PurchaseState (PURCHASED = 1, PENDING = 2)
    const val STATE_PURCHASED = 1
    const val STATE_PENDING = 2

    fun actionFor(purchaseState: Int, productIds: List<String>): TipAction {
        if (productIds.none { it in TipRules.productIds }) return TipAction.IGNORE
        return when (purchaseState) {
            STATE_PURCHASED -> TipAction.CONSUME
            STATE_PENDING -> TipAction.PENDING
            else -> TipAction.IGNORE
        }
    }
}

interface TipSource {
    val options: List<TipOption>
    fun start()
    fun launch(activity: Activity, productId: String)
    fun refreshPurchases()
    fun close()
}

interface TipListener {
    fun onOptionsReady(options: List<TipOption>)
    fun onPending(pending: Boolean)
    fun onTipped()
    fun onError()
}

/**
 * Google Play one-time tips. Every purchase is consumed straight away, which also
 * acknowledges it, so nothing stays owned and the same tier can be bought again.
 */
class PlayTipJar(context: Context, private val listener: TipListener) : TipSource, PurchasesUpdatedListener {

    private val main = Handler(Looper.getMainLooper())
    private val details = HashMap<String, ProductDetails>()
    // Tokens already sent to consume: onPurchasesUpdated and the onResume query can both report the same
    // purchase, and a second consume would fail and surface an error toast for a tip that went through
    private val consuming = HashSet<String>()
    @Volatile private var closed = false
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override var options: List<TipOption> = emptyList()
        private set

    override fun start() {
        if (client.isReady) { loadProducts(); refreshPurchases(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) return
                loadProducts()
                refreshPurchases()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadProducts() {
        val products = TipRules.productIds.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.INAPP).build()
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, queried ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            val found = queried.productDetailsList
            details.clear()
            found.forEach { details[it.productId] = it }
            options = TipRules.productIds.mapNotNull { id ->
                val price = details[id]?.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
                TipOption(id, TipRules.labels.getValue(id), price)
            }
            post { listener.onOptionsReady(options) }
        }
    }

    override fun launch(activity: Activity, productId: String) {
        val d = details[productId] ?: return listener.onError()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) handleFailure(result.responseCode)
    }

    override fun refreshPurchases() {
        if (!client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, userStarted = false, complete = true)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                // An update lists only the purchases that changed, so it can't say nothing else is still pending
                handle(purchases.orEmpty(), userStarted = true, complete = false)
                refreshPurchases()
            }
            else -> handleFailure(result.responseCode)
        }
    }

    private fun handleFailure(code: Int) {
        when (code) {
            BillingResponseCode.USER_CANCELED -> {}
            // A tip left over from an interrupted run: collect it instead of failing
            BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
            else -> post { listener.onError() }
        }
    }

    private fun handle(purchases: List<Purchase>, userStarted: Boolean, complete: Boolean) {
        var anyPending = false
        purchases.forEach { p ->
            when (TipRules.actionFor(p.purchaseState, p.products)) {
                TipAction.CONSUME -> consume(p, userStarted)
                TipAction.PENDING -> anyPending = true
                TipAction.IGNORE -> {}
            }
        }
        if (complete || anyPending) post { listener.onPending(anyPending) }
    }

    // A failed background consume stays owned and is retried on the next connect, so it needs no toast
    private fun consume(p: Purchase, userStarted: Boolean) {
        if (!synchronized(consuming) { consuming.add(p.purchaseToken) }) return
        val params = ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        client.consumeAsync(params) { result, _ ->
            if (result.responseCode == BillingResponseCode.OK) { post { listener.onTipped() }; return@consumeAsync }
            synchronized(consuming) { consuming.remove(p.purchaseToken) }
            if (userStarted) post { listener.onError() }
        }
    }

    private fun post(block: () -> Unit) = main.post { if (!closed) block() }

    // Ended even while still connecting, otherwise the connection and the Activity listener outlive the screen
    override fun close() {
        closed = true
        client.endConnection()
    }
}

/** Debug-only stand-in so the settings UI can be exercised on an emulator without Play. */
class FakeTipJar(private val listener: TipListener) : TipSource {
    private val main = Handler(Looper.getMainLooper())
    override val options = listOf(
        TipOption("tip_small", "Small tip", "$0.99"),
        TipOption("tip_medium", "Medium tip", "$2.99"),
        TipOption("tip_large", "Large tip", "$4.99"),
    )
    override fun start() { main.postDelayed({ listener.onOptionsReady(options) }, 200) }
    override fun launch(activity: Activity, productId: String) {
        main.postDelayed({ listener.onTipped() }, 500)
    }
    override fun refreshPurchases() {}
    override fun close() {}
}
