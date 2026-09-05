package com.receiptbox.app.data

import android.util.Log
import com.receiptbox.app.ocr.ParsedDiscount
import com.receiptbox.app.ocr.ParsedLineItem
import com.receiptbox.app.ocr.ParsedPayment
import com.receiptbox.app.ocr.StructuredReceiptParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inserts demo receipts on first DEBUG launch for emulator screenshots. */
object DebugSeed {
    private const val TAG = "DebugSeed"
    private const val MARKER_NOTES = "DEBUG_SEED_v1"

    suspend fun maybeSeed(
        repository: ReceiptRepository,
        preferencesRepository: PreferencesRepository
    ) = withContext(Dispatchers.IO) {
        try {
            preferencesRepository.setDebugUnlock(true)
            val existing = repository.count()
            if (existing > 0) {
                Log.i(TAG, "Skip seed; already have $existing receipts")
                return@withContext
            }

            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000

            repository.saveFromParse(
                photoUri = "content://demo/seed/grocery.png",
                parse = sampleGrocery(now - 2 * day),
                category = "Groceries",
                notes = MARKER_NOTES
            )
            repository.saveFromParse(
                photoUri = "content://demo/seed/cafe.png",
                parse = sampleCafe(now - day),
                category = "Meals",
                notes = MARKER_NOTES
            )
            repository.saveFromParse(
                photoUri = "content://demo/seed/office.png",
                parse = sampleOffice(now - 5 * day),
                category = "Office",
                notes = MARKER_NOTES
            )
            Log.i(TAG, "Seeded demo receipts")
        } catch (t: Throwable) {
            Log.e(TAG, "Seed failed", t)
        }
    }

    private fun sampleGrocery(whenMs: Long) = StructuredReceiptParse(
        rawText = "WHOLE FOODS MARKET\nDEMO SEED RECEIPT",
        merchant = "Whole Foods Market",
        companyName = "Amazon.com, Inc.",
        brand = "Whole Foods",
        taxId = "91-1646860",
        storeName = "Whole Foods - Downtown",
        address = "123 Market St, San Francisco, CA",
        phone = "415-555-0142",
        branch = "12",
        datetimeMillis = whenMs,
        receiptNumber = "WF-1001",
        cashier = "Alex",
        registerId = "R3",
        currency = "USD",
        subtotal = 42.50,
        tax = 3.61,
        total = 46.11,
        confidence = 0.92f,
        lineItems = listOf(
            ParsedLineItem(name = "Organic Bananas", sku = "BAN-4011", barcode = "4011", quantity = 1.2, unitPrice = 0.79, lineTotal = 0.95, taxFlag = "T"),
            ParsedLineItem(name = "Almond Milk", sku = "AM-32OZ", barcode = "072250011234", quantity = 2.0, unitPrice = 3.99, lineTotal = 7.98, taxFlag = "T"),
            ParsedLineItem(name = "Avocado Toast Kit", sku = "AVO-KIT", quantity = 1.0, unitPrice = 8.99, lineTotal = 8.99, taxFlag = "T"),
            ParsedLineItem(name = "Cold Brew Coffee", sku = "CB-16", barcode = "072250099988", quantity = 1.0, unitPrice = 4.49, lineTotal = 4.49, taxFlag = "T")
        ),
        discounts = listOf(ParsedDiscount("Member Save", "MEM5", 2.50, null)),
        payments = listOf(ParsedPayment("VISA", 46.11, "4242", "OK8821", null))
    )

    private fun sampleCafe(whenMs: Long) = StructuredReceiptParse(
        rawText = "BLUE BOTTLE COFFEE\nDEMO SEED",
        merchant = "Blue Bottle Coffee",
        companyName = "Blue Bottle Coffee, Inc.",
        brand = "Blue Bottle",
        taxId = "26-1234567",
        storeName = "Blue Bottle - Hayes Valley",
        address = "66 Mint St, San Francisco, CA",
        phone = "415-555-0199",
        datetimeMillis = whenMs,
        receiptNumber = "BB-7788",
        cashier = "Sam",
        registerId = "POS1",
        currency = "USD",
        subtotal = 14.00,
        tax = 1.19,
        total = 15.19,
        confidence = 0.88f,
        lineItems = listOf(
            ParsedLineItem(name = "Latte", sku = "LATTE", quantity = 1.0, unitPrice = 5.50, lineTotal = 5.50, taxFlag = "T"),
            ParsedLineItem(name = "Croissant", sku = "CROIS", quantity = 1.0, unitPrice = 4.25, lineTotal = 4.25, taxFlag = "T"),
            ParsedLineItem(name = "Pour Over", sku = "POUR", quantity = 1.0, unitPrice = 4.25, lineTotal = 4.25, taxFlag = "T")
        ),
        payments = listOf(ParsedPayment("Apple Pay", 15.19, null, "APPL1", null))
    )

    private fun sampleOffice(whenMs: Long) = StructuredReceiptParse(
        rawText = "STAPLES\nDEMO SEED RECEIPT",
        merchant = "Staples",
        companyName = "Staples, Inc.",
        brand = "Staples",
        taxId = "04-2896127",
        storeName = "Staples #214",
        address = "500 Mission St, San Francisco, CA",
        phone = "415-555-0100",
        datetimeMillis = whenMs,
        receiptNumber = "ST-5520",
        cashier = "Jordan",
        registerId = "04",
        currency = "USD",
        subtotal = 67.96,
        tax = 5.78,
        total = 73.74,
        confidence = 0.9f,
        lineItems = listOf(
            ParsedLineItem(name = "Copy Paper 500ct", sku = "PAPER-500", barcode = "071641012345", quantity = 2.0, unitPrice = 12.99, lineTotal = 25.98, taxFlag = "T"),
            ParsedLineItem(name = "Gel Pens 12pk", sku = "PEN-GEL12", barcode = "071641098765", quantity = 1.0, unitPrice = 9.99, lineTotal = 9.99, taxFlag = "T"),
            ParsedLineItem(name = "USB-C Hub", sku = "HUB-USBC", barcode = "071641055512", quantity = 1.0, unitPrice = 31.99, lineTotal = 31.99, taxFlag = "T")
        ),
        discounts = listOf(ParsedDiscount("Coupon", "SAVE10", 5.00, null)),
        payments = listOf(ParsedPayment("MASTERCARD", 73.74, "1111", "MC9901", null))
    )
}
