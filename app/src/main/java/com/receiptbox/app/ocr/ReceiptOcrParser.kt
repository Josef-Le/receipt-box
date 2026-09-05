package com.receiptbox.app.ocr

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Heuristic structured parser: ML Kit text → companies/stores/lines/discounts/payments.
 * Best-effort; UI always allows edits.
 */
object ReceiptOcrParser {

    private val moneyToken = Regex("""[€£$₪]?\s*\d{1,6}(?:[.,]\d{3})*[.,]\d{2}|\d+[.,]\d{2}""")
    private val barcodeRe = Regex("""\b(\d{8}|\d{12,14})\b""")
    private val skuRe = Regex("""(?i)\b(?:sku|plu|item[#:\s-]*)([A-Z0-9\-]{3,})\b""")
    private val phoneRe = Regex("""(?i)(?:tel|phone|ph)[:\s]*([+\d][\d\s\-().]{6,})|\b(\+?\d{1,3}[\s\-.]?\(?\d{2,4}\)?[\s\-.]?\d{3,4}[\s\-.]?\d{3,4})\b""")
    private val taxIdRe = Regex("""(?i)(?:VAT|TAX\s*ID|TIN|ABN|EIN|GST|CNPJ|RFC)[:\s#]*([A-Z0-9\-./]{5,})""")
    private val receiptNoRe = Regex("""(?i)(?:receipt|invoice|ticket|check|trans(?:action)?|order)\s*(?:no|num|number|#)?[:\s#]*([A-Z0-9\-/]{3,})""")
    private val cashierRe = Regex("""(?i)(?:cashier|server|clerk|associate|emp(?:loyee)?)[:\s#]*([A-Za-z0-9 ._\-]{2,30})""")
    private val registerRe = Regex("""(?i)(?:register|lane|pos|terminal)\s*(?:#|no|num)?[:\s]*([A-Z0-9\-]{1,10})""")
    private val qtyPriceRe = Regex(
        """(?i)(?:(\d+(?:[.,]\d+)?)\s*[xX@×]\s*)?([€£$₪]?\s*\d+[.,]\d{2})(?:\s+[€£$₪]?\s*(\d+[.,]\d{2}))?"""
    )

    private val totalKeywords = listOf("grand total", "amount due", "total due", "balance due", "total")
    private val subtotalKeywords = listOf("subtotal", "sub-total", "sub total", "net sales")
    private val taxKeywords = listOf("tax", "vat", "gst", "sales tax", "iva")
    private val discountKeywords = listOf("discount", "promo", "coupon", "savings", "markdown", "off")
    private val paymentKeywords = mapOf(
        "VISA" to "CARD",
        "MASTERCARD" to "CARD",
        "MASTER CARD" to "CARD",
        "AMEX" to "CARD",
        "AMERICAN EXPRESS" to "CARD",
        "DEBIT" to "CARD",
        "CREDIT" to "CARD",
        "CARD" to "CARD",
        "CASH" to "CASH",
        "APPLE PAY" to "MOBILE",
        "GOOGLE PAY" to "MOBILE",
        "CONTACTLESS" to "CARD",
        "CHECK" to "CHECK",
        "CHEQUE" to "CHECK",
        "GIFT" to "GIFT_CARD"
    )

    private val skipLine = Regex(
        """(?i)^(thank|welcome|www\.|http|visa|mastercard|authorized|signature|copy|customer|merchant copy|power(?:ed)? by).*"""
    )

    private val dateFormats = listOf(
        "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "MM-dd-yyyy",
        "dd.MM.yyyy", "MM.dd.yyyy", "d/M/yyyy", "M/d/yyyy",
        "dd/MM/yy", "MM/dd/yy", "d/M/yy", "M/d/yy",
        "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "d MMMM yyyy",
        "yyyy/MM/dd HH:mm", "dd/MM/yyyy HH:mm", "MM/dd/yyyy hh:mm a"
    )

    private val datePattern = Pattern.compile(
        "(\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?)?)|" +
            "((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{2,4})",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(rawText: String): StructuredReceiptParse {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return StructuredReceiptParse(rawText = rawText, confidence = 0f)
        }

        val lowerAll = rawText.lowercase(Locale.US)
        val isRefund = listOf("refund", "return", "credit note").any { lowerAll.contains(it) }
        val isVoid = lowerAll.contains("void")

        val merchant = guessMerchant(lines)
        val address = guessAddress(lines)
        val phone = phoneRe.find(rawText)?.let { it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } }?.trim()
        val taxId = taxIdRe.find(rawText)?.groupValues?.getOrNull(1)
        val receiptNumber = receiptNoRe.find(rawText)?.groupValues?.getOrNull(1)
        val cashier = cashierRe.find(rawText)?.groupValues?.getOrNull(1)?.trim()
        val registerId = registerRe.find(rawText)?.groupValues?.getOrNull(1)
        val datetime = guessDate(rawText)
        val currency = guessCurrency(rawText)

        val labeled = extractLabeledAmounts(lines)
        val subtotal = labeled["subtotal"]
        val tax = labeled["tax"]
        val total = labeled["total"] ?: guessLargestTotal(rawText, labeled)

        val discounts = extractDiscounts(lines)
        val payments = extractPayments(lines, total)
        val lineItems = extractLineItems(lines, labeled.keys)

        val originalReceiptNumber = Regex(
            """(?i)(?:original|orig|ref(?:erence)?)\s*(?:receipt|invoice)?\s*[#:]?\s*([A-Z0-9\-/]{3,})"""
        ).find(rawText)?.groupValues?.getOrNull(1)

        val confidence = scoreConfidence(
            merchant != null, total != null, lineItems.isNotEmpty(),
            payments.isNotEmpty(), datetime != null, receiptNumber != null
        )

        return StructuredReceiptParse(
            rawText = rawText,
            merchant = merchant,
            companyName = merchant,
            brand = merchant,
            storeName = merchant,
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
            confidence = confidence
        )
    }

    private fun guessMerchant(lines: List<String>): String? {
        val skip = listOf("receipt", "invoice", "tax", "tel", "phone", "www", "http", "thank", "cashier")
        for (line in lines.take(10)) {
            val lower = line.lowercase(Locale.US)
            if (line.length < 3 || line.length > 48) continue
            if (skip.any { lower.contains(it) }) continue
            if (line.count { it.isDigit() } > line.length / 2) continue
            if (moneyToken.containsMatchIn(line)) continue
            if (line.any { it.isLetter() }) return line.take(48)
        }
        return lines.firstOrNull { it.any(Char::isLetter) }?.take(48)
    }

    private fun guessAddress(lines: List<String>): String? {
        val addr = Regex("""(?i)\d{1,5}\s+\w+.*(st|street|rd|road|ave|avenue|blvd|suite|unit|floor|#)""")
        val hits = lines.filter { addr.containsMatchIn(it) || Regex("""(?i)\b[A-Z]{2}\s+\d{5}(-\d{4})?\b""").containsMatchIn(it) }
        return hits.take(2).joinToString(", ").ifBlank { null }
    }

    private fun extractLabeledAmounts(lines: List<String>): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (line in lines) {
            val lower = line.lowercase(Locale.US)
            val amount = moneyToken.findAll(line).lastOrNull()?.value?.let { parseMoney(it) } ?: continue
            when {
                subtotalKeywords.any { lower.contains(it) } -> map.putIfAbsent("subtotal", amount)
                taxKeywords.any { lower.contains(it) } && !lower.contains("tax id") -> map.putIfAbsent("tax", amount)
                totalKeywords.any { kw ->
                    lower.contains(kw) && (kw != "total" || !lower.contains("sub"))
                } -> {
                    // Prefer more specific totals
                    val existing = map["total"]
                    if (existing == null || lower.contains("grand") || lower.contains("amount due")) {
                        map["total"] = amount
                    }
                }
            }
        }
        return map
    }

    private fun guessLargestTotal(text: String, labeled: Map<String, Double>): Double? {
        labeled["total"]?.let { return it }
        val amounts = moneyToken.findAll(text).mapNotNull { parseMoney(it.value) }
            .filter { it in 0.01..999_999.0 }
            .toList()
        return amounts.maxOrNull()
    }

    private fun extractDiscounts(lines: List<String>): List<ParsedDiscount> {
        val result = mutableListOf<ParsedDiscount>()
        lines.forEachIndexed { idx, line ->
            val lower = line.lowercase(Locale.US)
            if (discountKeywords.none { lower.contains(it) }) return@forEachIndexed
            val amount = moneyToken.findAll(line).lastOrNull()?.value?.let { parseMoney(it) }
            val percent = Regex("""(\d{1,2}(?:[.,]\d+)?)\s*%""").find(line)?.groupValues?.get(1)
                ?.replace(',', '.')?.toDoubleOrNull()
            val code = Regex("""(?i)(?:code|promo)[:\s]*([A-Z0-9\-]+)""").find(line)?.groupValues?.get(1)
            result.add(
                ParsedDiscount(
                    description = line.take(80),
                    code = code,
                    amount = amount?.let { -abs(it) },
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
            val method = paymentKeywords.entries.firstOrNull { upper.contains(it.key) }?.value ?: continue
            val amount = moneyToken.findAll(line).lastOrNull()?.value?.let { parseMoney(it) }
                ?: fallbackTotal
                ?: continue
            val last4 = Regex("""(?:X{2,}|\*{2,}|\u2022{2,}|ending)\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)
                ?: Regex("""\b(\d{4})\s*$""").find(line)?.groupValues?.get(1)
            val auth = Regex("""(?i)(?:auth|approval)[:\s#]*([A-Z0-9]+)""").find(line)?.groupValues?.get(1)
            val change = if (upper.contains("CHANGE")) {
                moneyToken.findAll(line).lastOrNull()?.value?.let { parseMoney(it) }
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

    private fun extractLineItems(lines: List<String>, labeledKeys: Set<String>): List<ParsedLineItem> {
        val items = mutableListOf<ParsedLineItem>()
        val headerEnd = lines.indexOfFirst { it.length > 3 && it.any(Char::isLetter) }.coerceAtLeast(0)
        val footerStart = lines.indexOfFirst { line ->
            val l = line.lowercase(Locale.US)
            subtotalKeywords.any { l.contains(it) } || totalKeywords.any { l.contains(it) && !l.contains("sub") }
        }.let { if (it < 0) lines.size else it }

        for (i in (headerEnd + 1) until footerStart) {
            val line = lines[i]
            val lower = line.lowercase(Locale.US)
            if (skipLine.matches(line)) continue
            if (discountKeywords.any { lower.contains(it) }) continue
            if (paymentKeywords.keys.any { lower.contains(it.lowercase(Locale.US)) }) continue
            if (taxKeywords.any { lower.contains(it) }) continue
            if (subtotalKeywords.any { lower.contains(it) }) continue
            if (totalKeywords.any { lower.contains(it) }) continue
            if (!moneyToken.containsMatchIn(line) && barcodeRe.find(line) == null) continue

            val amounts = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.toList()
            if (amounts.isEmpty()) continue

            val qtyMatch = Regex("""(?i)^\s*(\d+(?:[.,]\d+)?)\s*[xX@×]""").find(line)
            val quantity = qtyMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
            val lineTotal = amounts.last()
            val unitPrice = when {
                amounts.size >= 2 -> amounts[amounts.size - 2]
                quantity > 0 -> lineTotal / quantity
                else -> lineTotal
            }

            var name = line
                .replace(moneyToken, "")
                .replace(Regex("""(?i)\d+(?:[.,]\d+)?\s*[xX@×]"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .trimStart('-', '.', ' ')
            if (name.length < 2) continue
            if (name.length > 60) name = name.take(60)

            val barcode = barcodeRe.find(line)?.groupValues?.get(1)
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

    private fun guessCurrency(text: String): String = when {
        text.contains('€') || text.contains("EUR", true) -> "EUR"
        text.contains('£') || text.contains("GBP", true) -> "GBP"
        text.contains('₪') || text.contains("ILS", true) || text.contains("NIS", true) -> "ILS"
        else -> "USD"
    }

    private fun parseMoney(raw: String): Double? {
        var s = raw.replace(Regex("[€£$₪\\s]"), "").trim()
        if (s.isEmpty()) return null
        s = when {
            s.contains(',') && s.contains('.') -> {
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            }
            s.contains(',') && s.indexOf(',') == s.length - 3 -> s.replace(',', '.')
            s.contains(',') -> s.replace(",", "")
            else -> s
        }
        return s.toDoubleOrNull()?.takeIf { it >= 0 && it < 1_000_000 }
    }

    private fun guessDate(text: String): Long? {
        val matcher = datePattern.matcher(text)
        val candidates = mutableListOf<Long>()
        while (matcher.find()) {
            val token = matcher.group()?.trim() ?: continue
            for (fmt in dateFormats) {
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
