package com.receiptbox.app.ocr

data class ParsedLineItem(
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val quantity: Double = 1.0,
    val unitPrice: Double? = null,
    val lineTotal: Double = 0.0,
    val taxFlag: String? = null,
    val lineDiscount: ParsedDiscount? = null
)

data class ParsedDiscount(
    val description: String? = null,
    val code: String? = null,
    val amount: Double? = null,
    val percent: Double? = null,
    val lineIndex: Int? = null
)

data class ParsedPayment(
    val method: String,
    val amount: Double,
    val last4: String? = null,
    val authCode: String? = null,
    val changeGiven: Double? = null
)

data class StructuredReceiptParse(
    val rawText: String,
    val merchant: String? = null,
    val companyName: String? = null,
    val brand: String? = null,
    val storeName: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val taxId: String? = null,
    val datetimeMillis: Long? = null,
    val receiptNumber: String? = null,
    val cashier: String? = null,
    val registerId: String? = null,
    val currency: String = "USD",
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val isRefund: Boolean = false,
    val isVoid: Boolean = false,
    val originalReceiptNumber: String? = null,
    val lineItems: List<ParsedLineItem> = emptyList(),
    val discounts: List<ParsedDiscount> = emptyList(),
    val payments: List<ParsedPayment> = emptyList(),
    val confidence: Float = 0f,
    val parserVersion: String = "1.0"
)
