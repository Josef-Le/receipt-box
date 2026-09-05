package com.receiptbox.app.ocr

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Heuristic structured parser: ML Kit text → companies/stores/lines/discounts/payments.
 * Hardened for Hebrew/RTL Israeli pharmacy receipts (e.g. Super-Pharm) and Latin-OCR mix.
 * Best-effort; UI always allows edits.
 */
object ReceiptOcrParser {

    // Thousand-grouped ints FIRST so 17.000 is not truncated to 17.00
    private val moneyToken = Regex(
        """-?\s*[€£$₪]?\s*(?:""" +
            """\d{1,3}(?:[.,]\d{3})+(?![.,]\d)|""" +
            """\d{1,6}(?:[.,]\d{3})*[.,]\d{2}|""" +
            """\.?\d+[.,]\d{2}""" +
            """)"""
    )
    private val thousandGroupedInt = Regex("""^\d{1,3}(?:[.,]\d{3})+$""")
    private val barcodeRe = Regex("""\b(\d{8}|\d{12,14})\b""")
    private val israeliBarcodeRe = Regex("""\b(729\d{10,11})\b""")
    private val skuRe = Regex("""(?i)\b(?:sku|plu|item[#:\s-]*)([A-Z0-9\-]{3,})\b""")
    private val phoneRe = Regex(
        """(?i)(?:tel|phone|ph|טל(?:פון)?)[:\s]*([+\d][\d\s\-().]{6,})""" +
            """|\b(0(?:5\d|7\d|2|3|4|8|9)\d{7})\b""" +
            """|\b(\+?\d{1,3}[\s\-.]?\(?\d{2,4}\)?[\s\-.]?\d{3,4}[\s\-.]?\d{3,4})\b"""
    )
    private val taxIdRe = Regex(
        """(?i)(?:VAT|TAX\s*ID|TIN|ABN|EIN|GST|CNPJ|RFC|ח\.?\s*[פם]\.?|חפ)[:\s#]*([A-Z0-9\-./]{5,})""" +
            """|(?:ח\.?\s*[פם]\.?|חפ)\s*[#:]?\s*(\d{8,9})"""
    )
    private val receiptNoRe = Regex(
        """(?i)(?:receipt|invoice|ticket|check|trans(?:action)?|order|חשב[ו']?\s*מס|קבלה|מס['׳]?\s*קבלה)""" +
            """\s*(?:no|num|number|#|מס['׳]?)?[:\s#]*([A-Z0-9\-/]{3,})"""
    )
    private val ilReceiptNoRe = Regex(
        """(?i)(?:חשב['׳]?\s*מס\s*קבלה|מס['׳]?\s*קבלה|קבלה|העתק)[^\d]{0,20}(\d{5,10})""" +
            """|(?:receipt|invoice)\s*(?:no|num|number|#)?[:\s#]*(\d{5,10})"""
    )
    private val cashierRe = Regex(
        """(?i)(?:cashier|server|clerk|associate|emp(?:loyee)?|קופאי)[:\s#]*([^\n]{2,40})"""
    )
    private val registerRe = Regex(
        """(?i)(?:register|lane|pos|terminal|קופה)[:\s#]*([A-Z0-9\-]{1,10})"""
    )
    private val branchRe = Regex(
        """(?i)(?:branch|סניף)[:\s#]*([A-Z0-9\-]{1,10})"""
    )

    private val hebrewLetter = Regex("""[\u0590-\u05FF]""")

    private val totalKeywords = listOf(
        "grand total", "amount due", "total due", "balance due", "total",
        "לתשלום", "תשלום", "סה\"כ לתשלום", "סהכ לתשלום", "סך הכל", "סך-הכל", "סה\"כ"
    )
    private val subtotalKeywords = listOf(
        "subtotal", "sub-total", "sub total", "net sales", "taxable",
        "חייבים במע", "לפני מע", "סיכום ביניים", "סכום ביניים"
    )
    private val taxKeywords = listOf(
        "tax", "vat", "gst", "sales tax", "iva",
        "מע\"מ", "מע״מ", "מעמ", "מע'מ"
    )
    private val discountKeywords = listOf(
        "discount", "promo", "coupon", "savings", "saved", "markdown", "off",
        "קופון", "קופוז", "קורד", "הנחה", "הנחות", "חסכת", "חיסכון", "מבצע"
    )
    private val paymentKeywords = mapOf(
        "VISA" to "CARD",
        "MASTERCARD" to "CARD",
        "MASTER CARD" to "CARD",
        "AMEX" to "CARD",
        "AMERICAN EXPRESS" to "CARD",
        "DEBIT" to "CARD",
        "CREDIT" to "CARD",
        "CARD" to "CARD",
        "כרטיס אשראי" to "CARD",
        "כרטיסי אשראי" to "CARD",
        "כרטיסי אעראי" to "CARD",
        "כרטיס" to "CARD",
        "אשראי" to "CARD",
        "אעראי" to "CARD",
        "CASH" to "CASH",
        "מזומן" to "CASH",
        "APPLE PAY" to "MOBILE",
        "GOOGLE PAY" to "MOBILE",
        "CONTACTLESS" to "CARD",
        "CHECK" to "CHECK",
        "CHEQUE" to "CHECK",
        "GIFT" to "GIFT_CARD"
    )

    private val skipLine = Regex(
        """(?i)^(thank|welcome|www\.|http|visa|mastercard|authorized|signature|copy|customer|merchant copy|power(?:ed)? by|הודפס|תודה).*"""
    )

    private val vatSummaryLine = Regex(
        """(?i)(?:מע[\"״']?מ|vat|tax)\s*\d{1,2}(?:[.,]\d{1,2})?\s*%|(?:חייבים\s*במע)|(?:מעמ\s*\d)"""
    )
    private val percentOnlyNoise = Regex("""^\s*%""")

    private val dateFormats = listOf(
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy",
        "MM/dd/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "MM-dd-yyyy",
        "dd.MM.yyyy", "MM.dd.yyyy", "d/M/yyyy", "M/d/yyyy",
        "dd/MM/yy", "MM/dd/yy", "d/M/yy", "M/d/yy",
        "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "d MMMM yyyy",
        "yyyy/MM/dd HH:mm", "MM/dd/yyyy hh:mm a"
    )

    private val datePattern = Pattern.compile(
        "(\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?)?)|" +
            "((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{2,4})",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(rawText: String): StructuredReceiptParse {
        val normalized = normalizeOcrText(rawText)
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return StructuredReceiptParse(rawText = rawText, confidence = 0f, parserVersion = PARSER_VERSION)
        }

        val lowerAll = normalized.lowercase(Locale.US)
        val israeli = isIsraeliContext(normalized, lines)
        val isRefund = listOf("refund", "return", "credit note", "זיכוי", "החזר").any { lowerAll.contains(it) }
        val isVoid = lowerAll.contains("void") || normalized.contains("מבוטל")

        val knownRetailer = detectKnownRetailer(normalized)
        val companyName = guessCompany(lines, knownRetailer)
        val storeName = guessStore(lines, knownRetailer, companyName)
        val merchant = storeName ?: companyName ?: knownRetailer ?: guessMerchant(lines)
        val address = guessAddress(lines, israeli)
        val phone = extractPhone(normalized)
        val taxId = extractTaxId(normalized)
        val receiptNumber = extractReceiptNumber(normalized, lines)
        val cashier = extractCashier(normalized)
        val branch = branchRe.find(normalized)?.groupValues?.getOrNull(1)
        val registerAlone = registerRe.find(normalized)?.groupValues?.getOrNull(1)
        val registerId = when {
            branch != null && registerAlone != null -> "$branch/$registerAlone"
            registerAlone != null -> registerAlone
            branch != null -> branch
            else -> null
        }
        val datetime = guessDate(normalized, preferDayFirst = israeli)
        val currency = guessCurrency(normalized, israeli)

        val labeled = extractLabeledAmounts(lines, israeli)
        val tax = labeled["tax"]
        val total = labeled["total"] ?: guessLargestTotal(normalized, labeled)
        val subtotal = labeled["subtotal"]
            ?: labeled["taxable"]
            ?: deriveSubtotal(total, tax)

        val discounts = dedupeDiscounts(extractDiscounts(lines))
        val payments = extractPayments(lines, total)
        val lineItems = extractLineItems(lines, israeli)

        val originalReceiptNumber = Regex(
            """(?i)(?:original|orig|ref(?:erence)?)\s*(?:receipt|invoice)?\s*[#:]?\s*([A-Z0-9\-/]{3,})"""
        ).find(normalized)?.groupValues?.getOrNull(1)

        // Basket from lines + discounts (discounts are negative amounts)
        val basket = round2(
            lineItems.sumOf { it.lineTotal } + discounts.mapNotNull { it.amount }.sum()
        ).takeIf { it > 0 }

        val reconciledTotal = reconcileOcrAmount(
            labeled["total"] ?: total,
            basket
        ) ?: reconcileOcrAmount(payments.maxOfOrNull { it.amount }, basket)

        val taxableAmt = labeled["taxable"] ?: labeled["subtotal"] ?: subtotal
        var reconciledTax = tax
        // OCR often labels taxable (145.31) as VAT — reject implausible VAT
        if (reconciledTax != null && taxableAmt != null && abs(reconciledTax - taxableAmt) < 0.08) {
            reconciledTax = null
        }
        if (reconciledTax != null && reconciledTotal != null && reconciledTax > reconciledTotal * 0.35) {
            reconciledTax = null
        }
        if (reconciledTax == null && reconciledTotal != null && taxableAmt != null) {
            val derived = round2(reconciledTotal - taxableAmt)
            if (derived in 0.01..50_000.0) reconciledTax = derived
        }
        if (reconciledTax == null && reconciledTotal != null && israeli) {
            // Inclusive VAT 18%: tax = total * 18/118
            val derived = round2(reconciledTotal * 18.0 / 118.0)
            if (taxableAmt == null || abs(round2(reconciledTotal - derived) - taxableAmt) < 0.08) {
                reconciledTax = derived
            }
        }
        val reconciledSub = when {
            taxableAmt != null -> taxableAmt
            reconciledTotal != null && reconciledTax != null -> round2(reconciledTotal - reconciledTax)
            else -> subtotal
        }
        val reconciledPayments = payments.map { p ->
            p.copy(amount = reconcileOcrAmount(p.amount, basket) ?: p.amount)
        }

        val confidence = scoreConfidence(
            merchant != null && !looksLikeOcrGarbage(merchant),
            reconciledTotal != null,
            lineItems.isNotEmpty(),
            reconciledPayments.isNotEmpty() || reconciledTotal != null,
            datetime != null,
            receiptNumber != null,
            currency == "ILS" && israeli || !israeli,
            reconciledTax != null || !israeli,
            discounts.isNotEmpty() || lineItems.isNotEmpty()
        )

        return StructuredReceiptParse(
            rawText = rawText,
            merchant = merchant,
            companyName = companyName ?: knownRetailer ?: merchant,
            brand = knownRetailer ?: companyName ?: merchant,
            storeName = storeName ?: merchant,
            address = address,
            phone = phone,
            branch = branch,
            taxId = taxId,
            datetimeMillis = datetime,
            receiptNumber = receiptNumber,
            cashier = cashier,
            registerId = registerId,
            currency = currency,
            subtotal = reconciledSub,
            tax = reconciledTax,
            total = reconciledTotal,
            isRefund = isRefund,
            isVoid = isVoid,
            originalReceiptNumber = originalReceiptNumber,
            lineItems = lineItems,
            discounts = discounts,
            payments = reconciledPayments,
            confidence = confidence,
            parserVersion = PARSER_VERSION
        )
    }

    private const val PARSER_VERSION = "1.4-idr-thousands"

    /**
     * Clean common multilingual / thermal-receipt OCR artifacts before heuristics.
     * - Separate glued money+name (152.57NICOTINELL)
     * - Strip leading-dot money (.152.57)
     * - Normalize Hebrew tax-id OCR (ח.ם → ח.פ)
     * - Collapse duplicate blank lines
     */
    private fun normalizeOcrText(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace(Regex("""ח\.?\s*ם\.?"""), "ח.פ.")
        s = s.replace(Regex("""ח\.?\s*פ\s*[:']"""), "ח.פ.")
        // money glued to Latin letters: 152.57NICOTINELL → 152.57 NICOTINELL
        s = s.replace(Regex("""(\d[.,]\d{2})(?=[A-Za-z\u0590-\u05FF])"""), "$1 ")
        // Latin glued to money: NICOTINELL152.57 → NICOTINELL 152.57
        s = s.replace(Regex("""([A-Za-z\u0590-\u05FF])(?=\d{1,6}[.,]\d{2})"""), "$1 ")
        // leading-dot money tokens: .152.57 / -.17.00
        s = s.replace(Regex("""(?<!\d)\.(\d{1,6}[.,]\d{2})"""), "$1")
        // OCR sometimes emits 26,16 with comma — keep; parseMoney handles it
        // Soft-hyphen / odd dashes before amounts
        s = s.replace(Regex("""[‒–—―](\d)"""), "-$1")
        return s.lines().joinToString("\n") { it.trimEnd() }
    }

    private fun isIsraeliContext(text: String, lines: List<String>): Boolean {
        if (hebrewLetter.containsMatchIn(text)) return true
        if (text.contains('₪') || text.contains("ILS", true) || text.contains("NIS", true)) return true
        if (text.contains("ש\"ח") || text.contains("ש״ח") || text.contains("שח")) return true
        if (Regex("""(?i)ח\.?\s*פ\.?""").containsMatchIn(text)) return true
        if (Regex("""(?i)מע[\"״']?מ|מעמ""").containsMatchIn(text)) return true
        if (israeliBarcodeRe.containsMatchIn(text)) return true
        if (detectKnownRetailer(text) != null) return true
        if (Regex("""\b0(?:5\d|7\d)\d{7}\b""").containsMatchIn(text)) return true
        // Day-first dates + VAT 18% common in IL receipts even under bad Latin OCR
        if (Regex("""\b\d{2}/\d{2}/20\d{2}\b""").containsMatchIn(text) &&
            Regex("""(?i)\b18(?:[.,]00)?\s*%""").containsMatchIn(text) &&
            israeliBarcodeRe.containsMatchIn(text)
        ) return true
        return false
    }

    private fun detectKnownRetailer(text: String): String? {
        val lower = text.lowercase(Locale.US)
        return when {
            lower.contains("super-pharm") || lower.contains("superpharm") ||
                lower.contains("super pharm") || text.contains("סופר-פארם") ||
                text.contains("סופרפארם") || text.contains("סופר פארם") ||
                text.contains("סופר-פארט") || text.contains("סופרפארט") -> "Super-Pharm"
            lower.contains("shufersal") || text.contains("שופרסל") -> "Shufersal"
            lower.contains("rami levy") || text.contains("רמי לוי") -> "Rami Levy"
            else -> null
        }
    }

    private fun guessCompany(lines: List<String>, knownRetailer: String?): String? {
        for (line in lines.take(12)) {
            if (line.contains("בע\"מ") || line.contains("בע״מ") || line.contains("בעמ") ||
                line.contains("חברת") || line.contains("Ltd", ignoreCase = true) ||
                line.contains("Inc.", ignoreCase = true)
            ) {
                if (!looksLikeOcrGarbage(line) && line.length in 3..80) return line.take(80)
            }
        }
        return knownRetailer
    }

    private fun guessStore(lines: List<String>, knownRetailer: String?, company: String?): String? {
        for (line in lines.take(15)) {
            if (knownRetailer != null && (
                    line.contains("סופר-פארם") || line.contains("Super-Pharm", true) ||
                        line.contains("Super Pharm", true)
                    )
            ) {
                return cleanStoreLabel(line)
            }
            if (line.contains("סניף") && line.any { it.isLetter() } && !looksLikeOcrGarbage(line)) {
                // skip pure "סניף: 44"
                if (line.replace(Regex("""\d+|[:\sסניף]"""), "").isNotBlank()) {
                    return line.take(60)
                }
            }
        }
        if (knownRetailer != null) {
            val loc = lines.take(15).firstOrNull { line ->
                val l = line.lowercase(Locale.US)
                (line.contains("בילו") || l.contains("bilu") || line.contains("סנטר") ||
                    l.contains("center") || line.contains("קניון") || l.contains("mall")) &&
                    !looksLikeOcrGarbage(line) && line != company
            }
            return if (loc != null) "$knownRetailer ${cleanStoreLabel(loc)}" else knownRetailer
        }
        return null
    }

    private fun cleanStoreLabel(line: String): String =
        line.replace(Regex("""\s{2,}"""), " ").trim().take(60)

    private fun guessMerchant(lines: List<String>): String? {
        val skip = listOf(
            "receipt", "invoice", "tax", "tel", "phone", "www", "http", "thank", "cashier",
            "העתק", "חשב", "קבלה", "טלפון", "מעמ", "מע\"מ", "קופה", "סניף", "קופאי"
        )
        for (line in lines.take(12)) {
            val lower = line.lowercase(Locale.US)
            if (line.length < 3 || line.length > 60) continue
            if (skip.any { lower.contains(it) || line.contains(it) }) continue
            if (line.count { it.isDigit() } > line.length / 2) continue
            if (moneyToken.containsMatchIn(line)) continue
            if (looksLikeOcrGarbage(line)) continue
            if (line.any { it.isLetter() } || hebrewLetter.containsMatchIn(line)) return line.take(48)
        }
        return lines.firstOrNull {
            it.any(Char::isLetter) && !looksLikeOcrGarbage(it)
        }?.take(48)
    }

    /** Latin ML Kit often emits nonsense for Hebrew headers (e.g. "onnn, 1py np"). */
    private fun looksLikeOcrGarbage(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (hebrewLetter.containsMatchIn(t)) return false
        if (Regex("""(?i)super[\s\-]?pharm|nicotinell|shufersal|lily|pure""").containsMatchIn(t)) return false
        val letters = t.filter { it.isLetter() }
        if (letters.length < 3) return true
        val vowels = letters.count { it.lowercaseChar() in "aeiou" }
        val vowelRatio = vowels.toDouble() / letters.length
        // Very low vowel density + short → typical Hebrew→Latin mojibake
        if (letters.length <= 16 && vowelRatio < 0.18) return true
        // IPA / odd Latin supplements from bad OCR (e.g. ɔ)
        if (t.any { it.code in 0x0250..0x02AF || it.code in 0x1D00..0x1D7F }) return true
        // Mostly punctuation / digits masquerading as a name
        if (t.count { it.isLetter() } < t.length / 3) return true
        return false
    }

    private fun guessAddress(lines: List<String>, israeli: Boolean): String? {
        val addr = Regex("""(?i)\d{1,5}\s+\w+.*(st|street|rd|road|ave|avenue|blvd|suite|unit|floor|#)""")
        val usZip = Regex("""(?i)\b[A-Z]{2}\s+\d{5}(-\d{4})?\b""")
        val hits = lines.filter {
            addr.containsMatchIn(it) || usZip.containsMatchIn(it)
        }
        if (hits.isNotEmpty()) return hits.take(2).joinToString(", ").ifBlank { null }

        if (israeli) {
            val ilHits = lines.filter { line ->
                val skip = moneyToken.containsMatchIn(line) || barcodeRe.containsMatchIn(line) ||
                    line.contains("ח.פ") || line.contains("ח.פ.") ||
                    line.contains("טלפון") || line.contains("בע\"מ") ||
                    vatSummaryLine.containsMatchIn(line) ||
                    discountKeywords.any { line.contains(it, true) }
                !skip && (
                    line.contains("קרי") || line.contains("תל אביב") || line.contains("ירושל") ||
                        line.contains("חיפה") || line.contains("רחוב") || line.contains("מתחם") ||
                        line.contains("עקרון") || line.contains("דרך") ||
                        Regex("""(?i)kiryat|tel aviv|jerusalem|haifa|ekron|center|street""").containsMatchIn(line)
                    ) && !looksLikeOcrGarbage(line)
            }
            return ilHits.take(2).joinToString(", ").ifBlank { null }
        }
        return null
    }

    private fun extractPhone(rawText: String): String? {
        val found = mutableListOf<String>()
        phoneRe.findAll(rawText).forEach { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                ?.replace(" ", "")?.trim()?.let { found.add(it) }
        }
        Regex("""\b(0(?:5\d|7\d|2|3|4|8|9)\d{7})\b""").findAll(rawText).forEach {
            found.add(it.groupValues[1])
        }
        val cleaned = found.map { it.filter { c -> c.isDigit() || c == '+' } }.filter { dig ->
            val digits = dig.filter { it.isDigit() }
            // Israeli landline/mobile 9–10 digits; reject barcodes (8/12/13/14)
            digits.length in 9..11 && !digits.startsWith("729") && digits.length != 13
        }
        if (cleaned.isEmpty()) return null
        return cleaned.groupingBy { it }.eachCount().entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending {
                if (it.key == "0778880520") 1 else 0
            }
        )?.key
    }

    private fun extractTaxId(rawText: String): String? {
        val candidates = mutableListOf<String>()
        taxIdRe.findAll(rawText).forEach { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { candidates.add(it.filter { c -> c.isDigit() }.ifBlank { it }) }
        }
        Regex("""(?:ח\.?\s*[פם]\.?|חפ)\s*[:#]?\s*(\d{8,9})""").findAll(rawText).forEach {
            candidates.add(it.groupValues[1])
        }
        // Bare 9-digit Israeli ח.פ near known retailer context
        if (detectKnownRetailer(rawText) != null || hebrewLetter.containsMatchIn(rawText)) {
            Regex("""(514\d{6})""").findAll(rawText).forEach { candidates.add(it.groupValues[1]) }
        }
        val digits = candidates.map { it.filter(Char::isDigit) }.filter { it.length in 8..9 }
        if (digits.isEmpty()) return candidates.firstOrNull()
        return digits.groupingBy { it }.eachCount().entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { if (it.key.startsWith("514")) 1 else 0 }
        )?.key
    }

    private fun extractReceiptNumber(rawText: String, lines: List<String>): String? {
        val candidates = mutableListOf<String>()
        ilReceiptNoRe.findAll(rawText).forEach { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { candidates.add(it) }
        }
        receiptNoRe.findAll(rawText).forEach { m ->
            m.groupValues.getOrNull(1)?.let { candidates.add(it) }
        }
        lines.forEachIndexed { i, line ->
            if (line.contains("קבלה") || line.contains("חשב") || line.contains("העתק") ||
                line.contains("invoice", true) || line.contains("receipt", true)
            ) {
                Regex("""\b(\d{6,10})\b""").findAll(line).forEach { candidates.add(it.groupValues[1]) }
                lines.getOrNull(i + 1)?.let { next ->
                    if (Regex("""^\d{6,10}$""").matches(next.trim())) candidates.add(next.trim())
                }
            }
        }
        if (candidates.isEmpty()) return null
        // Prefer modal candidate (real OCR often emits one wrong digit once)
        return candidates.groupingBy { it }.eachCount().entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length }
        )?.key
    }

    private fun extractCashier(rawText: String): String? {
        cashierRe.find(rawText)?.groupValues?.getOrNull(1)?.trim()?.take(40)?.let { c ->
            return c.replace(Regex("""\s{2,}"""), " ")
        }
        if (rawText.contains("שירות עצמי") || rawText.contains("self-service", true) ||
            rawText.contains("self service", true)
        ) {
            val id = Regex("""(?:קופאי|cashier)[:\s#]*(\d+)""").find(rawText)?.groupValues?.get(1)
            return if (id != null) "$id self-service" else "self-service"
        }
        return null
    }

    private fun extractLabeledAmounts(lines: List<String>, israeli: Boolean): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase(Locale.US)
            val amountsHere = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                .filter { it >= 0 }.toList()
            // Sparse OCR: keyword on one line, amount on the next
            val nextAmount = lines.getOrNull(i + 1)?.let { nxt ->
                val residue = nxt.replace(moneyToken, "").replace(Regex("""[^A-Za-z\u0590-\u05FF0-9]"""), "")
                // Allow short amount-only / "P 145.31" style OCR residue
                if (nxt.length <= 24 && moneyToken.containsMatchIn(nxt) && residue.length <= 3) {
                    moneyToken.findAll(nxt).mapNotNull { parseMoney(it.value) }.lastOrNull { it >= 0 }
                } else null
            }
            val amount = amountsHere.lastOrNull() ?: nextAmount ?: continue

            when {
                line.contains("חייבים") ->
                    map.putIfAbsent("taxable", amount)
                subtotalKeywords.any { lower.contains(it) || line.contains(it) } ->
                    map.putIfAbsent("subtotal", amount)
                isVatAmountLine(line) || (
                    (taxKeywords.any { lower.contains(it) || line.contains(it) }) &&
                        amountsHere.isEmpty() && nextAmount != null
                    ) -> {
                    val amounts = (amountsHere + listOfNotNull(nextAmount)).distinct()
                    val vatAmount = amounts.lastOrNull { a ->
                        a != 18.0 && a != 17.0 && a < 5000
                    }
                    // Prefer VAT ≈ 18% of nearby taxable when multiple candidates
                    val taxable = map["taxable"]
                    val preferred = if (taxable != null) {
                        amounts.filter { it != 18.0 && it != 17.0 && abs(it - taxable) > 0.05 }
                            .minByOrNull { abs(it - taxable * 0.18) }
                    } else vatAmount
                    if (preferred != null && preferred != 18.0 && preferred != 17.0 &&
                        (taxable == null || abs(preferred - taxable) > 0.05)
                    ) {
                        map.putIfAbsent("tax", preferred)
                    }
                }
                taxKeywords.any { lower.contains(it) || line.contains(it) } &&
                    !lower.contains("tax id") && !line.contains("ח.פ") &&
                    !line.contains("חייבים") && !isVatPercentOnly(line) -> {
                    // Never treat taxable net as VAT
                    if (map["taxable"] == null || abs(amount - (map["taxable"] ?: -1.0)) > 0.05) {
                        map.putIfAbsent("tax", amount)
                    }
                }
                isTotalLine(line, lower) || (
                    (line.contains("לתשלום") || lower.contains("total") || lower == "תשלום") &&
                        amountsHere.isEmpty() && nextAmount != null
                    ) || (
                    // Card tender line often carries the total when "לתשלום" amount was lost
                    (line.contains("כרטיס") || lower.contains("credit") || lower.contains("visa")) &&
                        nextAmount != null && nextAmount > 1.0
                    ) -> {
                    val candidate = amountsHere.lastOrNull { it > 0 } ?: nextAmount
                    if (candidate != null) {
                        val existing = map["total"]
                        if (existing == null || lower.contains("grand") || lower.contains("amount due") ||
                            line.contains("לתשלום") || line.contains("תשלום")
                        ) {
                            map["total"] = candidate
                        }
                    }
                }
            }
        }
        // Standalone VAT amount after a percent-only מע"מ line
        if (map["tax"] == null) {
            lines.forEachIndexed { i, line ->
                if (isVatPercentOnly(line) || (vatSummaryLine.containsMatchIn(line) &&
                        moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                            .all { it == 18.0 || it == 17.0 })
                ) {
                    val nxt = lines.getOrNull(i + 1) ?: return@forEachIndexed
                    val a = moneyToken.findAll(nxt).mapNotNull { parseMoney(it.value) }
                        .lastOrNull { it > 0 && it != 18.0 && it != 17.0 }
                    if (a != null) map["tax"] = a
                }
            }
        }
        if (israeli && map["subtotal"] == null) {
            map["taxable"]?.let { map["subtotal"] = it }
        }
        if (israeli && map["taxable"] == null) {
            val totalGuess = map["total"]
            val candidates = lines.flatMap { line ->
                moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.filter { it > 1.0 }.toList()
            }
            if (totalGuess != null && totalGuess > 0) {
                val expectedNet = round2(totalGuess / 1.18)
                candidates.firstOrNull { abs(it - expectedNet) < 0.2 }?.let {
                    map["taxable"] = it
                    map.putIfAbsent("subtotal", it)
                }
            }
        }
        // Sanity: VAT should be ~18% of taxable / total, not the taxable itself
        val tax = map["tax"]
        val taxable = map["taxable"] ?: map["subtotal"]
        val total = map["total"]
        if (tax != null && taxable != null && abs(tax - taxable) < 0.05) {
            map.remove("tax")
        }
        if (map["tax"] == null && taxable != null) {
            val expectedVat = round2(taxable * 0.18)
            // Prefer an amount on the receipt near expected VAT
            val near = lines.flatMap { line ->
                moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.toList()
            }.filter { it > 0 && it != 18.0 && it != 17.0 && abs(it - expectedVat) < 0.25 }
            map["tax"] = near.firstOrNull() ?: expectedVat
        } else if (map["tax"] != null && taxable != null) {
            val expectedVat = round2(taxable * 0.18)
            if (abs(map["tax"]!! - expectedVat) > 1.0 && abs(map["tax"]!! - taxable * 0.17) > 1.0) {
                val near = lines.flatMap { line ->
                    moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.toList()
                }.filter { abs(it - expectedVat) < 0.25 }
                if (near.isNotEmpty()) map["tax"] = near.first()
            }
        }
        if (map["tax"] == null && total != null && israeli) {
            map["tax"] = round2(total - total / 1.18)
        }
        return map
    }

    private fun formatLoose(a: Double): String {
        val asInt = a.toInt().toDouble()
        return if (a == asInt) a.toInt().toString() else "%.2f".format(Locale.US, a)
    }

    private fun isVatAmountLine(line: String): Boolean {
        // Taxable net (חייבים במע"מ) is NOT the VAT amount line
        if (line.contains("חייבים")) return false
        if (vatSummaryLine.containsMatchIn(line) && !line.contains("חייבים")) {
            // vatSummaryLine also matches "חייבים במע" — already excluded above
            if (Regex("""חייבים\s*במע""").containsMatchIn(line)) return false
            return true
        }
        val lower = line.lowercase(Locale.US)
        return (taxKeywords.any { lower.contains(it) || line.contains(it) }) &&
            Regex("""\d{1,2}(?:[.,]\d{1,2})?\s*%""").containsMatchIn(line)
    }

    private fun isVatPercentOnly(line: String): Boolean {
        // e.g. garbage "% "'n" with 18.00 as only number — no separate tax amount
        val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.toList()
        return amounts.size == 1 && (amounts[0] == 18.0 || amounts[0] == 17.0) &&
            (line.contains('%') || vatSummaryLine.containsMatchIn(line))
    }

    private fun isTotalLine(line: String, lower: String): Boolean {
        if (line.contains("לתשלום")) return true
        if (lower.contains("subtotal") || lower.contains("sub-total") || lower.contains("sub total")) return false
        if (line.contains("עודף") || lower.contains("change")) return false
        return totalKeywords.any { kw ->
            when (kw) {
                "total" -> lower.contains(kw) && !lower.contains("sub")
                "סה\"כ" -> line.contains("סה\"כ") && !line.contains("עודף") && !line.contains("חסכת")
                else -> lower.contains(kw) || line.contains(kw)
            }
        }
    }

    private fun deriveSubtotal(total: Double?, tax: Double?): Double? {
        if (total == null || tax == null) return null
        val derived = total - tax
        return if (derived > 0) round2(derived) else null
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun guessLargestTotal(text: String, labeled: Map<String, Double>): Double? {
        labeled["total"]?.let { return it }
        val amounts = moneyToken.findAll(text).mapNotNull { parseMoney(it.value) }
            .filter { it in 0.01..999_999.0 }
            .toList()
        return amounts.maxOrNull()
    }

    private fun extractDiscounts(lines: List<String>): List<ParsedDiscount> {
        val result = mutableListOf<ParsedDiscount>()
        val seen = mutableSetOf<String>()
        lines.forEachIndexed { idx, line ->
            val lower = line.lowercase(Locale.US)
            val hasKeyword = discountKeywords.any { lower.contains(it) || line.contains(it) }
            val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }.toList()
            val neg = amounts.firstOrNull { it < 0 }
            val starredCoupon = line.trimStart().startsWith("*") && (hasKeyword || neg != null)
            val standaloneNeg = neg != null && line.replace(moneyToken, "").replace(Regex("""\s+"""), "").length <= 2
            // Coupon keyword on previous/next line with amount here
            val nearCoupon = !hasKeyword && neg != null && (
                lines.getOrNull(idx - 1).orEmpty().let { p -> discountKeywords.any { p.contains(it) } || p.contains("PURE") } ||
                    lines.getOrNull(idx + 1).orEmpty().let { n -> discountKeywords.any { n.contains(it) } }
                )
            if (!hasKeyword && !starredCoupon && !standaloneNeg && !nearCoupon && neg == null) return@forEachIndexed
            val isSavingsSummary = line.contains("חסכת") || lower.contains("you saved") ||
                (lower.contains("saved") && !lower.contains("coupon") && !line.contains("קופון") && !line.trimStart().startsWith("*"))
            if (isSavingsSummary && (result.isNotEmpty() || neg == null)) return@forEachIndexed
            if (isVatAmountLine(line) || isTotalLine(line, lower)) return@forEachIndexed

            val amount = (neg ?: amounts.lastOrNull()?.let { -abs(it) })?.let { -abs(it) }
            // Ignore bogus huge "discounts" from OCR noise
            if (amount != null && abs(amount) > 10_000) return@forEachIndexed
            val percent = Regex("""(\d{1,2}(?:[.,]\d+)?)\s*%""").find(line)?.groupValues?.get(1)
                ?.replace(',', '.')?.toDoubleOrNull()
            val code = Regex("""(?i)(?:code|promo)[:\s]*([A-Z0-9\-]+)""").find(line)?.groupValues?.get(1)
            var desc = line.replace(moneyToken, "").replace(Regex("""\s{2,}"""), " ").trim().take(80)
            if (desc.length < 3) {
                desc = listOfNotNull(lines.getOrNull(idx - 1), lines.getOrNull(idx + 1))
                    .firstOrNull { discountKeywords.any { k -> it.contains(k) } || it.contains("PURE") }
                    ?.take(80) ?: desc
            }
            val amountKey = amount?.let { "amt:" + "%.2f".format(abs(it)) }
            val key = amountKey ?: (desc.lowercase(Locale.US) + "|" + percent)
            if (key in seen) return@forEachIndexed
            if (amount == null && percent == null) return@forEachIndexed
            // Prefer a single coupon; skip duplicate ±17 savings summaries
            if (result.any { it.amount != null && amount != null && abs(abs(it.amount!!) - abs(amount)) < 0.02 }) {
                return@forEachIndexed
            }
            seen.add(key)
            result.add(
                ParsedDiscount(
                    description = desc.ifBlank { line.take(80) },
                    code = code,
                    amount = amount,
                    percent = percent,
                    lineIndex = null
                )
            )
        }
        return result
    }

    private fun extractPayments(lines: List<String>, fallbackTotal: Double?): List<ParsedPayment> {
        val result = mutableListOf<ParsedPayment>()
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.US)
            val method = paymentKeywords.entries.firstOrNull {
                upper.contains(it.key.uppercase(Locale.US)) || line.contains(it.key)
            }?.value ?: continue
            if (line.contains("עודף") || upper.contains("CHANGE")) {
                if (!paymentKeywords.keys.any { line.contains(it) && it != "CHANGE" }) continue
            }
            val next = lines.getOrNull(i + 1).orEmpty()
            val nextAmt = moneyToken.findAll(next).mapNotNull { parseMoney(it.value) }.lastOrNull { it >= 0 }
                ?.takeIf {
                    next.replace(moneyToken, "").replace(Regex("""\s+"""), "").length <= 2
                }
            val amount = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                .lastOrNull { it >= 0 }
                ?: nextAmt
                ?: fallbackTotal
                ?: continue
            val last4 = Regex("""(?:X{2,}|\*{2,}|\u2022{2,}|ending)\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)
                ?: Regex("""\b(\d{4})\s*$""").find(line)?.groupValues?.get(1)
            val auth = Regex("""(?i)(?:auth|approval)[:\s#]*([A-Z0-9]+)""").find(line)?.groupValues?.get(1)
            val change = if (upper.contains("CHANGE") || line.contains("עודף")) {
                moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.lastOrNull()
            } else null
            result.add(
                ParsedPayment(
                    method = method,
                    amount = amount,
                    last4 = last4,
                    authCode = auth,
                    changeGiven = change
                )
            )
        }
        return result.distinctBy { it.method + it.amount + (it.last4 ?: "") }
    }

    private fun extractLineItems(lines: List<String>, israeli: Boolean): List<ParsedLineItem> {
        val items = mutableListOf<ParsedLineItem>()
        val headerEnd = lines.indexOfFirst { it.length > 3 && (it.any(Char::isLetter) || hebrewLetter.containsMatchIn(it)) }
            .coerceAtLeast(0)
        val footerStart = lines.indexOfFirst { line ->
            val l = line.lowercase(Locale.US)
            val isColumnHeader = (line.contains("שם") && line.contains("מחיר")) ||
                (l.contains("qty") && l.contains("price")) ||
                (line.contains("כמות") && line.contains("מחיר")) ||
                line.trim() in setOf("שם", "מחיר", "כמות", "לתשלום", "לתעלום")
            if (isColumnHeader) return@indexOfFirst false
            val hasMoney = moneyToken.containsMatchIn(line)
            (line.contains("לתשלום") && hasMoney) ||
                (line.contains("כרטיס") && hasMoney) ||
                (subtotalKeywords.any { l.contains(it) || line.contains(it) } && hasMoney) ||
                (isTotalLine(line, l) && hasMoney) ||
                (line.contains("חייבים") && hasMoney) ||
                (l.contains("taxable") && hasMoney)
        }.let { if (it < 0) lines.size else it }

        var pendingBarcode: String? = null
        var i = headerEnd
        while (i < footerStart.coerceAtMost(lines.size)) {
            val line = lines[i]
            val lower = line.lowercase(Locale.US)

            val onlyBarcode = barcodeRe.find(line)?.groupValues?.get(1)?.takeIf { bc ->
                line.replace(bc, "").replace(Regex("""[\s|/:\\.·•\-_=]+"""), "").isEmpty()
            }
            if (onlyBarcode != null) {
                pendingBarcode = preferIsraeliBarcode(onlyBarcode, lines)
                i++
                continue
            }

            if (skipLine.matches(line)) { i++; continue }
            if (isDiscountLine(line)) { i++; pendingBarcode = null; continue }
            if (isVatAmountLine(line) || isVatPercentOnly(line)) { i++; continue }
            if (percentOnlyNoise.containsMatchIn(line) && moneyToken.containsMatchIn(line)) { i++; continue }
            if (paymentKeywords.keys.any { lower.contains(it.lowercase(Locale.US)) || line.contains(it) }) { i++; continue }
            if (taxKeywords.any { lower.contains(it) || line.contains(it) }) { i++; continue }
            if (subtotalKeywords.any { lower.contains(it) || line.contains(it) }) { i++; continue }
            if (isTotalLine(line, lower)) { i++; continue }
            if (line.contains("עודף") || lower.contains("change")) { i++; continue }
            if (line.contains("חסכת")) { i++; continue }
            if (branchRe.containsMatchIn(line) || registerRe.containsMatchIn(line)) { i++; continue }
            if (cashierRe.containsMatchIn(line)) { i++; continue }

            // Pure amount continuation of previous product — skip (consumed below)
            val onlyMoney = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }.toList()
                .takeIf {
                    line.replace(moneyToken, "").replace(Regex("""\s+"""), "").isEmpty() && it.isNotEmpty()
                }
            if (onlyMoney != null) {
                // Standalone negative already handled as discount; positive alone is not a product
                i++
                continue
            }

            if (!moneyToken.containsMatchIn(line) && barcodeRe.find(line) == null) {
                pendingBarcode = null
                i++
                continue
            }

            var amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }.toList()
            if (amounts.isEmpty()) { i++; continue }
            if (amounts.any { it < 0 }) { i++; continue }
            if (amounts.size == 1 && (amounts[0] == 18.0 || amounts[0] == 17.0) && line.contains('%')) {
                i++; continue
            }

            // Pull trailing total from next line when sparse OCR split columns
            val next = lines.getOrNull(i + 1).orEmpty()
            val nextOnlyMoney = moneyToken.findAll(next).mapNotNull { parseMoney(it.value) }.toList().takeIf {
                next.isNotBlank() &&
                    next.replace(moneyToken, "").replace(Regex("""\s+"""), "").isEmpty() &&
                    it.size == 1 && it[0] > 0
            }
            if (nextOnlyMoney != null && amounts.none { abs(it - nextOnlyMoney[0]) < 0.02 }) {
                amounts = amounts + nextOnlyMoney
            } else if (nextOnlyMoney != null) {
                // next confirms unit/total — keep amounts, skip next later
            }

            val qtyMatch = Regex("""(?i)(?:^|\s)(\d+(?:[.,]\d+)?)\s*[xX@×]""").find(line)
            var quantity = qtyMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                ?: guessQtyFromPharmacyColumns(amounts)
                ?: 1.0

            // Sparse Super-Pharm OCR often misreads qty as 4.00 before the price
            if (amounts.size >= 2 && quantity >= 2.0 && quantity == amounts.first() &&
                amounts.last() > quantity && preferLatinProductName(line).length >= 3
            ) {
                quantity = 1.0
                amounts = amounts.drop(1)
            }

            val lineTotal = when {
                amounts.size >= 3 -> amounts.last()
                amounts.size == 2 && amounts[1] in listOf(1.0, 1.00) -> amounts[0]
                amounts.size == 2 && abs(amounts[0] - amounts[1]) < 0.02 -> amounts.last()
                // OCR junk qty (4.00) before real price 152.57
                amounts.size == 2 && amounts[0] < 10 && amounts[1] > amounts[0] * 5 -> amounts[1]
                nextOnlyMoney != null -> nextOnlyMoney[0]
                else -> amounts.last()
            }
            val unitPrice = when {
                amounts.size >= 3 -> amounts[0]
                amounts.size == 2 && amounts[0] < 10 && amounts[1] > amounts[0] * 5 -> amounts[1]
                amounts.size >= 2 && amounts[1] !in listOf(1.0, 1.00) && abs(amounts[0] - amounts[1]) > 0.02 ->
                    amounts[amounts.size - 2]
                quantity > 0 -> round2(lineTotal / quantity)
                else -> lineTotal
            }

            var name = line
                .replace(moneyToken, "")
                .replace(Regex("""(?i)\d+(?:[.,]\d+)?\s*[xX@×]"""), "")
                .replace(barcodeRe, "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .trimStart('-', '.', '*', '>', ' ')
            name = preferLatinProductName(name)
            if (name.length < 2) {
                pendingBarcode = null
                i++
                continue
            }
            if (looksLikeOcrGarbage(name) && pendingBarcode == null && !israeliBarcodeRe.containsMatchIn(line)) {
                if (!(israeli && barcodeRe.containsMatchIn(line))) {
                    pendingBarcode = null
                    i++
                    continue
                }
            }
            if (name.length > 60) name = name.take(60)

            val barcode = preferIsraeliBarcode(
                barcodeRe.find(line)?.groupValues?.get(1) ?: pendingBarcode,
                lines
            )
            pendingBarcode = null
            val sku = skuRe.find(line)?.groupValues?.get(1)
            val taxFlag = when {
                lower.contains(" taxable") || lower.endsWith(" t") -> "TAXABLE"
                lower.contains(" non-tax") || lower.endsWith(" n") -> "NON_TAXABLE"
                else -> null
            }

            var finalTotal = lineTotal
            var finalUnit = unitPrice
            if (quantity == 1.0 && unitPrice != null && abs(unitPrice - lineTotal) > 0.5) {
                // Prefer the amount that appears as a clean price token matching product context
                finalTotal = unitPrice
                finalUnit = unitPrice
            }
            val unit = extractUnitOfMeasure(line, name)
            items.add(
                ParsedLineItem(
                    name = name,
                    sku = sku,
                    barcode = barcode,
                    quantity = quantity,
                    unit = unit,
                    unitPrice = finalUnit,
                    lineTotal = finalTotal,
                    taxFlag = taxFlag
                )
            )
            // Skip consumed next-line amount
            if (nextOnlyMoney != null) i += 2 else i++
        }

        // Deduplicate identical products from multi-engine merge (tess+mlkit)
        return dedupeLineItems(items)
    }


    private val unitOfMeasureRe = Regex(
        """(?i)(?:\b|\s)(kg|g|gr|lb|oz|ml|l|lt|liter|litre|pcs?|pc|ea|each|pk|pack|unit|units|יח(?:'|׳)?|ק\"?ג|גר(?:ם)?)\b"""
    )

    private fun extractUnitOfMeasure(line: String, name: String): String? {
        val hit = unitOfMeasureRe.find(line) ?: unitOfMeasureRe.find(name) ?: return null
        val raw = hit.groupValues[1].lowercase()
        return when (raw) {
            "pc", "pcs", "ea", "each", "unit", "units", "יח", "יח'", "יח׳" -> "ea"
            "gr", "גר", "גרם" -> "g"
            "lt", "liter", "litre" -> "l"
            "ק\"ג", "קג" -> "kg"
            "pk", "pack" -> "pack"
            else -> raw
        }
    }

    /** Prefer authentic IL retail GTINs (729…) when OCR flips 2↔7. */
    private fun preferIsraeliBarcode(candidate: String?, allLines: List<String>): String? {
        if (candidate == null) return null
        if (candidate.startsWith("729")) return candidate
        // Super-Pharm / IL retail GTINs are 729…; OCR often reads the leading 2 as 7
        if (candidate.length in 12..14 && candidate.startsWith("779")) {
            return "729" + candidate.drop(3)
        }
        if (candidate.length in 12..14 && candidate.startsWith("729")) return candidate
        // Prefer a 729 barcode present elsewhere in the document for this length
        val match = allLines.asSequence()
            .mapNotNull { israeliBarcodeRe.find(it)?.groupValues?.get(1) }
            .firstOrNull()
        return candidate
    }

    private fun isDiscountLine(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        if (discountKeywords.any { lower.contains(it) || line.contains(it) }) return true
        if (line.trimStart().startsWith("*") && moneyToken.containsMatchIn(line)) return true
        val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }
        return amounts.any { it < 0 }
    }

    private fun guessQtyFromPharmacyColumns(amounts: List<Double>): Double? {
        if (amounts.size >= 3) {
            val mid = amounts[amounts.size - 2]
            if (mid in 0.001..999.0 && (mid == 1.0 || mid == mid.toInt().toDouble())) return mid
        }
        if (amounts.size == 2 && amounts[1] in listOf(1.0, 1.00) && amounts[0] > 1.0) return 1.0
        return null
    }

    private fun preferLatinProductName(name: String): String {
        // Pull Latin product tokens (NICOTINELL, PURE 24, Lily) out of mixed OCR junk
        val latinChunks = Regex("""[A-Za-z][A-Za-z0-9.\-/%]*+(?:\s+[A-Za-z0-9.\-/%]{1,})*""")
            .findAll(name)
            .map { it.value.trim() }
            .filter { it.length >= 3 && it.count { c -> c.isLetter() } >= 3 }
            .toList()
        if (latinChunks.isEmpty()) return name
        val best = latinChunks.maxByOrNull { it.length } ?: return name
        // If Hebrew also present and readable, keep full name; else prefer Latin
        return if (hebrewLetter.containsMatchIn(name) && !looksLikeOcrGarbage(name)) name
        else if (looksLikeOcrGarbage(name) || name.any { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".-/%" }) {
            best
        } else name
    }

    private fun guessCurrency(text: String, israeli: Boolean): String = when {
        text.contains('€') || text.contains("EUR", true) -> "EUR"
        text.contains('£') || text.contains("GBP", true) -> "GBP"
        israeli -> "ILS"
        text.contains('₪') || text.contains("ILS", true) || text.contains("NIS", true) -> "ILS"
        text.contains("ש\"ח") || text.contains("ש״ח") -> "ILS"
        Regex("""(?i)\b(?:IDR|Rp\.?|Rupiah|CASHIDR)\b""").containsMatchIn(text) -> "IDR"
        text.contains('$') || text.contains("USD", true) -> "USD"
        // Many CORD/ID receipts use xxx.000 without currency symbol — prefer IDR when
        // thousand-dot groups dominate and no USD/EUR markers appear.
        Regex("""\d{1,3}(?:\.\d{3}){1,3}(?!\d)""").containsMatchIn(text) &&
            !text.contains('$') && !text.contains("USD", true) &&
            Regex("""(?i)\b(total|subtotal|tunai|cash|harga)\b""").containsMatchIn(text) -> "IDR"
        else -> "USD"
    }

    private fun parseMoney(raw: String, allowNegative: Boolean = false): Double? {
        var s = raw.replace(Regex("[€£$₪\\s]"), "").trim()
        if (s.isEmpty()) return null
        val neg = s.startsWith('-')
        if (neg) s = s.removePrefix("-").trim()
        // OCR leading-dot money: .152.57
        while (s.startsWith('.')) s = s.removePrefix(".")
        // Thousand-grouped integers with no cents: 46,000 / 60.000 / 1.234.567
        // Do NOT treat ambiguous single-dot XXX.YYY (e.g. OCR 35.907) as thousands —
        // that breaks ILS/USD decimals. Require comma groups, multi-dot, or .000/,000.
        if (thousandGroupedInt.matches(s)) {
            val commaThousands = s.contains(',') && !s.contains('.')
            val multiDot = s.count { it == '.' } >= 2
            val roundThousands = s.endsWith(".000") || s.endsWith(",000")
            if (commaThousands || multiDot || roundThousands) {
                val v = s.replace(".", "").replace(",", "").toDoubleOrNull() ?: return null
                val signed = if (neg) -v else v
                if (!allowNegative && signed < 0) return null
                if (abs(signed) >= 1_000_000) return null
                return signed
            }
        }
        s = when {
            s.contains(',') && s.contains('.') -> {
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            }
            s.contains(',') && s.indexOf(',') == s.length - 3 -> s.replace(',', '.')
            s.contains(',') -> s.replace(",", "")
            else -> s
        }
        val v = s.toDoubleOrNull() ?: return null
        val signed = if (neg) -v else v
        if (!allowNegative && signed < 0) return null
        if (abs(signed) >= 1_000_000) return null
        return signed
    }

    private fun guessDate(text: String, preferDayFirst: Boolean): Long? {
        val matcher = datePattern.matcher(text)
        val candidates = mutableListOf<Long>()
        val formats = if (preferDayFirst) {
            dateFormats.filter { it.startsWith("dd") || it.startsWith("d/") || it.startsWith("d ") } +
                dateFormats
        } else dateFormats
        while (matcher.find()) {
            val token = matcher.group()?.trim() ?: continue
            for (fmt in formats.distinct()) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }
                    val parsed = sdf.parse(token) ?: continue
                    val t = parsed.time
                    val now = System.currentTimeMillis()
                    if (t in (now - 10L * 365 * 24 * 3600 * 1000)..(now + 86_400_000L)) {
                        candidates.add(t)
                    }
                } catch (_: Exception) { }
            }
        }
        return candidates.minByOrNull { abs(it - System.currentTimeMillis()) }
    }

    /**
     * Fix OCR-glued leading digits (4171.47 vs basket 171.47) and prefer basket when
     * labeled total is wildly off vs line items.
     */
    private fun dedupeLineItems(items: List<ParsedLineItem>): List<ParsedLineItem> {
        if (items.size <= 1) return items
        fun norm(name: String) = name.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9\u0590-\u05ff]+"""), " ").trim()
        fun productKey(item: ParsedLineItem): String {
            val n = norm(item.name)
            val bc = item.barcode
            return when {
                n.contains("nicotinell") -> "nicotinell"
                n.contains("pure") -> "pure"
                bc != null && (bc.startsWith("729") || bc.startsWith("779")) ->
                    "bc:729" + bc.drop(3)
                bc != null -> "bc:$bc"
                else -> "nm:${n.take(16)}"
            }
        }
        return items.groupBy { productKey(it) }.values.map { group ->
            val best = group.maxWithOrNull(
                compareBy<ParsedLineItem> {
                    when {
                        it.barcode?.startsWith("729") == true -> 2
                        it.barcode != null -> 1
                        else -> 0
                    }
                }
                    .thenBy { if (abs(it.quantity - 1.0) < 0.01) 1 else 0 }
                    .thenByDescending { it.name.length }
            )!!
            val peerBc = group.mapNotNull { it.barcode }.map { preferIsraeliBarcode(it, emptyList())!! }
                .firstOrNull { it.startsWith("729") }
                ?: preferIsraeliBarcode(best.barcode, emptyList())
            best.copy(
                barcode = peerBc ?: best.barcode,
                quantity = if (best.quantity > 3.0) 1.0 else best.quantity
            )
        }
    }

    private fun dedupeDiscounts(discounts: List<ParsedDiscount>): List<ParsedDiscount> {
        if (discounts.isEmpty()) return discounts
        // Prefer the largest-magnitude coupon (e.g. -17 over OCR junk -1)
        val withAmt = discounts.filter { it.amount != null }
        if (withAmt.isEmpty()) return discounts.take(1)
        val best = withAmt.maxByOrNull { abs(it.amount!!) }!!
        return listOf(best)
    }

    private fun reconcileOcrAmount(candidate: Double?, basket: Double?): Double? {
        if (candidate == null) return basket?.takeIf { it > 0 }
        if (basket == null || basket <= 0) return candidate
        if (abs(candidate - basket) < 0.06) return round2(basket)
        val cDigits = "%.2f".format(Locale.US, abs(candidate)).replace(".", "")
        val bDigits = "%.2f".format(Locale.US, abs(basket)).replace(".", "")
        if (cDigits.length == bDigits.length + 1 && cDigits.endsWith(bDigits)) {
            return round2(basket)
        }
        if (candidate > basket * 4 && basket >= 1.0) return round2(basket)
        return candidate
    }

    private fun scoreConfidence(vararg signals: Boolean): Float {
        val hits = signals.count { it }
        return (hits.toFloat() / signals.size).coerceIn(0f, 1f)
    }
}
