package com.receiptbox.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Company::class,
        Store::class,
        Receipt::class,
        Product::class,
        LineItem::class,
        Discount::class,
        Payment::class,
        RawOcrText::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun companyDao(): CompanyDao
    abstract fun storeDao(): StoreDao
    abstract fun productDao(): ProductDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun lineItemDao(): LineItemDao
    abstract fun discountDao(): DiscountDao
    abstract fun paymentDao(): PaymentDao
    abstract fun rawOcrDao(): RawOcrDao
    abstract fun analyticsDao(): AnalyticsDao

    companion object {
        @Volatile private var INSTANCE: ReceiptDatabase? = null

        fun get(context: Context): ReceiptDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReceiptDatabase::class.java,
                    "receiptbox.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
