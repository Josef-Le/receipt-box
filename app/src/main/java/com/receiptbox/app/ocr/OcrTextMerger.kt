package com.receiptbox.app.ocr

/**
 * Merge non-empty OCR engine outputs before [ReceiptOcrParser.parse].
 * Keeps complementary lines (barcodes, Latin products, CJK, Hebrew) while dropping exact dupes.
 */
object OcrTextMerger {

    private val barcodeRe = Regex("""\b(\d{8}|\d{12,14})\b""")
    private val usefulToken = Regex("""[A-Za-z\u0590-\u05FF\u0400-\u04FF\u0600-\u06FF\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]{2,}|\d{1,6}[.,]\d{2}""")

    fun merge(vararg blocks: String): String = merge(blocks.toList())

    fun merge(blocks: List<String>): String {
        val nonEmpty = blocks.map { it.trim() }.filter { it.length >= 4 }
        if (nonEmpty.isEmpty()) return ""
        if (nonEmpty.size == 1) return nonEmpty[0]

        val seen = linkedSetOf<String>()
        val out = StringBuilder()
        for ((idx, block) in nonEmpty.sortedByDescending { score(it) }.withIndex()) {
            out.appendLine("=== engine-$idx ===")
            for (line in block.lines()) {
                val t = line.trim()
                if (t.isBlank()) continue
                val key = t.lowercase().replace(Regex("""\s+"""), " ")
                if (key in seen) continue
                if (!usefulToken.containsMatchIn(t) && !barcodeRe.containsMatchIn(t)) continue
                seen.add(key)
                out.appendLine(t)
            }
        }
        return out.toString().trim()
    }

    fun score(text: String): Int {
        if (text.isBlank()) return 0
        var s = 0
        if (Regex("""\d{1,6}[.,]\d{2}""").containsMatchIn(text)) s += 3
        if (barcodeRe.containsMatchIn(text)) s += 2
        if (Regex("""\d{2}/\d{2}/20\d{2}""").containsMatchIn(text)) s += 1
        if (Regex("""(?i)total|לתשלום|vat|מע.?מ|receipt|קבלה|税|合計""").containsMatchIn(text)) s += 2
        if (Regex("""[\u0590-\u05FF]{3,}""").containsMatchIn(text)) s += 2
        if (Regex("""(?i)nicotinell|super.?pharm|shufersal""").containsMatchIn(text)) s += 2
        s += (text.count { it.isLetterOrDigit() } / 80).coerceAtMost(3)
        return s
    }
}
