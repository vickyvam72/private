package com.lumisignal.idxscreener.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumisignal.idxscreener.model.SignalStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "signals", indices = [Index(value = ["ticker", "setup", "referenceTradingDate"])])
data class SignalEntity(
    @PrimaryKey val uuid: String,
    val ticker: String,
    val companyName: String,
    val setup: String,
    val signalDate: Long,
    val signalTimestamp: Long,
    val referenceTradingDate: Long,
    val referenceClose: Double,
    val entryLow: Int,
    val entryHigh: Int,
    val takeProfit: Int,
    val stopLoss: Int,
    val riskReward: Double,
    val brokerScore: Double?,
    val volumeScore: Double,
    val anomalyScore: Double,
    val technicalScore: Double,
    val distributionScore: Double,
    val chasingRisk: Double,
    val finalScore: Double,
    val reasons: String,
    val telegramSent: Boolean = false,
    val telegramMessageId: String? = null,
    val auditTelegramSent: Boolean = false,
    val status: SignalStatus = SignalStatus.WAITING_ENTRY,
    val entryTriggeredDate: Long? = null,
    val entryTriggeredPrice: Double? = null,
    val auditDate: Long? = null,
    val exitDate: Long? = null,
    val exitPrice: Double? = null,
    val result: String? = null,
    val gainLoss: Double? = null,
    val scoringMode: String = "FLOW_AWARE",
    @ColumnInfo(defaultValue = "'1.12.0'") val strategyVersion: String = "1.12.0",
    val evidenceJson: String? = null,
    val supersededBy: String? = null,
    val lastAuditAt: Long? = null,
    val lastAuditSource: String? = null
)

@Entity(tableName = "screening_results", indices = [Index("batchId")])
data class ScreeningResultEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val createdAt: Long,
    val referenceDate: Long,
    val rank: Int,
    val ticker: String,
    val companyName: String,
    val setup: String,
    val score: Double,
    val candidateJson: String,
    val sourceMode: String,
    val checkedCount: Int,
    val passedLiquidityCount: Int,
    @ColumnInfo(defaultValue = "'COMPLETE'") val coverageStatus: String = "COMPLETE"
)

@Entity(tableName = "screening_runs", indices = [Index("createdAt")])
data class ScreeningRunEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val status: String = "RUNNING",
    val stage: String = "Menyiapkan screening",
    val universeDiscovered: Int = 0,
    val technicalAttempted: Int = 0,
    val technicalValid: Int = 0,
    val technicalFailed: Int = 0,
    val staleExcluded: Int = 0,
    val brokerSeedAttempted: Int = 0,
    val brokerSeedValid: Int = 0,
    val brokerSeedFailed: Int = 0,
    val detailAttempted: Int = 0,
    val detailValid: Int = 0,
    val detailFailed: Int = 0,
    val candidateCount: Int = 0,
    val referenceDate: Long? = null,
    val message: String? = null
)

@Entity(tableName = "audit_results", indices = [Index("signalUuid")])
data class AuditResultEntity(
    @PrimaryKey val id: String,
    val signalUuid: String,
    val auditedAt: Long,
    val previousStatus: SignalStatus,
    val newStatus: SignalStatus,
    val referencePrice: Double?,
    val gainLoss: Double?,
    val detail: String
)

@Entity(tableName = "ticker_cache")
data class TickerCacheEntity(
    @PrimaryKey val ticker: String,
    val companyName: String,
    val source: String,
    val lastUpdated: Long,
    val isActive: Boolean = true,
    val averageVolume10d: Double? = null,
    val estimatedAverageValue10d: Double? = null,
    val liquidityRank: Int? = null
)

@Entity(tableName = "broker_cache")
data class BrokerCacheEntity(
    @PrimaryKey val cacheKey: String,
    val ticker: String,
    val period: String,
    val payload: String,
    val lastUpdated: Long,
    val expiresAt: Long
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "activity_logs", indices = [Index("timestamp")])
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val level: String,
    val event: String,
    val detail: String? = null
)

@Dao
interface SignalDao {
    @Query("SELECT * FROM signals ORDER BY signalTimestamp DESC") fun observeAll(): Flow<List<SignalEntity>>
    @Query("SELECT * FROM signals ORDER BY signalTimestamp DESC") suspend fun all(): List<SignalEntity>
    @Query("SELECT * FROM signals WHERE uuid=:uuid") suspend fun get(uuid: String): SignalEntity?
    @Query("SELECT * FROM signals WHERE status IN ('WAITING_ENTRY','HOLDING') ORDER BY signalTimestamp") suspend fun auditable(): List<SignalEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(signal: SignalEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertImported(signals: List<SignalEntity>): List<Long>
    @Update suspend fun update(signal: SignalEntity)
    @Query("UPDATE signals SET status='SUPERSEDED', supersededBy=:replacementUuid WHERE ticker=:ticker AND setup=:setup AND referenceTradingDate<:referenceDate AND telegramSent=0 AND status='WAITING_ENTRY'")
    suspend fun supersedeOlder(ticker: String, setup: String, referenceDate: Long, replacementUuid: String): Int
    @Query("UPDATE signals SET lastAuditAt=:auditedAt, lastAuditSource=:source WHERE uuid=:uuid")
    suspend fun markAudited(uuid: String, auditedAt: Long, source: String)
    @Query("UPDATE signals SET telegramSent=1, telegramMessageId=:messageId WHERE uuid=:uuid AND telegramSent=0") suspend fun markTelegramSent(uuid: String, messageId: String?): Int
    @Query("UPDATE signals SET status=:newStatus, entryTriggeredDate=COALESCE(entryTriggeredDate,:entryDate), entryTriggeredPrice=COALESCE(entryTriggeredPrice,:entryPrice), auditDate=:auditDate, exitDate=:exitDate, exitPrice=:exitPrice, result=:result, gainLoss=:gainLoss WHERE uuid=:uuid AND status=:expectedStatus")
    suspend fun transition(uuid: String, expectedStatus: SignalStatus, newStatus: SignalStatus, entryDate: Long?, entryPrice: Double?, auditDate: Long, exitDate: Long?, exitPrice: Double?, result: String?, gainLoss: Double?): Int
    @Delete suspend fun delete(signal: SignalEntity)
}

@Dao
interface ScreeningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(results: List<ScreeningResultEntity>)
    @Query("SELECT * FROM screening_results WHERE batchId=(SELECT id FROM screening_runs WHERE status IN ('COMPLETE','PARTIAL') ORDER BY completedAt DESC LIMIT 1) ORDER BY rank") fun observeLatest(): Flow<List<ScreeningResultEntity>>
    @Query("SELECT * FROM screening_results ORDER BY createdAt DESC, rank ASC") fun observeHistory(): Flow<List<ScreeningResultEntity>>
    @Query("SELECT * FROM screening_results WHERE id=:id") suspend fun get(id: String): ScreeningResultEntity?
    @Query("SELECT * FROM screening_results WHERE batchId=(SELECT id FROM screening_runs WHERE status IN ('COMPLETE','PARTIAL') ORDER BY completedAt DESC LIMIT 1) ORDER BY setup, rank") suspend fun latestOnce(): List<ScreeningResultEntity>
}

@Dao
interface ScreeningRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(run: ScreeningRunEntity)
    @Query("SELECT * FROM screening_runs ORDER BY createdAt DESC LIMIT 1") fun observeLatest(): Flow<ScreeningRunEntity?>
    @Query("SELECT * FROM screening_runs WHERE id=:id") suspend fun get(id: String): ScreeningRunEntity?
}

@Dao
interface AuditDao { @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(result: AuditResultEntity): Long }

@Dao
interface CacheDao {
    @Query("SELECT * FROM ticker_cache WHERE isActive=1 ORDER BY estimatedAverageValue10d DESC, ticker") suspend fun tickers(): List<TickerCacheEntity>
    @Query("UPDATE ticker_cache SET isActive=0") suspend fun markAllTickersInactive()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTickers(items: List<TickerCacheEntity>)
    @Query("SELECT * FROM broker_cache WHERE cacheKey=:key AND expiresAt>:now") suspend fun broker(key: String, now: Long): BrokerCacheEntity?
    @Query("SELECT * FROM broker_cache WHERE cacheKey=:key") suspend fun brokerAny(key: String): BrokerCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBroker(item: BrokerCacheEntity)
    @Query("DELETE FROM broker_cache WHERE expiresAt<:now") suspend fun purgeExpiredBroker(now: Long): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings") fun observe(): Flow<List<SettingEntity>>
    @Query("SELECT value FROM settings WHERE key=:key") suspend fun value(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: SettingEntity)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit") fun observe(limit: Int = 100): Flow<List<ActivityLogEntity>>
    @Insert suspend fun insert(log: ActivityLogEntity)
}

@Database(
    entities = [SignalEntity::class, ScreeningResultEntity::class, ScreeningRunEntity::class, AuditResultEntity::class, TickerCacheEntity::class, BrokerCacheEntity::class, SettingEntity::class, ActivityLogEntity::class],
    version = 7,
    exportSchema = true
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao
    abstract fun screeningDao(): ScreeningDao
    abstract fun screeningRunDao(): ScreeningRunDao
    abstract fun auditDao(): AuditDao
    abstract fun cacheDao(): CacheDao
    abstract fun settingsDao(): SettingsDao
    abstract fun activityDao(): ActivityDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN scoringMode TEXT NOT NULL DEFAULT 'FLOW_AWARE'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ticker_cache ADD COLUMN averageVolume10d REAL")
                db.execSQL("ALTER TABLE ticker_cache ADD COLUMN estimatedAverageValue10d REAL")
                db.execSQL("ALTER TABLE ticker_cache ADD COLUMN liquidityRank INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN entryTriggeredPrice REAL")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN auditTelegramSent INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN strategyVersion TEXT NOT NULL DEFAULT '1.12.0'")
                db.execSQL("ALTER TABLE signals ADD COLUMN evidenceJson TEXT")
                db.execSQL("ALTER TABLE signals ADD COLUMN supersededBy TEXT")
                db.execSQL("ALTER TABLE signals ADD COLUMN lastAuditAt INTEGER")
                db.execSQL("ALTER TABLE signals ADD COLUMN lastAuditSource TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_signals_ticker_setup_referenceTradingDate ON signals(ticker, setup, referenceTradingDate)")
                db.execSQL("ALTER TABLE screening_results ADD COLUMN coverageStatus TEXT NOT NULL DEFAULT 'COMPLETE'")
                db.execSQL("CREATE TABLE IF NOT EXISTS screening_runs (id TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, completedAt INTEGER, status TEXT NOT NULL, stage TEXT NOT NULL, universeDiscovered INTEGER NOT NULL, technicalAttempted INTEGER NOT NULL, technicalValid INTEGER NOT NULL, technicalFailed INTEGER NOT NULL, staleExcluded INTEGER NOT NULL, brokerSeedAttempted INTEGER NOT NULL, brokerSeedValid INTEGER NOT NULL, brokerSeedFailed INTEGER NOT NULL, detailAttempted INTEGER NOT NULL, detailValid INTEGER NOT NULL, detailFailed INTEGER NOT NULL, candidateCount INTEGER NOT NULL, referenceDate INTEGER, message TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screening_runs_createdAt ON screening_runs(createdAt)")
                db.execSQL("INSERT OR IGNORE INTO screening_runs (id,createdAt,updatedAt,completedAt,status,stage,universeDiscovered,technicalAttempted,technicalValid,technicalFailed,staleExcluded,brokerSeedAttempted,brokerSeedValid,brokerSeedFailed,detailAttempted,detailValid,detailFailed,candidateCount,referenceDate,message) SELECT batchId,MAX(createdAt),MAX(createdAt),MAX(createdAt),'COMPLETE','Hasil versi sebelumnya',MAX(checkedCount),MAX(checkedCount),MAX(checkedCount),0,0,MAX(passedLiquidityCount),MAX(passedLiquidityCount),0,MAX(passedLiquidityCount),MAX(passedLiquidityCount),0,COUNT(*),MAX(referenceDate),'Dimigrasikan dari Lumi 1.11.0' FROM screening_results GROUP BY batchId ORDER BY MAX(createdAt) DESC LIMIT 1")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Results and successful FLOW_V9 cache entries may contain the invalid
                // date+1 broker window from 1.12.2. Do not display or reuse them.
                db.execSQL("DELETE FROM screening_results")
                db.execSQL("DELETE FROM screening_runs")
                db.execSQL("DELETE FROM broker_cache WHERE cacheKey LIKE '%FLOW_V9_%'")
            }
        }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "lumi_signal.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { instance = it }
        }
    }
}

class DbConverters {
    @TypeConverter fun statusToString(value: SignalStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): SignalStatus = SignalStatus.valueOf(value)
}
