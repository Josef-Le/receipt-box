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

    private val moneyToken = Regex(
        """-?\s*[€£$₪]?\s*\d{1,6}(?:[.,]\d{3})*[.,]\d{2}|-?\s*\d+[.,]\d{2}"""
    )
    private val barcodeRe = Regex("""\b(\d{8}|\d{12,14})\b""")
    private val israeliBarcodeRe = Regex("""\b(729\d{10,11})\b""")
    private val skuRe = Regex("""(?i)\b(?:sku|plu|item[#:\s-]*)([A-Z0-9\-]{3,})\b""")
    private val phoneRe = Regex(
        """(?i)(?:tel|phone|ph|טל(?:פון)?)[:\s]*([+\d][\d\s\-().]{6,})""" +
            """|\b(0(?:5\d|7\d|2|3|4|8|9)\d{7})\b""" +
            """|\b(\+?\d{1,3}[\s\-.]?\(?\d{2,4}\)?[\s\-.]?\d{3,4}[\s\-.]?\d{3,4})\b"""
    )
    private val taxIdRe = Regex(
        """(?i)(?:VAT|TAX\s*ID|TIN|ABN|EIN|GST|CNPJ|RFC|ח\.?\s*פ\.?|חפ)[:\s#]*([A-Z0-9\-./]{5,})""" +
            """|(?:ח\.?\s*פ\.?|חפ)\s*[#:]?\s*(\d{8,9})"""
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
        "לתשלום", "סה\"כ לתשלום", "סהכ לתשלום", "סך הכל", "סך-הכל", "סה\"כ"
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
        "קופון", "הנחה", "הנחות", "חסכת", "חיסכון", "מבצע"
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
        "אשראי" to "CARD",
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
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return StructuredReceiptParse(rawText = rawText, confidence = 0f, parserVersion = PARSER_VERSION)
        }

        val lowerAll = rawText.lowercase(Locale.US)
        val israeli = isIsraeliContext(rawText, lines)
        val isRefund = listOf("refund", "return", "credit note", "זיכוי", "החזר").any { lowerAll.contains(it) }
        val isVoid = lowerAll.contains("void") || rawText.contains("מבוטל")

        val knownRetailer = detectKnownRetailer(rawText)
        val companyName = guessCompany(lines, knownRetailer)
        val storeName = guessStore(lines, knownRetailer, companyName)
        val merchant = storeName ?: companyName ?: knownRetailer ?: guessMerchant(lines)
        val address = guessAddress(lines, israeli)
        val phone = extractPhone(rawText)
        val taxId = extractTaxId(rawText)
        val receiptNumber = extractReceiptNumber(rawText, lines)
        val cashier = extractCashier(rawText)
        val branch = branchRe.find(rawText)?.groupValues?.getOrNull(1)
        val registerAlone = registerRe.find(rawText)?.groupValues?.getOrNull(1)
        val registerId = when {
            branch != null && registerAlone != null -> "$branch/$registerAlone"
            registerAlone != null -> registerAlone
            branch != null -> branch
            else -> null
        }
        val datetime = guessDate(rawText, preferDayFirst = israeli)
        val currency = guessCurrency(rawText, israeli)

        val labeled = extractLabeledAmounts(lines, israeli)
        val tax = labeled["tax"]
        val total = labeled["total"] ?: guessLargestTotal(rawText, labeled)
        val subtotal = labeled["subtotal"]
            ?: labeled["taxable"]
            ?: deriveSubtotal(total, tax)

        val discounts = extractDiscounts(lines)
        val payments = extractPayments(lines, total)
        val lineItems = extractLineItems(lines, israeli)

        val originalReceiptNumber = Regex(
            """(?i)(?:original|orig|ref(?:erence)?)\s*(?:receipt|invoice)?\s*[#:]?\s*([A-Z0-9\-/]{3,})"""
        ).find(rawText)?.groupValues?.getOrNull(1)

        val confidence = scoreConfidence(
            merchant != null && !looksLikeOcrGarbage(merchant),
            total != null,
            lineItems.isNotEmpty(),
            payments.isNotEmpty() || total != null,
            datetime != null,
            receiptNumber != null,
            currency == "ILS" && israeli || !israeli,
            tax != null || !israeli,
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
            taxId = taxId,
            datetimeMillis = datetime,
            receiptNumber = receiptNumber,
            cashier = cashier,
            registerId = registerId,
            currency = currency,
            subtotal = subtotal,
            tax = tax,
            total = total,
            isRefund = isRefund,
            isVoid = isVoid,
            originalReceiptNumber = originalReceiptNumber,
            lineItems = lineItems,
            discounts = discounts,
            payments = payments,
            confidence = confidence,
            parserVersion = PARSER_VERSION
        )
    }

    private const val PARSER_VERSION = "1.1-il"

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
                text.contains("סופרפארם") || text.contains("סופר פארם") -> "Super-Pharm"
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
        phoneRe.find(rawText)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.replace(" ", "")?.trim()?.let { return it }
        }
        Regex("""\b(0(?:5\d|7\d|2|3|4|8|9)\d{7})\b""").find(rawText)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractTaxId(rawText: String): String? {
        taxIdRe.find(rawText)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { return it }
        }
        Regex("""(?:ח\.?\s*פ\.?|חפ)\s*[:#]?\s*(\d{8,9})""").find(rawText)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractReceiptNumber(rawText: String, lines: List<String>): String? {
        ilReceiptNoRe.find(rawText)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { return it }
        }
        receiptNoRe.find(rawText)?.groupValues?.getOrNull(1)?.let { return it }
        // Super-Pharm: "חשב' מס קבלה" then number on same or next line
        lines.forEachIndexed { i, line ->
            if (line.contains("קבלה") || line.contains("חשב") || line.contains("העתק") ||
                line.contains("invoice", true) || line.contains("receipt", true)
            ) {
                Regex("""\b(\d{6,10})\b""").find(line)?.groupValues?.get(1)?.let { return it }
                lines.getOrNull(i + 1)?.let { next ->
                    if (Regex("""^\d{6,10}$""").matches(next.trim())) return next.trim()
                }
            }
        }
        return null
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
        for (line in lines) {
            val lower = line.lowercase(Locale.US)
            val amount = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                .lastOrNull { it >= 0 } ?: continue

            when {
                line.contains("חייבים") && (line.contains("מע") || lower.contains("vat")) ->
                    map.putIfAbsent("taxable", amount)
                subtotalKeywords.any { lower.contains(it) || line.contains(it) } ->
                    map.putIfAbsent("subtotal", amount)
                // VAT / מע"מ amount (not the bare "18%")
                isVatAmountLine(line) -> {
                    // Prefer the non-percent money token (26.16 not 18.00)
                    val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                        .filter { it >= 0 }
                        .toList()
                    val vatAmount = amounts.lastOrNull { a ->
                        !Regex("""${Regex.escape(formatLoose(a))}\s*%""").containsMatchIn(line.replace(',', '.')) &&
                            a != 18.0 && a != 17.0
                    } ?: amounts.lastOrNull { it != 18.0 && it != 17.0 }
                    if (vatAmount != null) map.putIfAbsent("tax", vatAmount)
                }
                taxKeywords.any { lower.contains(it) || line.contains(it) } &&
                    !lower.contains("tax id") && !line.contains("ח.פ") && !isVatPercentOnly(line) ->
                    map.putIfAbsent("tax", amount)
                isTotalLine(line, lower) -> {
                    val existing = map["total"]
                    if (existing == null || lower.contains("grand") || lower.contains("amount due") ||
                        line.contains("לתשלום")
                    ) {
                        map["total"] = amount
                    }
                }
            }
        }
        if (israeli && map["subtotal"] == null) {
            map["taxable"]?.let { map["subtotal"] = it }
        }
        return map
    }

    private fun formatLoose(a: Double): String {
        val asInt = a.toInt().toDouble()
        return if (a == asInt) a.toInt().toString() else "%.2f".format(Locale.US, a)
    }

    private fun isVatAmountLine(line: String): Boolean {
        if (vatSummaryLine.containsMatchIn(line)) return true
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
        lines.forEachIndexed { _, line ->
            val lower = line.lowercase(Locale.US)
            val hasKeyword = discountKeywords.any { lower.contains(it) || line.contains(it) }
            val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }.toList()
            val neg = amounts.firstOrNull { it < 0 }
            val starredCoupon = line.trimStart().startsWith("*") && (hasKeyword || neg != null)
            if (!hasKeyword && !starredCoupon && neg == null) return@forEachIndexed
            // Skip savings-summary footers — prefer the coupon line
            val isSavingsSummary = line.contains("חסכת") || lower.contains("you saved") ||
                (lower.contains("saved") && !lower.contains("coupon") && !line.contains("קופון") && !line.trimStart().startsWith("*"))
            if (isSavingsSummary && (result.isNotEmpty() || neg == null)) return@forEachIndexed
            if (isVatAmountLine(line) || isTotalLine(line, lower)) return@forEachIndexed

            val amount = (neg ?: amounts.lastOrNull()?.let { -abs(it) })?.let { -abs(it) }
            val percent = Regex("""(\d{1,2}(?:[.,]\d+)?)\s*%""").find(line)?.groupValues?.get(1)
                ?.replace(',', '.')?.toDoubleOrNull()
            val code = Regex("""(?i)(?:code|promo)[:\s]*([A-Z0-9\-]+)""").find(line)?.groupValues?.get(1)
            val desc = line.replace(moneyToken, "").replace(Regex("""\s{2,}"""), " ").trim().take(80)
            val amountKey = amount?.let { "amt:" + "%.2f".format(abs(it)) }
            val key = amountKey ?: (desc.lowercase(Locale.US) + "|" + percent)
            if (key in seen) return@forEachIndexed
            if (amount == null && percent == null) return@forEachIndexed
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
        for (line in lines) {
            val upper = line.uppercase(Locale.US)
            val method = paymentKeywords.entries.firstOrNull {
                upper.contains(it.key.uppercase(Locale.US)) || line.contains(it.key)
            }?.value ?: continue
            if (line.contains("עודף") || upper.contains("CHANGE")) {
                // change line may also mention card — skip as payment method source
                if (!paymentKeywords.keys.any { line.contains(it) && it != "CHANGE" }) continue
            }
            val amount = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }
                .lastOrNull { it >= 0 }
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
            // Column headers include לתשלום — only treat as footer when money is present
            val isColumnHeader = (line.contains("שם") && line.contains("מחיר")) ||
                (l.contains("qty") && l.contains("price")) ||
                (line.contains("כמות") && line.contains("מחיר"))
            if (isColumnHeader) return@indexOfFirst false
            (line.contains("לתשלום") && moneyToken.containsMatchIn(line)) ||
                (subtotalKeywords.any { l.contains(it) || line.contains(it) } && moneyToken.containsMatchIn(line)) ||
                (isTotalLine(line, l) && moneyToken.containsMatchIn(line))
        }.let { if (it < 0) lines.size else it }

        var pendingBarcode: String? = null

        for (i in headerEnd until footerStart.coerceAtMost(lines.size)) {
            val line = lines[i]
            val lower = line.lowercase(Locale.US)

            // Standalone barcode line (Super-Pharm style)
            val onlyBarcode = barcodeRe.find(line)?.groupValues?.get(1)?.takeIf {
                line.replace(it, "").replace(Regex("""\s+"""), "").isEmpty()
            }
            if (onlyBarcode != null) {
                pendingBarcode = onlyBarcode
                continue
            }

            if (skipLine.matches(line)) continue
            if (isDiscountLine(line)) continue
            if (isVatAmountLine(line) || isVatPercentOnly(line)) continue
            if (percentOnlyNoise.containsMatchIn(line) && moneyToken.containsMatchIn(line)) continue
            if (paymentKeywords.keys.any { lower.contains(it.lowercase(Locale.US)) || line.contains(it) }) continue
            if (taxKeywords.any { lower.contains(it) || line.contains(it) }) continue
            if (subtotalKeywords.any { lower.contains(it) || line.contains(it) }) continue
            if (isTotalLine(line, lower)) continue
            if (line.contains("עודף") || lower.contains("change")) continue
            if (line.contains("חסכת")) continue
            if (branchRe.containsMatchIn(line) || registerRe.containsMatchIn(line)) continue
            if (cashierRe.containsMatchIn(line)) continue
            if (!moneyToken.containsMatchIn(line) && barcodeRe.find(line) == null) {
                pendingBarcode = null
                continue
            }

            val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value, allowNegative = true) }.toList()
            if (amounts.isEmpty()) continue
            // Negative → discount, not product
            if (amounts.any { it < 0 }) continue
            // Bare percent / VAT rate mistaken as product
            if (amounts.size == 1 && (amounts[0] == 18.0 || amounts[0] == 17.0) && line.contains('%')) continue

            val qtyMatch = Regex("""(?i)(?:^|\s)(\d+(?:[.,]\d+)?)\s*[xX@×]""").find(line)
            val qtyFromCols = guessQtyFromPharmacyColumns(amounts)
            val quantity = qtyMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                ?: qtyFromCols
                ?: 1.0

            val lineTotal = when {
                // Super-Pharm columns: price, qty, total — last is line total
                amounts.size >= 3 -> amounts.last()
                amounts.size == 2 && amounts[1] in listOf(1.0, 1.00) -> amounts[0]
                amounts.size == 2 && amounts[0] == amounts[1] -> amounts.last()
                else -> amounts.last()
            }
            val unitPrice = when {
                amounts.size >= 3 -> amounts[0]
                amounts.size >= 2 && amounts[1] !in listOf(1.0, 1.00) && amounts[0] != amounts[1] ->
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
            // Prefer Latin product fragment when mixed garbage (e.g. "77 PURE 24 .U.J")
            name = preferLatinProductName(name)
            if (name.length < 2) {
                pendingBarcode = null
                continue
            }
            if (looksLikeOcrGarbage(name) && pendingBarcode == null && !israeliBarcodeRe.containsMatchIn(line)) {
                // Keep if we have a barcode (Israeli product) even with messy Hebrew name
                if (!(israeli && barcodeRe.containsMatchIn(line))) {
                    pendingBarcode = null
                    continue
                }
            }
            if (name.length > 60) name = name.take(60)

            val barcode = barcodeRe.find(line)?.groupValues?.get(1) ?: pendingBarcode
            pendingBarcode = null
            val sku = skuRe.find(line)?.groupValues?.get(1)
            val taxFlag = when {
                lower.contains(" taxable") || lower.endsWith(" t") -> "TAXABLE"
                lower.contains(" non-tax") || lower.endsWith(" n") -> "NON_TAXABLE"
                else -> null
            }

            items.add(
                ParsedLineItem(
                    name = name,
                    sku = sku,
                    barcode = barcode,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    lineTotal = lineTotal,
                    taxFlag = taxFlag
                )
            )
        }
        return items
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
        text.contains('$') || text.contains("USD", true) -> "USD"
        else -> "USD"
    }

    private fun parseMoney(raw: String, allowNegative: Boolean = false): Double? {
        var s = raw.replace(Regex("[€£$₪\\s]"), "").trim()
        if (s.isEmpty()) return null
        val neg = s.startsWith('-')
        if (neg) s = s.removePrefix("-").trim()
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

    private fun scoreConfidence(vararg signals: Boolean): Float {
        val hits = signals.count { it }
        return (hits.toFloat() / signals.size).coerceIn(0f, 1f)
    }
}
