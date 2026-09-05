package com.receiptbox.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.receiptbox.app.ocr.ParsedDiscount
import com.receiptbox.app.ocr.ParsedLineItem
import com.receiptbox.app.ocr.ParsedPayment
import com.receiptbox.app.ocr.StructuredReceiptParse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Saves a full Super-Pharm StructuredReceiptParse and asserts critical fields
 * round-trip through Room (company/store/receipt/lines/discounts/payments/raw OCR).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiptRepositoryRoundtripTest {

    private lateinit var db: ReceiptDatabase
    private lateinit var repo: ReceiptRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ReceiptRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun superPharmParse(): StructuredReceiptParse = StructuredReceiptParse(
        rawText = "סופר-פארם בילו\nח.פ. 514203975\nחשב' מס קבלה 8661758\nNICOTINELL 152.57\nPURE 35.90\nקופון -17.00\nלתשלום 171.47",
        merchant = "סופר-פארם בילו סנטר",
        companyName = "חברת שגית לביא בע\"מ",
        brand = "Super-Pharm",
        storeName = "סופר-פארם בילו סנטר",
        address = "קרית עקרון, מתחם",
        phone = "0778880520",
        branch = "44",
        taxId = "514203975",
        datetimeMillis = 1757056620000L, // 2026-09-05 ~10:57 Asia/Jerusalem
        receiptNumber = "8661758",
        cashier = "85281",
        registerId = "44/52",
        currency = "ILS",
        subtotal = 145.31,
        tax = 26.16,
        total = 171.47,
        confidence = 0.95f,
        parserVersion = "1.3-il-real",
        lineItems = listOf(
            ParsedLineItem(
                name = "NICOTINELL MINT 4M",
                barcode = "7290113560253",
                quantity = 1.0,
                unit = "ea",
                unitPrice = 152.57,
                lineTotal = 152.57
            ),
            ParsedLineItem(
                name = "Lily PURE 24",
                barcode = "7290000201801",
                quantity = 1.0,
                unit = "ea",
                unitPrice = 35.90,
                lineTotal = 35.90
            )
        ),
        discounts = listOf(
            ParsedDiscount(description = "קופון לילי PURE", amount = -17.0)
        ),
        payments = listOf(
            ParsedPayment(method = "CARD", amount = 171.47, last4 = null, authCode = null)
        )
    )

    @Test
    fun saveSuperPharm_readsBackEqualCriticalFields() = runBlocking {
        val parse = superPharmParse()
        val id = repo.saveFromParse(
            photoUri = "file:///tmp/superpharm.jpg",
            parse = parse,
            category = "Healthcare",
            notes = "bilu ground truth"
        )
        assertTrue(id > 0)

        val detail = repo.getDetail(id)
        assertNotNull(detail)
        val d = detail!!

        // Company
        assertNotNull(d.company)
        assertEquals("חברת שגית לביא בע\"מ", d.company!!.legalName)
        assertEquals("514203975", d.company!!.taxId)
        assertEquals("Super-Pharm", d.company!!.brand)

        // Store
        assertNotNull(d.store)
        assertEquals("סופר-פארם בילו סנטר", d.store!!.name)
        assertEquals("קרית עקרון, מתחם", d.store!!.address)
        assertEquals("0778880520", d.store!!.phone)
        assertEquals("44", d.store!!.branch)

        // Receipt header
        val r = d.receipt
        assertEquals("8661758", r.receiptNumber)
        assertEquals("ILS", r.currency)
        assertEquals(145.31, r.subtotal!!, 0.001)
        assertEquals(26.16, r.tax!!, 0.001)
        assertEquals(171.47, r.total, 0.001)
        assertEquals(ReceiptStatus.PURCHASE.name, r.status)
        assertEquals("85281", r.cashier)
        assertEquals("44/52", r.registerId)
        assertEquals("Healthcare", r.category)
        assertEquals("bilu ground truth", r.notes)
        assertEquals(parse.datetimeMillis, r.datetime)
        assertTrue(r.parseConfidence >= 0.9f)

        // Line items
        assertEquals(2, d.lineItems.size)
        val nic = d.lineItems.first { it.barcode == "7290113560253" }
        assertEquals("NICOTINELL MINT 4M", nic.name)
        assertEquals(1.0, nic.quantity, 0.001)
        assertEquals("ea", nic.unit)
        assertEquals(152.57, nic.unitPrice!!, 0.001)
        assertEquals(152.57, nic.lineTotal, 0.001)

        val lily = d.lineItems.first { it.barcode == "7290000201801" }
        assertEquals(35.90, lily.lineTotal, 0.001)
        assertEquals("ea", lily.unit)

        // Discounts / payments / raw OCR
        assertEquals(1, d.discounts.size)
        assertEquals(-17.0, d.discounts[0].amount!!, 0.001)
        assertTrue(d.payments.any { it.method == "CARD" && abs(it.amount - 171.47) < 0.001 })
        assertNotNull(d.rawOcr)
        assertTrue(d.rawOcr!!.rawText.contains("514203975"))
        assertEquals("1.3-il-real", d.rawOcr!!.parserVersion)

        // Search hits
        assertTrue(repo.search("514203975").any { it.id == id })
        assertTrue(repo.search("8661758").any { it.id == id })
        assertTrue(repo.search("NICOTINELL").any { it.id == id })
        assertTrue(repo.search("7290113560253").any { it.id == id })
        assertTrue(repo.search("bilu ground").any { it.id == id })
        assertTrue(repo.search("סופר-פארם").any { it.id == id })
    }
}
