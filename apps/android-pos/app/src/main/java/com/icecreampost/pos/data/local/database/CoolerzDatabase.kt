package com.icecreampost.pos.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.icecreampost.pos.data.local.dao.CategoryDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.CategoryEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.StallEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.dao.BusinessDayDao

@Database(
    entities = [
        StallEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        InventoryLedgerEntity::class,
        TransactionEntity::class,
        TransactionItemEntity::class,
        SyncStateEntity::class,
        AppSessionEntity::class,
        BusinessDayEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class CoolerzDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun inventoryLedgerDao(): InventoryLedgerDao
    abstract fun productDao(): ProductDao
    abstract fun transactionDao(): TransactionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun sessionDao(): SessionDao
    abstract fun businessDayDao(): BusinessDayDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN stallId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN categoryId TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN sku TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN unit TEXT NOT NULL DEFAULT 'scoop'")
                db.execSQL("ALTER TABLE products ADD COLUMN costPriceCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE products ADD COLUMN lowStockThreshold REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE products ADD COLUMN packSize REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE products ADD COLUMN conversionRate REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE products ADD COLUMN isSellable INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE products ADD COLUMN localUpdatedAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN stallId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN deviceId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN cashierId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN receiptNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN subtotalCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN cashReceivedCents INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN changeAmountCents INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN occurredAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN updatedAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN localCreatedAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN lastSyncAttemptAt TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAt TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS stalls (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, code TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS product_categories (id TEXT NOT NULL PRIMARY KEY, stallId TEXT NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS inventory_ledger (id TEXT NOT NULL PRIMARY KEY, stallId TEXT NOT NULL, productId TEXT NOT NULL, quantityDelta REAL NOT NULL, movementType TEXT NOT NULL, reason TEXT, referenceId TEXT, occurredAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transaction_items (id TEXT NOT NULL PRIMARY KEY, transactionId TEXT NOT NULL, productId TEXT, productName TEXT NOT NULL, quantity REAL NOT NULL, unitPriceCents INTEGER NOT NULL, lineTotalCents INTEGER NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_session (id TEXT NOT NULL PRIMARY KEY, userId TEXT, stallId TEXT, displayName TEXT NOT NULL, role TEXT NOT NULL, sessionToken TEXT, expiresAt TEXT, isActivated INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN cursorUpdatedAt TEXT")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN lastSyncAt TEXT")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN status TEXT NOT NULL DEFAULT 'idle'")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN errorMessage TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_session ADD COLUMN deviceId TEXT")
                // Version 2 activation was local-only. Require one verified
                // server activation after upgrade so the device ID is trusted.
                db.execSQL("UPDATE app_session SET isActivated = 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS business_days (id TEXT NOT NULL PRIMARY KEY, stallId TEXT NOT NULL, deviceId TEXT NOT NULL, cashierId TEXT NOT NULL, businessDate TEXT NOT NULL, openedAt TEXT NOT NULL, openingNotes TEXT, closedAt TEXT, closingCashCents INTEGER, closingNotes TEXT, updatedAt TEXT NOT NULL, isSynced INTEGER NOT NULL DEFAULT 0, syncError TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_business_days_stallId_businessDate ON business_days (stallId, businessDate)")
            }
        }
    }
}
