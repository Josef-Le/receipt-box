package com.receiptbox.app.billing

import android.app.Activity
import android.content.Context
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
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BillingManager(
    context: Context,
    private val repository: PreferencesRepository,
    private val scope: CoroutineScope
) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                } else {
                    _statusMessage.value = "Billing unavailable (${result.debugMessage})"
                }
            }

            override fun onBillingServiceDisconnected() {
                // Retry on next user action
            }
        })
    }

    fun end() {
        if (billingClient.isReady) billingClient.endConnection()
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(AppConstants.PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = detailsList.firstOrNull()
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        val details = _productDetails.value
        if (details == null) {
            queryProduct()
            _statusMessage.value = "Product not loaded yet. Try again in a moment."
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                scope.launch(Dispatchers.IO) {
                    var unlocked = false
                    purchases.forEach { purchase ->
                        if (AppConstants.PRO_PRODUCT_ID in purchase.products &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                        ) {
                            acknowledgeIfNeeded(purchase)
                            unlocked = true
                        }
                    }
                    repository.setProUnlocked(unlocked)
                    _statusMessage.value = if (unlocked) "Pro unlocked" else "No purchase found"
                }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (AppConstants.PRO_PRODUCT_ID in purchase.products &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        scope.launch(Dispatchers.IO) {
                            acknowledgeIfNeeded(purchase)
                            repository.setProUnlocked(true)
                            _statusMessage.value = "Thanks! Pro unlocked."
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _statusMessage.value = "Purchase canceled"
            }
            else -> {
                _statusMessage.value = "Purchase failed: ${result.debugMessage}"
            }
        }
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    fun formattedPrice(): String {
        val offer = _productDetails.value?.oneTimePurchaseOfferDetails
        return offer?.formattedPrice ?: "$9.99"
    }
}
