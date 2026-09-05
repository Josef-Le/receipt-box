package com.receiptbox.app

import android.app.Application
import com.receiptbox.app.billing.BillingManager
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptDatabase
import com.receiptbox.app.data.ReceiptRepository
import com.receiptbox.app.ocr.OcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReceiptBoxApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var receiptRepository: ReceiptRepository
        private set
    lateinit var preferencesRepository: PreferencesRepository
        private set
    lateinit var billingManager: BillingManager
        private set
    lateinit var ocrHelper: OcrHelper
        private set

    override fun onCreate() {
        super.onCreate()
        val db = ReceiptDatabase.get(this)
        receiptRepository = ReceiptRepository(db)
        preferencesRepository = PreferencesRepository(this)
        billingManager = BillingManager(this, preferencesRepository, appScope)
        billingManager.start()
        ocrHelper = OcrHelper(this)
    }
}
