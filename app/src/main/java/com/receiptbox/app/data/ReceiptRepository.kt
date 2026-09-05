package com.receiptbox.app.data

import com.receiptbox.app.ocr.ParsedDiscount
import com.receiptbox.app.ocr.ParsedLineItem
import com.receiptbox.app.ocr.ParsedPayment
import com.receiptbox.app.ocr.StructuredReceiptParse
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ReceiptRepository(private val db: ReceiptDatabase) {
    private val companies get() = db.companyDao()
    private val stores get() = db.storeDao()
    private val products get() = db.productDao()
    private val receipts get() = db.receiptDao()
    private val lines get() = db.lineItemDao()
    private val discounts get() = db.discountDao()
    private val payments get() = db.paymentDao()
    private val rawOcr get() = db.rawOcrDao()
    private val analytics get() = db.analyticsDao()

    fun observeList(): Flow<List<ReceiptListItem>> = receipts.observeList()
    fun observeCount(): Flow<Int> = receipts.observeCount()
    fun observeReceipt(id: Long): Flow<Receipt?> = receipts.observeById(id)
    fun observeStores(): Flow<List<Store>> = stores.observeAll()
    fun observeCompanies(): Flow<List<Company>> = companies.observeAll()

    /** Global search across merchant/company/tax id/receipt #/product/barcode/notes. */
    suspend fun search(query: String): List<ReceiptListItem> {
        val q = query.trim()
        if (q.isEmpty()) return receipts.listAll()
        return receipts.search(q)
    }

    suspend fun count(): Int = receipts.count()
    suspend fun getStores(): List<Store> = stores.getAll()
    suspend fun getCompanies(): List<Company> = companies.getAll()

    suspend fun getDetail(id: Long): ReceiptDetail? {
        val receipt = receipts.getById(id) ?: return null
        val store = receipt.storeId?.let { stores.getById(it) }
        val company = store?.companyId?.let { companies.getById(it) }
        val original = receipt.originalReceiptId?.let { receipts.getById(it) }
        return ReceiptDetail(
            receipt = receipt,
            store = store,
            company = company,
            lineItems = lines.forReceipt(id),
            discounts = discounts.forReceipt(id),
            payments = payments.forReceipt(id),
            rawOcr = rawOcr.forReceipt(id),
            originalReceipt = original
        )
    }

    suspend fun deleteReceipt(id: Long) {
        // cascades for children; store/company kept for catalog
        receipts.deleteById(id)
    }

    suspend fun updateReceiptHeader(receipt: Receipt) {
        receipts.update(receipt)
    }

    /**
     * Persist a structured OCR parse into the relational schema.
     * Dedupes company/store/product and links refunds by receipt number when possible.
     */
    suspend fun saveFromParse(
        photoUri: String,
        parse: StructuredReceiptParse,
        category: String,
        notes: String,
        overrides: HeaderOverrides? = null
    ): Long {
        val companyId = upsertCompany(
            name = overrides?.companyName ?: parse.companyName,
            brand = parse.brand,
            taxId = parse.taxId
        )
        val storeId = upsertStore(
            companyId = companyId,
            name = overrides?.storeName ?: parse.storeName ?: parse.merchant ?: "Unknown store",
            address = overrides?.address ?: parse.address,
            phone = parse.phone,
            branch = parse.branch
        )

        val status = when {
            overrides?.status != null -> overrides.status
            parse.isRefund -> ReceiptStatus.REFUND.name
            parse.isVoid -> ReceiptStatus.VOID.name
            else -> ReceiptStatus.PURCHASE.name
        }

        var originalId: Long? = null
        val receiptNumber = overrides?.receiptNumber ?: parse.receiptNumber
        if (status == ReceiptStatus.REFUND.name && !receiptNumber.isNullOrBlank()) {
            originalId = receipts.findByReceiptNumber(receiptNumber)?.id
        } else if (!parse.originalReceiptNumber.isNullOrBlank()) {
            originalId = receipts.findByReceiptNumber(parse.originalReceiptNumber)?.id
        }

        val merchant = overrides?.merchant
            ?: parse.merchant
            ?: parse.storeName
            ?: parse.companyName
            ?: "Unknown"

        val receiptId = receipts.insert(
            Receipt(
                storeId = storeId,
                photoUri = photoUri,
                datetime = overrides?.datetime ?: parse.datetimeMillis ?: System.currentTimeMillis(),
                receiptNumber = receiptNumber,
                cashier = parse.cashier,
                registerId = parse.registerId,
                currency = overrides?.currency ?: parse.currency,
                subtotal = overrides?.subtotal ?: parse.subtotal,
                tax = overrides?.tax ?: parse.tax,
                total = overrides?.total ?: parse.total ?: guessTotal(parse),
                status = status,
                originalReceiptId = originalId,
                category = category,
                notes = notes,
                merchantDisplay = merchant,
                parseConfidence = parse.confidence
            )
        )

        rawOcr.insert(
            RawOcrText(receiptId = receiptId, rawText = parse.rawText, parserVersion = parse.parserVersion)
        )

        val lineIds = mutableListOf<Long>()
        parse.lineItems.forEachIndexed { index, item ->
            val productId = upsertProduct(storeId, item)
            val id = lines.insert(
                LineItem(
                    receiptId = receiptId,
                    productId = productId,
                    name = item.name,
                    sku = item.sku,
                    barcode = item.barcode,
                    quantity = item.quantity,
                    unit = item.unit,
                    unitPrice = item.unitPrice,
                    lineTotal = item.lineTotal,
                    taxFlag = item.taxFlag,
                    position = index
                )
            )
            lineIds.add(id)
            item.lineDiscount?.let { d ->
                discounts.insertAll(
                    listOf(
                        Discount(
                            receiptId = receiptId,
                            lineItemId = id,
                            description = d.description,
                            code = d.code,
                            amount = d.amount,
                            percent = d.percent
                        )
                    )
                )
            }
        }

        val headerDiscounts = parse.discounts.filter { it.lineIndex == null }.map {
            Discount(
                receiptId = receiptId,
                lineItemId = null,
                description = it.description,
                code = it.code,
                amount = it.amount,
                percent = it.percent
            )
        }
        if (headerDiscounts.isNotEmpty()) discounts.insertAll(headerDiscounts)

        val paymentRows = parse.payments.map {
            Payment(
                receiptId = receiptId,
                method = it.method,
                amount = it.amount,
                last4 = it.last4,
                authCode = it.authCode,
                changeGiven = it.changeGiven
            )
        }.ifEmpty {
            val total = overrides?.total ?: parse.total ?: 0.0
            if (total > 0) listOf(Payment(receiptId = receiptId, method = "UNKNOWN", amount = total))
            else emptyList()
        }
        if (paymentRows.isNotEmpty()) payments.insertAll(paymentRows)

        // Link prior refunds that referenced this receipt number
        if (!receiptNumber.isNullOrBlank() && status == ReceiptStatus.PURCHASE.name) {
            // no bulk update helper — leave for future; originalReceiptId set on refund save
        }

        return receiptId
    }

    suspend fun replaceLineItems(receiptId: Long, items: List<LineItem>) {
        lines.deleteForReceipt(receiptId)
        if (items.isNotEmpty()) {
            lines.insertAll(items.mapIndexed { i, it -> it.copy(id = 0, receiptId = receiptId, position = i) })
        }
    }

    private suspend fun upsertCompany(name: String?, brand: String?, taxId: String?): Long? {
        if (name.isNullOrBlank() && taxId.isNullOrBlank()) return null
        taxId?.takeIf { it.isNotBlank() }?.let { companies.findByTaxId(it) }?.let { existing ->
            val merged = existing.copy(
                brand = existing.brand ?: brand,
                legalName = if (existing.legalName.isBlank() && !name.isNullOrBlank()) name else existing.legalName
            )
            if (merged != existing) companies.update(merged)
            return existing.id
        }
        name?.takeIf { it.isNotBlank() }?.let { companies.findByName(it) }?.let { existing ->
            val merged = existing.copy(
                taxId = existing.taxId ?: taxId,
                brand = existing.brand ?: brand
            )
            if (merged != existing) companies.update(merged)
            return existing.id
        }
        val legal = name?.takeIf { it.isNotBlank() } ?: return null
        return companies.insert(Company(legalName = legal, brand = brand, taxId = taxId))
    }

    private suspend fun upsertStore(
        companyId: Long?,
        name: String,
        address: String?,
        phone: String?,
        branch: String?
    ): Long {
        stores.findByNameAddress(name, address)?.let { existing ->
            val merged = existing.copy(
                companyId = existing.companyId ?: companyId,
                phone = existing.phone ?: phone,
                branch = existing.branch ?: branch
            )
            if (merged != existing) stores.update(merged)
            return existing.id
        }
        return stores.insert(
            Store(companyId = companyId, name = name, address = address, phone = phone, branch = branch)
        )
    }

    private suspend fun upsertProduct(storeId: Long?, item: ParsedLineItem): Long? {
        val barcode = item.barcode?.takeIf { it.isNotBlank() }
        val sku = item.sku?.takeIf { it.isNotBlank() }
        if (barcode != null) {
            products.findByBarcode(barcode)?.let { return it.id }
        }
        if (sku != null) {
            products.findBySku(sku, storeId)?.let { return it.id }
        }
        val normalized = normalizeName(item.name)
        products.findByNormalizedName(normalized, storeId)?.let { return it.id }
        if (item.name.isBlank()) return null
        return products.insert(
            Product(
                storeId = storeId,
                name = item.name,
                normalizedName = normalized,
                sku = sku,
                barcode = barcode
            )
        )
    }

    private fun normalizeName(name: String): String =
        name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun guessTotal(parse: StructuredReceiptParse): Double {
        parse.total?.let { return it }
        val linesSum = parse.lineItems.sumOf { it.lineTotal }
        if (linesSum > 0) return linesSum
        return parse.payments.sumOf { it.amount }
    }

    suspend fun buildAnalytics(
        from: Long,
        to: Long,
        storeId: Long? = null,
        companyId: Long? = null,
        status: String? = null,
        category: String? = null,
        paymentMethod: String? = null,
        period: String = "day"
    ): AnalyticsSummary {
        val byStore = analytics.spendByStore(from, to, storeId, companyId, status, category)
        val count = analytics.filteredCount(from, to, storeId, companyId, status, category)
        val spend = analytics.filteredSpend(from, to, storeId, companyId, status, category)
        val refunds = analytics.refundCount(from, to, storeId, companyId)
        val discountTotal = abs(discounts.totalInRange(from, to))
        val byProduct = lines.productSpend(from, to, storeId, 25)
        var byPayment = payments.spendByMethod(from, to)
        if (paymentMethod != null) {
            byPayment = byPayment.filter { it.method.equals(paymentMethod, ignoreCase = true) }
        }
        val receiptRows = analytics.filteredReceipts(from, to, storeId, companyId, status, category)
        val byPeriod = aggregatePeriod(receiptRows, period)
        val topStoreId = byStore.firstOrNull()?.storeId?.takeIf { it > 0 }
        val topProductsAtTopStore = if (topStoreId != null) {
            lines.productSpend(from, to, topStoreId, 10)
        } else emptyList()

        val barcodePoints = analytics.priceHistoryByBarcode(from, to, storeId)
        val barcodePriceHistory = barcodePoints
            .groupBy { it.barcode }
            .map { (barcode, pts) ->
                val prices = pts.map { it.unitPrice }
                BarcodePriceHistory(
                    barcode = barcode,
                    name = pts.last().name,
                    points = pts,
                    minPrice = prices.minOrNull() ?: 0.0,
                    maxPrice = prices.maxOrNull() ?: 0.0,
                    lastPrice = pts.last().unitPrice,
                    sampleCount = pts.size
                )
            }
            .sortedByDescending { it.sampleCount }
            .take(40)

        val purchaseCount = receiptRows.count { it.status == ReceiptStatus.PURCHASE.name }
        val avg = if (purchaseCount > 0) {
            receiptRows.filter { it.status == ReceiptStatus.PURCHASE.name }.sumOf { it.total } / purchaseCount
        } else 0.0
        val discountRate = if (spend > 0) discountTotal / (spend + discountTotal) else 0.0

        val displayCurrency = receiptRows.groupingBy { it.currency }.eachCount()
            .maxByOrNull { it.value }?.key ?: "ILS"

        return AnalyticsSummary(
            receiptCount = count,
            totalSpend = spend,
            avgBasket = avg,
            refundCount = refunds,
            discountTotal = discountTotal,
            discountRate = discountRate,
            byStore = byStore,
            byProduct = byProduct,
            byPeriod = byPeriod,
            byPayment = byPayment,
            topProductsAtTopStore = topProductsAtTopStore,
            barcodePriceHistory = barcodePriceHistory,
            displayCurrency = displayCurrency
        )
    }

    private fun aggregatePeriod(receipts: List<Receipt>, period: String): List<PeriodSpend> {
        val fmt = when (period) {
            "week" -> SimpleDateFormat("yyyy-'W'ww", Locale.US)
            "month" -> SimpleDateFormat("yyyy-MM", Locale.US)
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }
        if (period == "week") {
            val cal = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
            }
            fmt.calendar = cal
        }
        return receipts
            .groupBy { fmt.format(Date(it.datetime)) }
            .map { (key, rows) ->
                PeriodSpend(
                    periodKey = key,
                    totalSpend = rows.sumOf {
                        when (it.status) {
                            ReceiptStatus.REFUND.name -> -it.total
                            ReceiptStatus.VOID.name -> 0.0
                            else -> it.total
                        }
                    },
                    receiptCount = rows.size
                )
            }
            .sortedBy { it.periodKey }
    }

    suspend fun exportBundle(from: Long, to: Long): ExportBundle {
        val receiptList = receipts.getInRange(from, to)
        val details = receiptList.mapNotNull { getDetail(it.id) }
        return ExportBundle(
            companies = companies.getAll(),
            stores = stores.getAll(),
            products = products.getAll(),
            details = details
        )
    }
}

data class HeaderOverrides(
    val merchant: String? = null,
    val companyName: String? = null,
    val storeName: String? = null,
    val address: String? = null,
    val receiptNumber: String? = null,
    val datetime: Long? = null,
    val currency: String? = null,
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val status: String? = null
)

data class ExportBundle(
    val companies: List<Company>,
    val stores: List<Store>,
    val products: List<Product>,
    val details: List<ReceiptDetail>
)
