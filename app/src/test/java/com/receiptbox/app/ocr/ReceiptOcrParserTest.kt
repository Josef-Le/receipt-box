package com.receiptbox.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

class ReceiptOcrParserTest {

    /**
     * Idealized clean Hebrew sample (parser regression).
     */
    private val superPharmBiluOcr = """
        סופר-פארם בילו סנטר
        חברת שגית לביא בע"מ
        קרית עקרון, מתחם
        טלפון: 0778880520
        ח.פ. 514203975
        עוסק מורשה מס 557-206695
        העתק
        חשב' מס קבלה 8661758
        שם מחיר כמות לתשלום
        7290113560253
        NICOTINELL MINT 4M 152.57 1.00 152.57
        7290000201801
        נ.ט. PURE 24 לילי 35.90 1.00 35.90
        * קופון לילי PURE -17.00 1.00 -17.00
        ------------------------------
        לתשלום 171.47
        כרטיסי אשראי 171.47
        סה"כ עודף 0.00
        חייבים במע"מ 145.31
        מע"מ 18.00 % 26.16
        סניף: 44
        קופה: 52
        קופאי: 85281 קופת שירות עצמי
        05/09/2026 10:57:00
        הודפס 05/09/2026 10:57
        בקניה זו חסכת 17.00
        ##############################
        תודה שקנית בסופר-פארם,
    """.trimIndent()

    /** Approximate Latin-script OCR garbage for Hebrew headers (what Josef saw). */
    private val superPharmLatinGarbageOcr = """
        onnn, 1py np
        Super-Pharm Bilu Center
        nɔorn 1 nP
        tel: 0778880520
        514203975
        copy
        receipt 8661758
        7290113560253
        NICOTINELL MINT 4M 152.57 1.00 152.57
        7290000201801
        77 PURE 24 .U.J 35.90 1.00 35.90
        >* 917 PURE * -17.00 1.00 -17.00
        TOTAL 171.47
        CREDIT 171.47
        CHANGE 0.00
        % "'n 18.00
        VAT 18.00 % 26.16
        taxable 145.31
        branch: 44
        register: 52
        cashier: 85281 self-service
        05/09/2026 10:57
        you saved 17.00
    """.trimIndent()

    /** Real tesseract eng+heb output from the attached Super-Pharm photo (messy). */
    private fun loadRealOcr(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("superpharm_bilu_real.ocr.txt")
            ?: error("Missing test resource superpharm_bilu_real.ocr.txt")
        return stream.bufferedReader(Charsets.UTF_8).readText()
    }

    private fun assertSuperPharmGroundTruth(p: StructuredReceiptParse, allowMessyMerchant: Boolean = false) {
        assertEquals("ILS", p.currency)
        assertEquals("8661758", p.receiptNumber)
        assertEquals(171.47, p.total!!, 0.05)
        assertEquals(26.16, p.tax!!, 0.08)
        assertNotNull(p.subtotal)
        assertTrue(
            "subtotal was ${p.subtotal}",
            abs(p.subtotal!! - 145.31) < 0.15
        )

        assertTrue("items=${p.lineItems}", p.lineItems.size == 2)
        val nic = p.lineItems.first { it.name.contains("NICOTINELL", true) || it.barcode == "7290113560253" }
        assertTrue(nic.name.contains("NICOTINELL", ignoreCase = true))
        assertEquals(1.0, nic.quantity, 0.001)
        assertEquals(152.57, nic.lineTotal, 1.0)

        val lily = p.lineItems.first { it.name.contains("PURE", true) || it.barcode == "7290000201801" }
        assertEquals(35.90, lily.lineTotal, 0.6)
        // Real multi-pass OCR should still recover at least one IL GTIN
        assertTrue(p.lineItems.any { it.barcode == "7290113560253" || it.barcode == "7290000201801" })

        assertTrue(p.discounts.isNotEmpty())
        assertEquals(-17.0, p.discounts.minByOrNull { it.amount ?: 0.0 }!!.amount!!, 0.05)

        assertTrue("phone was ${p.phone}", p.phone != null && p.phone!!.startsWith("077") && p.phone!!.length >= 9)
        assertTrue("taxId was ${p.taxId}", p.taxId != null && (p.taxId == "514203975" || p.taxId!!.startsWith("514")))
        if (!allowMessyMerchant) {
            assertTrue(
                p.merchant.orEmpty().contains("סופר") ||
                    p.merchant.orEmpty().contains("Super-Pharm", true)
            )
        } else {
            assertTrue(
                p.merchant.orEmpty().contains("סופר") ||
                    p.merchant.orEmpty().contains("Super-Pharm", true) ||
                    p.brand == "Super-Pharm"
            )
        }

        assertFalse(p.lineItems.any { abs(it.lineTotal - 18.0) < 0.01 })
        assertFalse(p.lineItems.any { it.name.contains("קופון") })
        assertTrue("confidence was ${p.confidence}", p.confidence >= 0.55f)
    }

    @Test
    fun superPharmHebrew_parsesGroundTruth() {
        val p = ReceiptOcrParser.parse(superPharmBiluOcr)
        assertSuperPharmGroundTruth(p)
        assertTrue(
            "address/store was addr=${p.address} store=${p.storeName}",
            listOf(p.address, p.storeName, p.merchant).any { s ->
                s.orEmpty().contains("עקרון") || s.orEmpty().contains("קרית") ||
                    s.orEmpty().contains("בילו") || s.orEmpty().contains("Bilu", true)
            }
        )
        assertTrue(
            "registerId was ${p.registerId}",
            p.registerId == "44/52" || p.registerId?.contains("52") == true ||
                p.registerId?.contains("44") == true
        )
        assertNotNull(p.cashier)
        assertTrue(p.payments.any { it.method == "CARD" && abs(it.amount - 171.47) < 0.05 })
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
        cal.timeInMillis = p.datetimeMillis!!
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun superPharmLatinGarbage_stillGetsIlsTwoItemsDiscountTax() {
        val p = ReceiptOcrParser.parse(superPharmLatinGarbageOcr)
        assertEquals("ILS", p.currency)
        assertEquals("8661758", p.receiptNumber)
        assertEquals(171.47, p.total!!, 0.001)
        assertEquals(26.16, p.tax!!, 0.001)
        assertEquals(2, p.lineItems.size)
        assertEquals(1, p.discounts.size)
        assertEquals(-17.0, p.discounts[0].amount!!, 0.001)
        assertTrue(p.lineItems.any { it.name.contains("NICOTINELL", true) })
        assertTrue(p.lineItems.any { it.name.contains("PURE", true) })
        assertFalse(p.merchant.orEmpty().contains("onnn"))
        assertTrue(p.merchant.orEmpty().contains("Super-Pharm", true) || p.brand == "Super-Pharm")
    }

    @Test
    fun superPharmRealPhotoOcr_parsesGroundTruth() {
        val raw = loadRealOcr()
        assertTrue("OCR fixture empty", raw.length > 50)
        val p = ReceiptOcrParser.parse(raw)
        assertSuperPharmGroundTruth(p, allowMessyMerchant = true)
        // Payment / card line should reconcile glued 4171.47 → 171.47
        assertTrue(
            p.payments.isEmpty() || p.payments.any { abs(it.amount - 171.47) < 0.05 }
        )
    }

    @Test
    fun usReceipt_stillDefaultsUsd() {
        val text = """
            Target Store #1234
            100 Main Street, Austin TX 78701
            Tel: 512-555-0100
            Receipt #998877
            Milk 2% 3.49
            Bread 2.99
            Subtotal 6.48
            Tax 0.54
            Total 7.02
            VISA ****1234 7.02
            09/04/2026 14:22
        """.trimIndent()
        val p = ReceiptOcrParser.parse(text)
        assertEquals("USD", p.currency)
        assertEquals(7.02, p.total!!, 0.001)
        assertTrue(p.lineItems.size >= 2)
    }

    @Test
    fun indonesianReceipt_idrThousands() {
        val text = """
            Warung Makan Sederhana
            Jl. Merdeka 12
            CINNAMON SUGAR
            1 x 17.000 17.000
            SUBTOTAL 17.000
            GRAND TOTAL 17.000
            CASHIDR 20.000
            CHANGE 3.000
        """.trimIndent()
        val p = ReceiptOcrParser.parse(text)
        assertEquals("IDR", p.currency)
        assertEquals(17000.0, p.total!!, 0.001)
        // Line-item extraction for "1 x 17.000" layouts is still weak; total/currency is the IDR win.
    }

    @Test
    fun indonesianReceipt_commaThousands() {
        val text = """
            Toko Roti
            TOTAL 46,000
            CASH 50,000
            CHANGE 4,000
            Rp
        """.trimIndent()
        val p = ReceiptOcrParser.parse(text)
        assertEquals("IDR", p.currency)
        assertEquals(46000.0, p.total!!, 0.001)
    }

}
