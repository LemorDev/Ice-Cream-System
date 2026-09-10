package com.icecreampost.pos.di

import android.content.Context
import androidx.room.Room
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CoolerzDatabase =
        Room.databaseBuilder(context, CoolerzDatabase::class.java, "coolerz-pos.db")
            .addMigrations(CoolerzDatabase.MIGRATION_1_2, CoolerzDatabase.MIGRATION_2_3, CoolerzDatabase.MIGRATION_3_4)
            .build()

    @Provides fun provideProductDao(database: CoolerzDatabase): ProductDao = database.productDao()
    @Provides fun provideInventoryLedgerDao(database: CoolerzDatabase): InventoryLedgerDao = database.inventoryLedgerDao()
    @Provides fun provideTransactionDao(database: CoolerzDatabase): TransactionDao = database.transactionDao()
    @Provides fun provideSyncStateDao(database: CoolerzDatabase): SyncStateDao = database.syncStateDao()
    @Provides fun provideSessionDao(database: CoolerzDatabase): SessionDao = database.sessionDao()
    @Provides fun provideBusinessDayDao(database: CoolerzDatabase): BusinessDayDao = database.businessDayDao()
    @Provides fun provideProductRecipeDao(database: CoolerzDatabase): ProductRecipeDao = database.productRecipeDao()
    @Provides fun provideDailyStoreClosingDao(database: CoolerzDatabase): DailyStoreClosingDao = database.dailyStoreClosingDao()
}
