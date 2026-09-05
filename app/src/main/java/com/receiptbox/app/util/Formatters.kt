package com.receiptbox.app.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

object Formatters {
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val dateInputFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun formatDate(millis: Long): String = dateFmt.format(Date(millis))

    fun formatDateInput(millis: Long): String = dateInputFmt.format(Date(millis))

    fun parseDateInput(value: String): Long? = try {
        dateInputFmt.parse(value)?.time
    } catch (_: Exception) {
        null
    }

    fun formatMoney(amount: Double, currencyCode: String): String {
        return try {
            val fmt = NumberFormat.getCurrencyInstance(Locale.getDefault())
            fmt.currency = Currency.getInstance(currencyCode)
            fmt.format(amount)
        } catch (_: Exception) {
            "$currencyCode ${"%.2f".format(Locale.US, amount)}"
        }
    }
}
