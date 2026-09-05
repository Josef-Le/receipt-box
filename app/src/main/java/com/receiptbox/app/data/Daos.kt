package com.receiptbox.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(company: Company): Long

    @Query("SELECT * FROM companies WHERE id = :id")
    suspend fun getById(id: Long): Company?

    @Query("SELECT * FROM companies WHERE lower(legalName) = lower(:name) LIMIT 1")
    suspend fun findByName(name: String): Company?

    @Query("SELECT * FROM companies WHERE taxId = :taxId LIMIT 1")
    suspend fun findByTaxId(taxId: String): Company?

    @Query("SELECT * FROM companies ORDER BY legalName ASC")
    fun observeAll(): Flow<List<Company>>

    @Query("SELECT * FROM companies ORDER BY legalName ASC")
    suspend fun getAll(): List<Company>
}

@Dao
interface StoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(store: Store): Long

    @Update
    suspend fun update(store: Store)

    @Query("SELECT * FROM stores WHERE id = :id")
    suspend fun getById(id: Long): Store?

    @Query(
        """
        SELECT * FROM stores
        WHERE lower(name) = lower(:name)
          AND ((:address IS NULL AND address IS NULL) OR lower(ifnull(address,'')) = lower(ifnull(:address,'')))
        LIMIT 1
        """
    )
    suspend fun findByNameAddress(name: String, address: String?): Store?

    @Query("SELECT * FROM stores ORDER BY name ASC")
    fun observeAll(): Flow<List<Store>>

    @Query("SELECT * FROM stores ORDER BY name ASC")
    suspend fun getAll(): List<Store>
}

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: Product): Long

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): Product?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE sku = :sku AND (storeId = :storeId OR :storeId IS NULL) LIMIT 1")
    suspend fun findBySku(sku: String, storeId: Long?): Product?

    @Query(
        """
        SELECT * FROM products
        WHERE normalizedName = :normalized AND (storeId IS :storeId OR (:storeId IS NULL AND storeId IS NULL))
        LIMIT 1
        """
    )
    suspend fun findByNormalizedName(normalized: String, storeId: Long?): Product?

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<Product>
}

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: Receipt): Long

    @Update
    suspend fun update(receipt: Receipt)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getById(id: Long): Receipt?

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observeById(id: Long): Flow<Receipt?>

    @Query("SELECT COUNT(*) FROM receipts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM receipts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM receipts WHERE receiptNumber = :number AND id != :excludeId LIMIT 1")
    suspend fun findByReceiptNumber(number: String, excludeId: Long = -1): Receipt?

    @Query(
        """
        SELECT r.id, r.merchantDisplay, r.total, r.currency, r.datetime, r.category, r.status,
               s.name AS storeName, c.legalName AS companyName,
               (SELECT p.method FROM payments p WHERE p.receiptId = r.id LIMIT 1) AS paymentMethod
        FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        LEFT JOIN companies c ON c.id = s.companyId
        ORDER BY r.datetime DESC, r.createdAt DESC
        """
    )
    fun observeList(): Flow<List<ReceiptListItem>>

    @Query(
        """
        SELECT r.id, r.merchantDisplay, r.total, r.currency, r.datetime, r.category, r.status,
               s.name AS storeName, c.legalName AS companyName,
               (SELECT p.method FROM payments p WHERE p.receiptId = r.id LIMIT 1) AS paymentMethod
        FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        LEFT JOIN companies c ON c.id = s.companyId
        WHERE r.datetime BETWEEN :from AND :to
        ORDER BY r.datetime ASC
        """
    )
    suspend fun listInRange(from: Long, to: Long): List<ReceiptListItem>

    @Query("SELECT * FROM receipts WHERE datetime BETWEEN :from AND :to ORDER BY datetime ASC")
    suspend fun getInRange(from: Long, to: Long): List<Receipt>
}

@Dao
interface LineItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LineItem>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LineItem): Long

    @Query("SELECT * FROM line_items WHERE receiptId = :receiptId ORDER BY position ASC")
    suspend fun forReceipt(receiptId: Long): List<LineItem>

    @Query("DELETE FROM line_items WHERE receiptId = :receiptId")
    suspend fun deleteForReceipt(receiptId: Long)

    @Query(
        """
        SELECT li.productId AS productId,
               li.name AS name,
               li.sku AS sku,
               li.barcode AS barcode,
               SUM(li.lineTotal) AS totalSpend,
               SUM(li.quantity) AS quantity,
               COUNT(DISTINCT li.receiptId) AS receiptCount
        FROM line_items li
        INNER JOIN receipts r ON r.id = li.receiptId
        WHERE r.datetime BETWEEN :from AND :to
          AND r.status = 'PURCHASE'
          AND (:storeId IS NULL OR r.storeId = :storeId)
        GROUP BY ifnull(li.productId, -1), lower(li.name), ifnull(li.sku,''), ifnull(li.barcode,'')
        ORDER BY totalSpend DESC
        LIMIT :limit
        """
    )
    suspend fun productSpend(from: Long, to: Long, storeId: Long?, limit: Int): List<ProductSpend>
}

@Dao
interface DiscountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Discount>): List<Long>

    @Query("SELECT * FROM discounts WHERE receiptId = :receiptId")
    suspend fun forReceipt(receiptId: Long): List<Discount>

    @Query("DELETE FROM discounts WHERE receiptId = :receiptId")
    suspend fun deleteForReceipt(receiptId: Long)

    @Query(
        """
        SELECT ifnull(SUM(ifnull(amount,0)),0) FROM discounts d
        INNER JOIN receipts r ON r.id = d.receiptId
        WHERE r.datetime BETWEEN :from AND :to
        """
    )
    suspend fun totalInRange(from: Long, to: Long): Double
}

@Dao
interface PaymentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Payment>): List<Long>

    @Query("SELECT * FROM payments WHERE receiptId = :receiptId")
    suspend fun forReceipt(receiptId: Long): List<Payment>

    @Query("DELETE FROM payments WHERE receiptId = :receiptId")
    suspend fun deleteForReceipt(receiptId: Long)

    @Query(
        """
        SELECT p.method AS method,
               SUM(p.amount) AS totalSpend,
               COUNT(DISTINCT p.receiptId) AS receiptCount
        FROM payments p
        INNER JOIN receipts r ON r.id = p.receiptId
        WHERE r.datetime BETWEEN :from AND :to
        GROUP BY p.method
        ORDER BY totalSpend DESC
        """
    )
    suspend fun spendByMethod(from: Long, to: Long): List<PaymentSpend>
}

@Dao
interface RawOcrDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(raw: RawOcrText): Long

    @Query("SELECT * FROM raw_ocr WHERE receiptId = :receiptId LIMIT 1")
    suspend fun forReceipt(receiptId: Long): RawOcrText?

    @Query("DELETE FROM raw_ocr WHERE receiptId = :receiptId")
    suspend fun deleteForReceipt(receiptId: Long)
}

@Dao
interface AnalyticsDao {
    @Query(
        """
        SELECT ifnull(r.storeId, -1) AS storeId,
               ifnull(s.name, r.merchantDisplay) AS storeName,
               c.legalName AS companyName,
               SUM(CASE WHEN r.status = 'PURCHASE' THEN r.total ELSE 0 END) AS totalSpend,
               COUNT(*) AS receiptCount,
               AVG(CASE WHEN r.status = 'PURCHASE' THEN r.total ELSE NULL END) AS avgBasket
        FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        LEFT JOIN companies c ON c.id = s.companyId
        WHERE r.datetime BETWEEN :from AND :to
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND (:companyId IS NULL OR s.companyId = :companyId)
          AND (:status IS NULL OR r.status = :status)
          AND (:category IS NULL OR r.category = :category)
        GROUP BY ifnull(r.storeId, -1), ifnull(s.name, r.merchantDisplay), c.legalName
        ORDER BY totalSpend DESC
        """
    )
    suspend fun spendByStore(
        from: Long,
        to: Long,
        storeId: Long?,
        companyId: Long?,
        status: String?,
        category: String?
    ): List<StoreSpend>

    @Query(
        """
        SELECT COUNT(*) FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.datetime BETWEEN :from AND :to
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND (:companyId IS NULL OR s.companyId = :companyId)
          AND (:status IS NULL OR r.status = :status)
          AND (:category IS NULL OR r.category = :category)
        """
    )
    suspend fun filteredCount(
        from: Long, to: Long, storeId: Long?, companyId: Long?, status: String?, category: String?
    ): Int

    @Query(
        """
        SELECT ifnull(SUM(CASE WHEN r.status = 'PURCHASE' THEN r.total
                               WHEN r.status = 'REFUND' THEN -r.total ELSE 0 END), 0)
        FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.datetime BETWEEN :from AND :to
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND (:companyId IS NULL OR s.companyId = :companyId)
          AND (:status IS NULL OR r.status = :status)
          AND (:category IS NULL OR r.category = :category)
        """
    )
    suspend fun filteredSpend(
        from: Long, to: Long, storeId: Long?, companyId: Long?, status: String?, category: String?
    ): Double

    @Query(
        """
        SELECT COUNT(*) FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.datetime BETWEEN :from AND :to AND r.status = 'REFUND'
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND (:companyId IS NULL OR s.companyId = :companyId)
        """
    )
    suspend fun refundCount(from: Long, to: Long, storeId: Long?, companyId: Long?): Int

    @Query(
        """
        SELECT r.* FROM receipts r
        LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.datetime BETWEEN :from AND :to
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND (:companyId IS NULL OR s.companyId = :companyId)
          AND (:status IS NULL OR r.status = :status)
          AND (:category IS NULL OR r.category = :category)
        """
    )
    suspend fun filteredReceipts(
        from: Long, to: Long, storeId: Long?, companyId: Long?, status: String?, category: String?
    ): List<Receipt>

    @Query(
        """
        SELECT li.barcode AS barcode,
               li.name AS name,
               r.datetime AS datetime,
               li.unitPrice AS unitPrice,
               li.lineTotal AS lineTotal,
               ifnull(s.name, r.merchantDisplay) AS storeName,
               r.currency AS currency
        FROM line_items li
        INNER JOIN receipts r ON r.id = li.receiptId
        LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.datetime BETWEEN :from AND :to
          AND r.status = 'PURCHASE'
          AND li.barcode IS NOT NULL
          AND length(li.barcode) >= 8
          AND (:storeId IS NULL OR r.storeId = :storeId)
        ORDER BY li.barcode ASC, r.datetime ASC
        """
    )
    suspend fun priceHistoryByBarcode(from: Long, to: Long, storeId: Long?): List<BarcodePricePoint>
}
