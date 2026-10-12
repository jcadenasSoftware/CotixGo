package com.cotixgo.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Campos de sincronización futura: id global UUIDv4 + marcas UTC + pendingSync.
// createdAt/updatedAt son epoch millis de un Instant UTC (ver Domain.nowEpochMillis);
// en el límite de sync (F3) se serializarán a ISO-8601 UTC. La cola de
// operaciones se implementa en esa fase.

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mainCurrency: String? = null,
    val email: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Entity(tableName = "professional_profiles", indices = [Index("userId")])
data class ProfileEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val businessName: String,
    val description: String = "",
    val commercialInfo: String = "",
    val logoRef: String? = null,
    val vigenciaDias: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Entity(tableName = "clients", indices = [Index("userId")])
data class ClientEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val type: String,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observe(): Flow<UserEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun get(): UserEntity?

    @Query("UPDATE users SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM professional_profiles WHERE userId = :userId ORDER BY businessName")
    fun observeForUser(userId: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM professional_profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("UPDATE professional_profiles SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)
}

@Entity(tableName = "services", indices = [Index("userId"), Index("profileId")])
data class ServiceEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val profileId: String,
    val name: String,
    val description: String = "",
    val suggestedPrice: String = "",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/** Configuración de retenciones: global por Usuario/Titular (DP3-F2). */
@Entity(tableName = "retention_configs", indices = [Index("userId")])
data class RetentionConfigEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val ratePercent: String,
    val appliesTo: String = "SERVICIO",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Entity(
    tableName = "quotes",
    indices = [Index("userId"), Index("clientId"), Index("profileId"), Index(value = ["provisionalNumber"], unique = true)],
)
data class QuoteEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val profileId: String,
    val provisionalNumber: String,
    val status: String,
    val issueDateEpochDay: Long,
    val validityDays: Int,
    val expiryDateEpochDay: Long,
    val currency: String,
    val notes: String = "",
    val currentVersion: Int = 1,
    val emittedVersion: Int = 0,
    val gross: String = "0.00",
    val totalRetained: String = "0.00",
    val net: String = "0.00",
    val clientNameSnapshot: String = "",
    val archived: Boolean = false,
    val officialNumber: String? = null,
    val versionConflict: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Versión interna append-only de una Cotización: cada guardado inserta una
 * versión con el snapshot completo (cabecera, Cliente, Perfil, líneas,
 * retenciones aplicadas y totales). La versión vigente al emitirse queda
 * como histórico inmutable del documento emitido.
 */
@Entity(
    tableName = "quote_versions",
    // (quoteId, version) no es único: versiones concurrentes de otros
    // dispositivos con el mismo número se conservan como copias separadas
    // (F3·D3); serverOrder expone la secuencia autoritativa del servidor.
    indices = [Index("quoteId"), Index(value = ["quoteId", "version"])],
)
data class QuoteVersionEntity(
    @PrimaryKey val id: String,
    val quoteId: String,
    val version: Int,
    val snapshotJson: String,
    val serverOrder: Int? = null,
    val createdAt: Long,
)

// ---------- Cola durable de sincronización (F3) ----------

/**
 * Operación de sincronización pendiente en el dispositivo. La escritura de la
 * entidad y su operación se insertan en la misma transacción Room; las
 * operaciones nunca se descartan (estados: PENDING/RETRY/PAUSED/DONE).
 * `operation_id` (opId) + payload inmutable son la clave de idempotencia;
 * `baseServerVersion` es control de concurrencia, un mecanismo distinto.
 */
@Entity(tableName = "sync_operations", indices = [Index("entityType", "entityId"), Index("state")])
data class SyncOperationEntity(
    @PrimaryKey val opId: String,
    val seq: Long = 0,
    val entityType: String,
    val entityId: String,
    val opType: String = "upsert",
    val baseServerVersion: Int = 0,
    val payloadJson: String,
    val supersedesOpId: String? = null,
    val state: String = "PENDING",
    val resultJson: String? = null,
    val resultCode: String? = null,
    val deviceId: String = "",
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Copia remota preservada cuando un pull detecta un cambio sobre una entidad
 * con operaciones locales pendientes. Ambas copias se conservan hasta que el
 * usuario resuelve el conflicto (regla aprobada: sin sobrescritura silenciosa).
 */
@Entity(tableName = "sync_conflicts", indices = [Index("resolved")])
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val remoteJson: String,
    val remoteServerVersion: Int,
    val detectedAt: Long,
    val resolved: Boolean = false,
)

/** Par clave-valor de metadatos de sincronización (cursor, device_id, cuenta). */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SyncDao {
    @Insert
    suspend fun insertOp(op: SyncOperationEntity): Long

    @Insert
    suspend fun insertOps(ops: List<SyncOperationEntity>)

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM sync_operations")
    suspend fun nextSeq(): Long

    @Insert
    suspend fun insertConflict(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_operations WHERE state IN ('PENDING','RETRY') ORDER BY seq ASC")
    suspend fun pendingOps(): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE state = 'PAUSED' ORDER BY seq ASC")
    fun observePaused(): Flow<List<SyncOperationEntity>>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state IN ('PENDING','RETRY')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state IN ('PENDING','RETRY')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state = 'PAUSED'")
    fun observePausedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE entityType = :type AND entityId = :id AND state IN ('PENDING','RETRY','PAUSED')")
    suspend fun openOpsForEntity(type: String, id: String): Int

    @Query("SELECT * FROM sync_operations WHERE entityType = :type AND entityId = :id AND state IN ('PENDING','RETRY','PAUSED') ORDER BY seq ASC")
    suspend fun pendingOpsForEntity(type: String, id: String): List<SyncOperationEntity>

    @Query("UPDATE sync_operations SET state = :state, resultJson = :resultJson, resultCode = :resultCode, attempts = attempts + 1, updatedAt = :now WHERE opId = :opId")
    suspend fun updateOpResult(opId: String, state: String, resultJson: String?, resultCode: String?, now: Long)

    @Query("UPDATE sync_operations SET state = :state, updatedAt = :now WHERE opId = :opId")
    suspend fun updateOpState(opId: String, state: String, now: Long)

    @Query("UPDATE sync_operations SET baseServerVersion = :base, updatedAt = :now WHERE entityType = :type AND entityId = :id AND state IN ('PENDING','RETRY')")
    suspend fun rebasePendingOps(type: String, id: String, base: Int, now: Long)

    @Query("SELECT * FROM sync_conflicts WHERE resolved = 0 ORDER BY detectedAt ASC")
    fun observeConflicts(): Flow<List<SyncConflictEntity>>

    @Query("UPDATE sync_conflicts SET resolved = 1 WHERE id = :id")
    suspend fun resolveConflict(id: Long)

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("INSERT OR REPLACE INTO sync_meta (`key`, value) VALUES (:key, :value)")
    suspend fun putMeta(key: String, value: String)
}

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    fun observeOne(id: String): Flow<ClientEntity?>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun get(id: String): ClientEntity?

    @Query("UPDATE clients SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(client: ClientEntity)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services WHERE profileId = :profileId ORDER BY name")
    fun observeForProfile(profileId: String): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE id = :id")
    suspend fun get(id: String): ServiceEntity?

    @Query("UPDATE services SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(service: ServiceEntity)
}

@Dao
interface RetentionDao {
    @Query("SELECT * FROM retention_configs WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<RetentionConfigEntity>>

    @Query("SELECT * FROM retention_configs WHERE id = :id")
    suspend fun get(id: String): RetentionConfigEntity?

    @Query("UPDATE retention_configs SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: RetentionConfigEntity)
}

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE userId = :userId AND versionConflict = 1")
    fun observeVersionConflicts(userId: String): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE id = :id")
    fun observeOne(id: String): Flow<QuoteEntity?>

    @Query("SELECT * FROM quotes WHERE id = :id")
    suspend fun get(id: String): QuoteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM quotes WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("UPDATE quotes SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE quotes SET officialNumber = :num, versionConflict = :conflict, updatedAt = :now WHERE id = :id")
    suspend fun updateSyncFields(id: String, num: String?, conflict: Boolean, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quote: QuoteEntity)
}

@Dao
interface QuoteVersionDao {
    @Query("SELECT * FROM quote_versions WHERE quoteId = :quoteId ORDER BY version")
    fun observeForQuote(quoteId: String): Flow<List<QuoteVersionEntity>>

    @Query("SELECT * FROM quote_versions WHERE quoteId = :quoteId ORDER BY version DESC LIMIT 1")
    suspend fun getLatest(quoteId: String): QuoteVersionEntity?

    @Query("SELECT * FROM quote_versions WHERE id = :id")
    suspend fun get(id: String): QuoteVersionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: QuoteVersionEntity)
}

@Database(
    entities = [
        UserEntity::class, ProfileEntity::class, ClientEntity::class,
        ServiceEntity::class, RetentionConfigEntity::class,
        QuoteEntity::class, QuoteVersionEntity::class,
        SyncOperationEntity::class, SyncConflictEntity::class,
        SyncMetaEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun profileDao(): ProfileDao
    abstract fun clientDao(): ClientDao
    abstract fun serviceDao(): ServiceDao
    abstract fun retentionDao(): RetentionDao
    abstract fun quoteDao(): QuoteDao
    abstract fun quoteVersionDao(): QuoteVersionDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Migración v1→v2: tablas de F2 + moneda del usuario + vigencia del Perfil. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN mainCurrency TEXT")
                db.execSQL("ALTER TABLE professional_profiles ADD COLUMN vigenciaDias INTEGER")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `services` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL, `suggestedPrice` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_services_userId` ON `services` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_services_profileId` ON `services` (`profileId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `retention_configs` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL, `ratePercent` TEXT NOT NULL,
                        `appliesTo` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_retention_configs_userId` ON `retention_configs` (`userId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `quotes` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `profileId` TEXT NOT NULL,
                        `provisionalNumber` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `issueDateEpochDay` INTEGER NOT NULL, `validityDays` INTEGER NOT NULL,
                        `expiryDateEpochDay` INTEGER NOT NULL, `currency` TEXT NOT NULL,
                        `notes` TEXT NOT NULL, `currentVersion` INTEGER NOT NULL,
                        `emittedVersion` INTEGER NOT NULL, `gross` TEXT NOT NULL,
                        `totalRetained` TEXT NOT NULL, `net` TEXT NOT NULL,
                        `clientNameSnapshot` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_userId` ON `quotes` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_clientId` ON `quotes` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_profileId` ON `quotes` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quotes_provisionalNumber` ON `quotes` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `quote_versions` (
                        `id` TEXT NOT NULL PRIMARY KEY, `quoteId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quote_versions_quoteId` ON `quote_versions` (`quoteId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quote_versions_quoteId_version` ON `quote_versions` (`quoteId`, `version`)")
            }
        }

        /** Migración v2→v3 (F3): cola durable, conflictos, metadatos y trazabilidad del servidor. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_operations` (
                        `opId` TEXT NOT NULL PRIMARY KEY,
                        `seq` INTEGER NOT NULL DEFAULT 0,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `opType` TEXT NOT NULL DEFAULT 'upsert',
                        `baseServerVersion` INTEGER NOT NULL DEFAULT 0,
                        `payloadJson` TEXT NOT NULL,
                        `supersedesOpId` TEXT,
                        `state` TEXT NOT NULL DEFAULT 'PENDING',
                        `resultJson` TEXT,
                        `resultCode` TEXT,
                        `deviceId` TEXT NOT NULL DEFAULT '',
                        `attempts` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_operations_entityType_entityId` ON `sync_operations` (`entityType`, `entityId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_operations_state` ON `sync_operations` (`state`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `remoteJson` TEXT NOT NULL,
                        `remoteServerVersion` INTEGER NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        `resolved` INTEGER NOT NULL DEFAULT 0)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflicts_resolved` ON `sync_conflicts` (`resolved`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_meta` (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        `value` TEXT NOT NULL)""",
                )
                db.execSQL("ALTER TABLE users ADD COLUMN email TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE professional_profiles ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clients ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE services ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE retention_configs ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quotes ADD COLUMN officialNumber TEXT")
                db.execSQL("ALTER TABLE quotes ADD COLUMN versionConflict INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quotes ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migración v3→v4: quote_versions admite versiones concurrentes del
         * mismo número (copia remota preservada, F3·D3) y registra el
         * server_order autoritativo fuera del snapshot.
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_quote_versions_quoteId_version`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quote_versions_quoteId_version` ON `quote_versions` (`quoteId`, `version`)")
                db.execSQL("ALTER TABLE quote_versions ADD COLUMN serverOrder INTEGER")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cotixgo.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
    }
}
