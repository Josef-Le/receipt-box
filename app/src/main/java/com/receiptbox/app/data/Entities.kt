package com.receiptbox.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "companies", indices = [Index(value = ["legalName"]), Index(value = ["taxId"])])
data class Company(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val legalName: String,
    val brand: String? = null,
    val taxId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "stores",
    foreignKeys = [
        ForeignKey(
            entity = Company::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("companyId"), Index("name"), Index("phone"), Index("branch")]
)
data class Store(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long? = null,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    /** Branch / סניף code when present on the receipt. */
    val branch: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ReceiptStatus { PURCHASE, REFUND, VOID }

@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = Store::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["originalReceiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("storeId"),
        Index("datetime"),
        Index("receiptNumber"),
        Index("status"),
        Index("originalReceiptId"),
        Index("category")
    ]
)
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storeId: Long? = null,
    val photoUri: String,
    val datetime: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null,
    val cashier: String? = null,
    val registerId: String? = null,
    val currency: String = "USD",
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double = 0.0,
    val status: String = ReceiptStatus.PURCHASE.name,
    val originalReceiptId: Long? = null,
    val category: String = "Uncategorized",
    val notes: String = "",
    val merchantDisplay: String = "",
    val parseConfidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"], unique = false),
        Index("sku"),
        Index("normalizedName"),
        Index("storeId")
    ]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storeId: Long? = null,
    val name: String,
    val normalizedName: String,
    val sku: String? = null,
    val barcode: String? = null,
    val defaultCategory: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "line_items",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("receiptId"), Index("productId"), Index("sku"), Index("barcode")]
)
data class LineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val productId: Long? = null,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val quantity: Double = 1.0,
    /** Unit of measure when known (kg, ea, pcs, יח, …). */
    val unit: String? = null,
    val unitPrice: Double? = null,
    val lineTotal: Double = 0.0,
    val taxFlag: String? = null,
    val position: Int = 0
)

@Entity(
    tableName = "discounts",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LineItem::class,
            parentColumns = ["id"],
            childColumns = ["lineItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("receiptId"), Index("lineItemId")]
)
data class Discount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val lineItemId: Long? = null,
    val description: String? = null,
    val code: String? = null,
    val amount: Double? = null,
    val percent: Double? = null
)

@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("receiptId"), Index("method")]
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val method: String,
    val amount: Double,
    val last4: String? = null,
    val authCode: String? = null,
    val changeGiven: Double? = null
)

@Entity(
    tableName = "raw_ocr",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("receiptId")]
)
data class RawOcrText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val rawText: String,
    val parserVersion: String = "1.0",
    val createdAt: Long = System.currentTimeMillis()
)

object Categories {
    val ALL = listOf(
        "Uncategorized", "Meals", "Travel", "Office", "Transport",
        "Utilities", "Entertainment", "Groceries", "Healthcare", "Other"
    )
}

object AppConstants {
    const val PRO_PRODUCT_ID = "receiptbox_pro"
    const val FREE_RECEIPT_LIMIT = 15
    const val WATERMARK_TEXT = "ReceiptBox Free"
}

/** List-row projection for home / filters */
data class ReceiptListItem(
    val id: Long,
    val merchantDisplay: String,
    val total: Double,
    val currency: String,
    val datetime: Long,
    val category: String,
    val status: String,
    val storeName: String? = null,
    val companyName: String? = null,
    val paymentMethod: String? = null
)

data class StoreSpend(
    val storeId: Long,
    val storeName: String,
    val companyName: String?,
    val totalSpend: Double,
    val receiptCount: Int,
    val avgBasket: Double
)

data class ProductSpend(
    val productId: Long?,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val totalSpend: Double,
    val quantity: Double,
    val receiptCount: Int
)

data class PeriodSpend(
    val periodKey: String,
    val totalSpend: Double,
    val receiptCount: Int
)

data class PaymentSpend(
    val method: String,
    val totalSpend: Double,
    val receiptCount: Int
)

data class BarcodePricePoint(
    val barcode: String,
    val name: String,
    val datetime: Long,
    val unitPrice: Double,
    val lineTotal: Double,
    val storeName: String?,
    val currency: String
)

data class BarcodePriceHistory(
    val barcode: String,
    val name: String,
    val points: List<BarcodePricePoint>,
    val minPrice: Double,
    val maxPrice: Double,
    val lastPrice: Double,
    val sampleCount: Int
)

data class AnalyticsSummary(
    val receiptCount: Int = 0,
    val totalSpend: Double = 0.0,
    val avgBasket: Double = 0.0,
    val refundCount: Int = 0,
    val discountTotal: Double = 0.0,
    val discountRate: Double = 0.0,
    val byStore: List<StoreSpend> = emptyList(),
    val byProduct: List<ProductSpend> = emptyList(),
    val byPeriod: List<PeriodSpend> = emptyList(),
    val byPayment: List<PaymentSpend> = emptyList(),
    val topProductsAtTopStore: List<ProductSpend> = emptyList(),
    val barcodePriceHistory: List<BarcodePriceHistory> = emptyList(),
    /** Dominant currency in filtered receipts — never assume USD for IL data. */
    val displayCurrency: String = "ILS"
)

data class ReceiptDetail(
    val receipt: Receipt,
    val store: Store?,
    val company: Company?,
    val lineItems: List<LineItem>,
    val discounts: List<Discount>,
    val payments: List<Payment>,
    val rawOcr: RawOcrText?,
    val originalReceipt: Receipt?
)
