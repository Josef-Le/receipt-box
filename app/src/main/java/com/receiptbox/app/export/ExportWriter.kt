package com.receiptbox.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.ExportBundle
import com.receiptbox.app.data.ReceiptDetail
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportWriter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun writeRelationalCsv(context: Context, bundle: ExportBundle, watermark: Boolean): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "receiptbox_dump_${stampFmt.format(Date())}.csv")
        file.bufferedWriter().use { out ->
            if (watermark) out.appendLine("# ${AppConstants.WATERMARK_TEXT}")
            out.appendLine("## receipts")
            out.appendLine("id,datetime,merchant,store,company,receipt_number,status,currency,subtotal,tax,total,category,confidence")
            bundle.details.forEach { d ->
                val r = d.receipt
                out.appendLine(
                    listOf(
                        r.id, dateFmt.format(Date(r.datetime)), csv(r.merchantDisplay),
                        csv(d.store?.name), csv(d.company?.legalName), csv(r.receiptNumber),
                        r.status, r.currency, r.subtotal, r.tax, r.total, csv(r.category), r.parseConfidence
                    ).joinToString(",")
                )
            }
            out.appendLine()
            out.appendLine("## line_items")
            out.appendLine("receipt_id,position,name,sku,barcode,qty,unit_price,line_total,tax_flag")
            bundle.details.forEach { d ->
                d.lineItems.forEach { li ->
                    out.appendLine(
                        listOf(
                            d.receipt.id, li.position, csv(li.name), csv(li.sku), csv(li.barcode),
                            li.quantity, li.unitPrice, li.lineTotal, csv(li.taxFlag)
                        ).joinToString(",")
                    )
                }
            }
            out.appendLine()
            out.appendLine("## payments")
            out.appendLine("receipt_id,method,amount,last4,auth,change")
            bundle.details.forEach { d ->
                d.payments.forEach { p ->
                    out.appendLine(
                        listOf(d.receipt.id, p.method, p.amount, csv(p.last4), csv(p.authCode), p.changeGiven)
                            .joinToString(",")
                    )
                }
            }
            out.appendLine()
            out.appendLine("## discounts")
            out.appendLine("receipt_id,line_item_id,description,code,amount,percent")
            bundle.details.forEach { d ->
                d.discounts.forEach { disc ->
                    out.appendLine(
                        listOf(
                            d.receipt.id, disc.lineItemId, csv(disc.description), csv(disc.code),
                            disc.amount, disc.percent
                        ).joinToString(",")
                    )
                }
            }
        }
        return file
    }

    fun writeJson(context: Context, bundle: ExportBundle, watermark: Boolean): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "receiptbox_dump_${stampFmt.format(Date())}.json")
        val root = JSONObject()
        if (watermark) root.put("watermark", AppConstants.WATERMARK_TEXT)
        root.put("exportedAt", dateFmt.format(Date()))
        root.put("companies", JSONArray().also { arr ->
            bundle.companies.forEach { c ->
                arr.put(JSONObject().put("id", c.id).put("legalName", c.legalName)
                    .put("brand", c.brand).put("taxId", c.taxId))
            }
        })
        root.put("stores", JSONArray().also { arr ->
            bundle.stores.forEach { s ->
                arr.put(JSONObject().put("id", s.id).put("companyId", s.companyId)
                    .put("name", s.name).put("address", s.address).put("phone", s.phone))
            }
        })
        root.put("products", JSONArray().also { arr ->
            bundle.products.forEach { p ->
                arr.put(JSONObject().put("id", p.id).put("storeId", p.storeId).put("name", p.name)
                    .put("sku", p.sku).put("barcode", p.barcode))
            }
        })
        root.put("receipts", JSONArray().also { arr ->
            bundle.details.forEach { d -> arr.put(detailJson(d)) }
        })
        file.writeText(root.toString(2))
        return file
    }

    fun writePdfSummary(context: Context, details: List<ReceiptDetail>, watermark: Boolean): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "receipts_${stampFmt.format(Date())}.pdf")
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val lineHeight = 16f
        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true }
        val textPaint = Paint().apply { color = Color.BLACK; textSize = 10f }
        val watermarkPaint = Paint().apply { color = Color.LTGRAY; textSize = 42f; isFakeBoldText = true }

        val rows = details.ifEmpty { emptyList() }
        val chunk = 40
        val pages = if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(chunk)
        pages.forEachIndexed { pageIndex, pageRows ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            if (watermark) {
                canvas.save()
                canvas.rotate(-35f, pageWidth / 2f, pageHeight / 2f)
                canvas.drawText(AppConstants.WATERMARK_TEXT, pageWidth / 2f - 140f, pageHeight / 2f, watermarkPaint)
                canvas.restore()
            }
            var y = margin
            canvas.drawText("ReceiptBox relational summary", margin, y, titlePaint)
            y += 22f
            canvas.drawText("Page ${pageIndex + 1}/${pages.size} · ${details.size} receipts", margin, y, textPaint)
            y += 20f
            pageRows.forEach { d ->
                val r = d.receipt
                canvas.drawText(
                    "${dateFmt.format(Date(r.datetime))}  ${r.merchantDisplay.take(28)}  ${r.currency} ${"%.2f".format(r.total)}  [${r.status}]  lines=${d.lineItems.size}",
                    margin, y, textPaint
                )
                y += lineHeight
            }
            doc.finishPage(page)
        }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun detailJson(d: ReceiptDetail): JSONObject {
        val r = d.receipt
        return JSONObject()
            .put("id", r.id)
            .put("datetime", dateFmt.format(Date(r.datetime)))
            .put("merchant", r.merchantDisplay)
            .put("storeId", r.storeId)
            .put("receiptNumber", r.receiptNumber)
            .put("status", r.status)
            .put("currency", r.currency)
            .put("subtotal", r.subtotal)
            .put("tax", r.tax)
            .put("total", r.total)
            .put("category", r.category)
            .put("confidence", r.parseConfidence)
            .put("originalReceiptId", r.originalReceiptId)
            .put("lineItems", JSONArray().also { arr ->
                d.lineItems.forEach { li ->
                    arr.put(
                        JSONObject().put("name", li.name).put("sku", li.sku).put("barcode", li.barcode)
                            .put("qty", li.quantity).put("unitPrice", li.unitPrice)
                            .put("lineTotal", li.lineTotal).put("productId", li.productId)
                    )
                }
            })
            .put("payments", JSONArray().also { arr ->
                d.payments.forEach { p ->
                    arr.put(JSONObject().put("method", p.method).put("amount", p.amount)
                        .put("last4", p.last4).put("auth", p.authCode))
                }
            })
            .put("discounts", JSONArray().also { arr ->
                d.discounts.forEach { disc ->
                    arr.put(JSONObject().put("description", disc.description).put("code", disc.code)
                        .put("amount", disc.amount).put("percent", disc.percent)
                        .put("lineItemId", disc.lineItemId))
                }
            })
            .put("rawOcr", d.rawOcr?.rawText)
    }

    private fun csv(value: Any?): String {
        val s = value?.toString() ?: ""
        val needs = s.contains(',') || s.contains('"') || s.contains('\n')
        val escaped = s.replace("\"", "\"\"")
        return if (needs) "\"$escaped\"" else escaped
    }
}
