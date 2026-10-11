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
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
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
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observe(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM professional_profiles WHERE userId = :userId ORDER BY businessName")
    fun observeForUser(userId: String): Flow<List<ProfileEntity>>

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
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
)

/**
 * Versión interna append-only de una Cotización: cada guardado inserta una
 * versión con el snapshot completo (cabecera, Cliente, Perfil, líneas,
 * retenciones aplicadas y totales). La versión vigente al emitirse queda
 * como histórico inmutable del documento emitido.
 */
@Entity(
    tableName = "quote_versions",
    indices = [Index("quoteId"), Index(value = ["quoteId", "version"], unique = true)],
)
data class QuoteVersionEntity(
    @PrimaryKey val id: String,
    val quoteId: String,
    val version: Int,
    val snapshotJson: String,
    val createdAt: Long,
)

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    fun observeOne(id: String): Flow<ClientEntity?>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun get(id: String): ClientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(client: ClientEntity)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services WHERE profileId = :profileId ORDER BY name")
    fun observeForProfile(profileId: String): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE id = :id")
    suspend fun get(id: String): ServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(service: ServiceEntity)
}

@Dao
interface RetentionDao {
    @Query("SELECT * FROM retention_configs WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<RetentionConfigEntity>>

    @Query("SELECT * FROM retention_configs WHERE id = :id")
    suspend fun get(id: String): RetentionConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: RetentionConfigEntity)
}

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE id = :id")
    fun observeOne(id: String): Flow<QuoteEntity?>

    @Query("SELECT * FROM quotes WHERE id = :id")
    suspend fun get(id: String): QuoteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM quotes WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quote: QuoteEntity)
}

@Dao
interface QuoteVersionDao {
    @Query("SELECT * FROM quote_versions WHERE quoteId = :quoteId ORDER BY version")
    fun observeForQuote(quoteId: String): Flow<List<QuoteVersionEntity>>

    @Query("SELECT * FROM quote_versions WHERE quoteId = :quoteId ORDER BY version DESC LIMIT 1")
    suspend fun getLatest(quoteId: String): QuoteVersionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: QuoteVersionEntity)
}

@Database(
    entities = [
        UserEntity::class, ProfileEntity::class, ClientEntity::class,
        ServiceEntity::class, RetentionConfigEntity::class,
        QuoteEntity::class, QuoteVersionEntity::class,
    ],
    version = 2,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cotixgo.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
