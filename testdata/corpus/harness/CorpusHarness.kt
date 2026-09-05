import java.io.File
import com.receiptbox.app.ocr.ReceiptOcrParser

fun esc(s: String?): String {
    if (s == null) return "null"
    val e = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return "\"$e\""
}

fun main(args: Array<String>) {
    val ocrDir = File(args.getOrElse(0) { "/workspace/receipt-box/testdata/corpus/ocr" })
    val outFile = File(args.getOrElse(1) { "/workspace/receipt-box/testdata/corpus/results.jsonl" })
    val files = ocrDir.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: emptyList()
    outFile.parentFile.mkdirs()
    outFile.printWriter().use { pw ->
        for (f in files) {
            val text = f.readText(Charsets.UTF_8)
            val id = f.nameWithoutExtension
            var crash: String? = null
            val parse = try {
                ReceiptOcrParser.parse(text)
            } catch (t: Throwable) {
                crash = t.javaClass.simpleName + ": " + (t.message ?: "")
                null
            }
            val ocrLen = text.length
            val ocrBlank = text.trim().isEmpty()
            val currency = parse?.currency
            val total = parse?.total
            val items = parse?.lineItems?.size ?: 0
            val discounts = parse?.discounts?.size ?: 0
            val merchant = parse?.merchant
            val conf = parse?.confidence
            val company = parse?.companyName
            val tax = parse?.tax
            val subtotal = parse?.subtotal
            val itemNames = parse?.lineItems?.take(5)?.joinToString("|") { it.name } ?: ""
            val sb = StringBuilder()
            sb.append("{")
            sb.append("\"id\":").append(esc(id)).append(",")
            sb.append("\"ocr_chars\":").append(ocrLen).append(",")
            sb.append("\"ocr_blank\":").append(ocrBlank).append(",")
            sb.append("\"crash\":").append(esc(crash)).append(",")
            sb.append("\"currency\":").append(esc(currency)).append(",")
            sb.append("\"total\":").append(total?.toString() ?: "null").append(",")
            sb.append("\"total_present\":").append(total != null).append(",")
            sb.append("\"subtotal\":").append(subtotal?.toString() ?: "null").append(",")
            sb.append("\"tax\":").append(tax?.toString() ?: "null").append(",")
            sb.append("\"line_items\":").append(items).append(",")
            sb.append("\"discounts\":").append(discounts).append(",")
            sb.append("\"merchant\":").append(esc(merchant)).append(",")
            sb.append("\"merchant_nonempty\":").append(!(merchant.isNullOrBlank())).append(",")
            sb.append("\"company\":").append(esc(company)).append(",")
            sb.append("\"confidence\":").append(conf?.toString() ?: "null").append(",")
            sb.append("\"item_names_sample\":").append(esc(itemNames)).append(",")
            sb.append("\"ocr_preview\":").append(esc(text.take(240).replace("\n", " | ")))
            sb.append("}")
            pw.println(sb.toString())
        }
    }
    println("Wrote ${files.size} results to ${outFile.absolutePath}")
}
