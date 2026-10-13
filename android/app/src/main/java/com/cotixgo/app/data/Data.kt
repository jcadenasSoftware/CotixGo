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

// ---------- Trabajos (F4) ----------

/**
 * Trabajo local: documento operativo de la ejecución real. Numeración
 * provisional `TR-PEND-XXXX` desde la creación; el oficial `TR-AAAAMM-NNN`
 * lo asigna el backend en la primera aceptación de PROGRAMADO/EN_PROCESO.
 * `executionAddress` es propio del Trabajo (no se deduce del Cliente) y es
 * obligatorio al pasar a PROGRAMADO; `deliveryDateEpochDay` registra el fin
 * de la ejecución al entregar.
 */
@Entity(
    tableName = "jobs",
    indices = [Index("userId"), Index("clientId"), Index("profileId"), Index(value = ["provisionalNumber"], unique = true)],
)
data class JobEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val profileId: String,
    val provisionalNumber: String,
    val status: String,
    val startDateEpochDay: Long? = null,
    val estimatedDateEpochDay: Long? = null,
    val deliveryDateEpochDay: Long? = null,
    val executionAddress: String = "",
    val notes: String = "",
    val clientNameSnapshot: String = "",
    val archived: Boolean = false,
    val officialNumber: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Vínculo Trabajo ↔ Cotización (N:N). `relationType`: ORIGEN cuando el
 * Trabajo nace de la Cotización, ADICIONAL para asociaciones posteriores.
 * Vínculos solo a cotizaciones APROBADA del mismo Cliente; la asociación
 * es manual — la aprobación nunca crea el Trabajo automáticamente.
 */
@Entity(tableName = "job_quotes", indices = [Index("jobId"), Index("quoteId")])
data class JobQuoteEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val quoteId: String,
    val relationType: String,
    val createdAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Actividad subordinada al Trabajo con estado propio
 * (PENDIENTE/EN_PROCESO/COMPLETADA) y orden de ejecución.
 */
@Entity(tableName = "job_activities", indices = [Index("jobId")])
data class JobActivityEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val description: String,
    val status: String,
    val position: Int,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Material registrado en el Trabajo en modo descriptivo (F4): sin catálogo
 * ni `material_id`, sin efecto en inventario ni movimientos. Los registros
 * descriptivos no se reinterpretan retroactivamente en fases posteriores.
 */
@Entity(tableName = "job_materials", indices = [Index("jobId")])
data class JobMaterialEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val description: String,
    val origin: String,
    val quantity: String = "",
    val unit: String = "",
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

// ---------- Informes de Trabajo (F5) ----------

/**
 * Informe local: paso opcional de "Documentar" (interno o para el Cliente).
 * Numeración provisional `INF-PEND-XXXX` desde la creación; el oficial
 * `INF-AAAAMM-NNN` lo asigna el backend en la primera aceptación de EMITIDO
 * y se conserva estable a través de correcciones/versiones.
 * `content` es el borrador editable construido desde los Trabajos asociados
 * (solo actividades COMPLETADA se presentan como ejecución realizada).
 */
@Entity(
    tableName = "reports",
    indices = [Index("userId"), Index("clientId"), Index("profileId"), Index(value = ["provisionalNumber"], unique = true)],
)
data class ReportEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val profileId: String,
    val provisionalNumber: String,
    val status: String,
    val title: String,
    val destination: String,
    val content: String = "",
    val currentVersion: Int = 0,
    val emittedVersion: Int = 0,
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
 * Vínculo Informe ↔ Trabajo (N:N). Los Trabajos asociados comparten el
 * Cliente y el Perfil Profesional del Informe (regla aprobada F5); cada
 * Trabajo conserva su identidad y trazabilidad.
 */
@Entity(tableName = "report_jobs", indices = [Index("reportId"), Index("jobId")])
data class ReportJobEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val jobId: String,
    val position: Int,
    val createdAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Snapshot inmutable de cada versión EMITIDA del Informe (análogo a
 * `quote_version`): suficiente para reproducir el documento histórico.
 * `previousVersionId` enlaza cada corrección con la versión emitida previa;
 * serverOrder expone la secuencia autoritativa del servidor.
 */
@Entity(
    tableName = "report_versions",
    indices = [Index("reportId"), Index(value = ["reportId", "version"])],
)
data class ReportVersionEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val version: Int,
    val snapshotJson: String,
    val previousVersionId: String? = null,
    val serverOrder: Int? = null,
    val createdAt: Long,
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun observeForClient(clientId: String): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observeOne(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM jobs WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("UPDATE jobs SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE jobs SET officialNumber = :num, updatedAt = :now WHERE id = :id")
    suspend fun updateOfficial(id: String, num: String?, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)
}

@Dao
interface JobQuoteDao {
    @Query("SELECT * FROM job_quotes WHERE jobId = :jobId ORDER BY createdAt")
    fun observeForJob(jobId: String): Flow<List<JobQuoteEntity>>

    @Query("SELECT * FROM job_quotes WHERE jobId = :jobId")
    suspend fun forJob(jobId: String): List<JobQuoteEntity>

    @Query("SELECT * FROM job_quotes WHERE quoteId = :quoteId")
    suspend fun forQuote(quoteId: String): List<JobQuoteEntity>

    @Query("SELECT * FROM job_quotes WHERE id = :id")
    suspend fun get(id: String): JobQuoteEntity?

    @Query("UPDATE job_quotes SET pendingSync = 0, serverVersion = :v WHERE id = :id")
    suspend fun markSynced(id: String, v: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: JobQuoteEntity)
}

@Dao
interface JobActivityDao {
    @Query("SELECT * FROM job_activities WHERE jobId = :jobId ORDER BY position, createdAt")
    fun observeForJob(jobId: String): Flow<List<JobActivityEntity>>

    @Query("SELECT * FROM job_activities WHERE jobId = :jobId ORDER BY position, createdAt")
    suspend fun forJob(jobId: String): List<JobActivityEntity>

    @Query("SELECT * FROM job_activities WHERE id = :id")
    suspend fun get(id: String): JobActivityEntity?

    @Query("UPDATE job_activities SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: JobActivityEntity)
}

@Dao
interface JobMaterialDao {
    @Query("SELECT * FROM job_materials WHERE jobId = :jobId ORDER BY createdAt")
    fun observeForJob(jobId: String): Flow<List<JobMaterialEntity>>

    @Query("SELECT * FROM job_materials WHERE id = :id")
    suspend fun get(id: String): JobMaterialEntity?

    @Query("UPDATE job_materials SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(material: JobMaterialEntity)
}

@Dao
interface ReportDao {
    @Query("SELECT * FROM reports WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE userId = :userId AND versionConflict = 1")
    fun observeVersionConflicts(userId: String): Flow<List<ReportEntity>>

    @Query("SELECT r.* FROM reports r INNER JOIN report_jobs rj ON rj.reportId = r.id WHERE rj.jobId = :jobId ORDER BY r.createdAt DESC")
    fun observeForJob(jobId: String): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE id = :id")
    fun observeOne(id: String): Flow<ReportEntity?>

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun get(id: String): ReportEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM reports WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("UPDATE reports SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE reports SET officialNumber = :num, versionConflict = :conflict, updatedAt = :now WHERE id = :id")
    suspend fun updateSyncFields(id: String, num: String?, conflict: Boolean, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: ReportEntity)
}

@Dao
interface ReportJobDao {
    @Query("SELECT * FROM report_jobs WHERE reportId = :reportId ORDER BY position, createdAt")
    fun observeForReport(reportId: String): Flow<List<ReportJobEntity>>

    @Query("SELECT * FROM report_jobs WHERE reportId = :reportId ORDER BY position, createdAt")
    suspend fun forReport(reportId: String): List<ReportJobEntity>

    @Query("SELECT * FROM report_jobs WHERE jobId = :jobId")
    suspend fun forJob(jobId: String): List<ReportJobEntity>

    @Query("SELECT * FROM report_jobs WHERE id = :id")
    suspend fun get(id: String): ReportJobEntity?

    @Query("UPDATE report_jobs SET pendingSync = 0, serverVersion = :v WHERE id = :id")
    suspend fun markSynced(id: String, v: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ReportJobEntity)
}

@Dao
interface ReportVersionDao {
    @Query("SELECT * FROM report_versions WHERE reportId = :reportId ORDER BY version")
    fun observeForReport(reportId: String): Flow<List<ReportVersionEntity>>

    @Query("SELECT * FROM report_versions WHERE reportId = :reportId ORDER BY version DESC LIMIT 1")
    suspend fun getLatest(reportId: String): ReportVersionEntity?

    @Query("SELECT * FROM report_versions WHERE id = :id")
    suspend fun get(id: String): ReportVersionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: ReportVersionEntity)
}

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

    @Query("SELECT COUNT(*) FROM sync_operations WHERE entityType = :type AND entityId = :id AND state IN ('PENDING','RETRY')")
    suspend fun activeOpsForEntity(type: String, id: String): Int

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
        JobEntity::class, JobQuoteEntity::class,
        JobActivityEntity::class, JobMaterialEntity::class,
        ReportEntity::class, ReportJobEntity::class, ReportVersionEntity::class,
        SyncOperationEntity::class, SyncConflictEntity::class,
        SyncMetaEntity::class,
    ],
    version = 6,
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
    abstract fun jobDao(): JobDao
    abstract fun jobQuoteDao(): JobQuoteDao
    abstract fun jobActivityDao(): JobActivityDao
    abstract fun jobMaterialDao(): JobMaterialDao
    abstract fun reportDao(): ReportDao
    abstract fun reportJobDao(): ReportJobDao
    abstract fun reportVersionDao(): ReportVersionDao
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

        /**
         * Migración v4→v5 (F4): Trabajos locales — `jobs` con numeración
         * provisional/oficial y dirección de ejecución propia, el vínculo
         * N:N con Cotizaciones y los hijos subordinados (actividades y
         * materiales descriptivos), todos sincronizables.
         */
        // Interna (no privada): la prueba de migración la ejerce directamente.
        internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `jobs` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `profileId` TEXT NOT NULL,
                        `provisionalNumber` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `startDateEpochDay` INTEGER, `estimatedDateEpochDay` INTEGER,
                        `deliveryDateEpochDay` INTEGER, `executionAddress` TEXT NOT NULL,
                        `notes` TEXT NOT NULL, `clientNameSnapshot` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL, `officialNumber` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_userId` ON `jobs` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_clientId` ON `jobs` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_profileId` ON `jobs` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_jobs_provisionalNumber` ON `jobs` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `job_quotes` (
                        `id` TEXT NOT NULL PRIMARY KEY, `jobId` TEXT NOT NULL,
                        `quoteId` TEXT NOT NULL, `relationType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_quotes_jobId` ON `job_quotes` (`jobId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_quotes_quoteId` ON `job_quotes` (`quoteId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `job_activities` (
                        `id` TEXT NOT NULL PRIMARY KEY, `jobId` TEXT NOT NULL,
                        `description` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `position` INTEGER NOT NULL, `startedAt` INTEGER,
                        `finishedAt` INTEGER, `notes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_activities_jobId` ON `job_activities` (`jobId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `job_materials` (
                        `id` TEXT NOT NULL PRIMARY KEY, `jobId` TEXT NOT NULL,
                        `description` TEXT NOT NULL, `origin` TEXT NOT NULL,
                        `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_materials_jobId` ON `job_materials` (`jobId`)")
            }
        }

        /**
         * Migración v5→v6 (F5): Informes — `reports` con numeración
         * provisional/oficial INF, destino INTERNO/CLIENTE, contenido
         * editable en BORRADOR y flag de conflicto de versiones; el vínculo
         * N:N `report_jobs` con Trabajos y los snapshots inmutables
         * `report_versions` enlazados por referencia.
         */
        // Interna (no privada): la prueba de migración la ejerce directamente.
        internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `reports` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `profileId` TEXT NOT NULL,
                        `provisionalNumber` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `title` TEXT NOT NULL, `destination` TEXT NOT NULL,
                        `content` TEXT NOT NULL, `currentVersion` INTEGER NOT NULL,
                        `emittedVersion` INTEGER NOT NULL, `clientNameSnapshot` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL, `officialNumber` TEXT,
                        `versionConflict` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_userId` ON `reports` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_clientId` ON `reports` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_profileId` ON `reports` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reports_provisionalNumber` ON `reports` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `report_jobs` (
                        `id` TEXT NOT NULL PRIMARY KEY, `reportId` TEXT NOT NULL,
                        `jobId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_jobs_reportId` ON `report_jobs` (`reportId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_jobs_jobId` ON `report_jobs` (`jobId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `report_versions` (
                        `id` TEXT NOT NULL PRIMARY KEY, `reportId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL,
                        `previousVersionId` TEXT, `serverOrder` INTEGER,
                        `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_versions_reportId` ON `report_versions` (`reportId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_versions_reportId_version` ON `report_versions` (`reportId`, `version`)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cotixgo.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
