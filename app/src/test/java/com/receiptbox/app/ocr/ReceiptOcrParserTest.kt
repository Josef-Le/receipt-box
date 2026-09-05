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
     * Realistic ML Kit-ish mix: Hebrew labels (when recognizer/script cooperates) +
     * Latin barcodes/product names + a few recoverable OCR glitches.
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

    @Test
    fun superPharmHebrew_parsesGroundTruth() {
        val p = ReceiptOcrParser.parse(superPharmBiluOcr)

        assertEquals("ILS", p.currency)
        assertEquals("8661758", p.receiptNumber)
        assertEquals(171.47, p.total!!, 0.001)
        assertEquals(26.16, p.tax!!, 0.001)
        assertNotNull(p.subtotal)
        assertTrue("subtotal should be taxable or derived", abs(p.subtotal!! - 145.31) < 0.02 || abs(p.subtotal!! - 145.31) < 0.02)

        assertEquals(2, p.lineItems.size)
        val nic = p.lineItems.first { it.barcode == "7290113560253" || it.name.contains("NICOTINELL", true) }
        assertEquals("7290113560253", nic.barcode)
        assertTrue(nic.name.contains("NICOTINELL", ignoreCase = true))
        assertEquals(1.0, nic.quantity, 0.001)
        assertEquals(152.57, nic.lineTotal, 0.001)

        val lily = p.lineItems.first { it.barcode == "7290000201801" || it.name.contains("PURE", true) }
        assertEquals("7290000201801", lily.barcode)
        assertEquals(35.90, lily.lineTotal, 0.001)

        assertEquals(1, p.discounts.size)
        assertEquals(-17.0, p.discounts[0].amount!!, 0.001)
        assertTrue(
            p.discounts[0].description.orEmpty().contains("קופון") ||
                p.discounts[0].description.orEmpty().contains("PURE", true)
        )

        assertEquals("0778880520", p.phone)
        assertEquals("514203975", p.taxId)
        assertTrue(p.merchant.orEmpty().contains("סופר") || p.merchant.orEmpty().contains("Super-Pharm", true))
        assertTrue(
            p.companyName.orEmpty().contains("שגית") ||
                p.companyName.orEmpty().contains("Super-Pharm", true) ||
                p.brand.orEmpty().contains("Super-Pharm", true)
        )
        assertTrue(p.address.orEmpty().contains("עקרון") || p.address.orEmpty().contains("קרית"))
        assertTrue(p.registerId == "44/52" || p.registerId?.contains("52") == true)
        assertNotNull(p.cashier)
        assertTrue(p.payments.any { it.method == "CARD" && abs(it.amount - 171.47) < 0.01 })

        assertFalse(p.lineItems.any { it.lineTotal == 17.0 || it.lineTotal == 18.0 })
        assertFalse(p.lineItems.any { it.name.contains("קופון") })
        assertFalse(p.lineItems.any { it.name.contains("מע") })

        assertTrue("confidence was ${p.confidence}", p.confidence >= 0.7f)

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
        assertFalse(p.lineItems.any { abs(it.lineTotal - 18.0) < 0.01 })
        assertFalse(p.lineItems.any { abs(it.lineTotal - 17.0) < 0.01 && !it.name.contains("NICOTINELL", true) })
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
}
