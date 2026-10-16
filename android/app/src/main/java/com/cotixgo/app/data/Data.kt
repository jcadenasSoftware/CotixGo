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
@Entity(tableName = "job_materials", indices = [Index("jobId"), Index("materialId")])
data class JobMaterialEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val description: String,
    val origin: String,
    val quantity: String = "",
    val unit: String = "",
    val notes: String = "",
    // F8: vínculo opcional al catálogo de Materiales — solo filas NUEVAS;
    // los registros históricos F4 quedan descriptivos (materialId = null).
    val materialId: String? = null,
    val condition: String? = null,
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

// ---------- Cuentas de Cobro, Métodos de Cobro y Pagos (F6) ----------

/**
 * Cuenta de Cobro (docs/03 §6): nace en PENDIENTE con numeración
 * `CC-PEND-XXXX`; el backend asigna `CC-AAAAMM-NNN` sobre el mismo
 * entity_id en el primer registro aceptado. Editable solo en PENDIENTE y
 * sin pagos aplicados; PARCIAL/PAGADA se derivan de `appliedAmount` vs
 * `net`; cancelable solo sin pagos aplicados. Los snapshots JSON de
 * Cliente, Perfil y retenciones quedan congelados al crear y son
 * inmutables una vez aplicado un pago.
 */
@Entity(
    tableName = "collection_accounts",
    indices = [Index("userId"), Index("clientId"), Index("profileId"), Index(value = ["provisionalNumber"], unique = true)],
)
data class CollectionAccountEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val profileId: String,
    val provisionalNumber: String,
    val status: String,
    val currency: String,
    val gross: String = "0.00",
    val totalRetained: String = "0.00",
    val net: String = "0.00",
    val appliedAmount: String = "0.00",
    val balance: String = "0.00",
    val issuedAt: Long,
    val dueDateEpochDay: Long? = null,
    val conditions: String = "",
    val clientSnapshotJson: String = "",
    val profileSnapshotJson: String = "",
    val retentionsSnapshotJson: String = "",
    val clientNameSnapshot: String = "",
    val archived: Boolean = false,
    val officialNumber: String? = null,
    // Espejo autoritativo de derivados (F-pausadas): ver PaymentEntity.
    val serverAppliedAmount: String = "0.00",
    val serverBalance: String = "0.00",
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Línea de la Cuenta de Cobro: concepto, tipo SERVICIO/MATERIAL/OTRO,
 * cantidad, precio unitario e importe de línea calculado con el motor de
 * F2. Viaja dentro del payload de la Cuenta (no es entidad de sync
 * independiente): el documento y sus líneas cambian atómicamente y la
 * regla de inmutabilidad tras el primer pago aplica al conjunto.
 */
@Entity(tableName = "collection_account_items", indices = [Index("accountId")])
data class CollectionAccountItemEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val position: Int,
    val conceptType: String,
    val description: String,
    val quantity: String,
    val unit: String = "",
    val unitPrice: String,
    val lineTotal: String,
    val createdAt: Long,
)

/** Vínculo Cuenta ↔ Trabajo (conceptual CollectionAccountJob, docs/02 §23). */
@Entity(tableName = "collection_account_jobs", indices = [Index("accountId"), Index("jobId")])
data class CollectionAccountJobEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val jobId: String,
    val position: Int,
    val createdAt: Long,
)

/** Vínculo Cuenta ↔ Cotización (conceptual CollectionAccountQuote, docs/02). */
@Entity(tableName = "collection_account_quotes", indices = [Index("accountId"), Index("quoteId")])
data class CollectionAccountQuoteEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val quoteId: String,
    val position: Int,
    val createdAt: Long,
)

/**
 * Método de Cobro global del Usuario/Titular: `name` requerido,
 * `detail` opcional, `archived` soportado. Sin valores predeterminados.
 */
@Entity(tableName = "collection_methods", indices = [Index("userId")])
data class CollectionMethodEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val detail: String = "",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Pago recibido (append-only, docs/03 §6): se registra contra una Cuenta
 * individual, un Consolidado o sin asignar vinculado al Cliente. El dinero
 * aplicado vive en eventos `payment_assignments`; `appliedAmount`,
 * `unassignedAmount`, destino vigente (`assignedTargetType` +
 * `assignedAccountId`/`assignedConsolidatedId`) y `assignmentStatus` son
 * derivados mantenidos en la misma transacción que cada evento (local y
 * servidor). Nunca existe dinero aplicado a dos documentos a la vez.
 *
 * F7: `effectiveAmount` es el importe vigente (corrige `amount` solo vía
 * eventos `payment_adjustments`); `refundedAmount` acumula Reembolsos y
 * `annulled` marca la anulación terminal. Invariante compartida:
 * aplicado + reembolsado ≤ efectivo; disponible = efectivo − aplicado −
 * reembolsado.
 */
@Entity(
    tableName = "payments",
    indices = [Index("userId"), Index("clientId"), Index("assignedAccountId"), Index("assignedConsolidatedId")],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val collectionMethodId: String? = null,
    val amount: String,
    val paidAtEpochDay: Long,
    val reference: String = "",
    val notes: String = "",
    val appliedAmount: String = "0.00",
    val unassignedAmount: String = "0.00",
    val effectiveAmount: String = "0.00",
    val refundedAmount: String = "0.00",
    val annulled: Boolean = false,
    val assignedAccountId: String? = null,
    val assignedConsolidatedId: String? = null,
    /** ACCOUNT | CONSOLIDATED (enum wire `account`/`consolidated`). */
    val assignedTargetType: String? = null,
    val assignmentStatus: String = "UNASSIGNED",
    // Espejo de los derivados confirmados por el servidor (F-pausadas):
    // los campos aplicado/sinAsignar/reembolsado de la fila son optimistas;
    // estos guardan el último estado autoritativo recibido por pull o
    // incluido en el resultado aplicado.
    val serverAppliedAmount: String = "0.00",
    val serverUnassignedAmount: String = "0.00",
    val serverRefundedAmount: String = "0.00",
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Evento auditado de asignación (append-only): `assign` aplica un monto
 * del Pago a un destino (el servidor recorta al saldo pendiente y el
 * excedente queda UNASSIGNED en el mismo Pago), `unassign` retira la
 * aplicación vigente y `reassign` la mueve a otro destino del mismo
 * Cliente. Todo evento exige `reason`; `amount` registra lo aplicado
 * (en assign/reassign) o lo liberado (en unassign) efectivamente, y
 * `releasedAmount` lo liberado por una reasignación.
 *
 * F7: `targetType` (ACCOUNT | CONSOLIDATED) discrimina el destino —
 * `accountId` o `consolidatedId` según corresponda; `fromAccountId` /
 * `fromConsolidatedId` registran el origen de una reasignación.
 */
@Entity(
    tableName = "payment_assignments",
    indices = [Index("paymentId"), Index("accountId"), Index("consolidatedId"), Index("userId")],
)
data class PaymentAssignmentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val paymentId: String,
    val action: String,
    /** ACCOUNT | CONSOLIDATED — discrimina qué id de destino aplica. */
    val targetType: String = "ACCOUNT",
    val accountId: String? = null,
    val consolidatedId: String? = null,
    val fromAccountId: String? = null,
    val fromConsolidatedId: String? = null,
    val amount: String,
    val releasedAmount: String? = null,
    val reason: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * F7 — Consolidado de Cuentas de Cobro (docs/03 §6): documento que agrupa
 * ≥2 Cuentas no canceladas con saldo pendiente del mismo Cliente y moneda
 * (los Perfiles pueden diferir). Nace PENDIENTE con numeración provisional
 * `CONS-PEND-XXXX` y recibe la oficial `CONS-AAAAMM-NNN` en la primera
 * aceptación del servidor; PARCIAL/PAGADA son derivadas; CANCELADA solo
 * sin pagos aplicados y libera a las Cuentas.
 *
 * La membresía vive en `consolidated_collection_accounts` (N:M histórico:
 * una Cuenta puede tener varias membresías, a lo sumo una en un
 * Consolidado no cancelado). `totalAmount`/`appliedAmount`/`balance` son
 * derivados que el servidor recalcula siempre.
 */
@Entity(
    tableName = "consolidated_collections",
    indices = [Index("userId"), Index("clientId"), Index(value = ["provisionalNumber"], unique = true)],
)
data class ConsolidatedCollectionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val clientId: String,
    val provisionalNumber: String,
    /** PENDIENTE | PARCIAL | PAGADA | CANCELADA */
    val status: String = "PENDIENTE",
    val currency: String,
    val totalAmount: String = "0.00",
    val appliedAmount: String = "0.00",
    val balance: String = "0.00",
    val clientNameSnapshot: String = "",
    val archived: Boolean = false,
    val officialNumber: String? = null,
    // Espejo autoritativo de derivados (F-pausadas): ver PaymentEntity.
    val serverAppliedAmount: String = "0.00",
    val serverBalance: String = "0.00",
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/** Membresía Consolidado ↔ Cuenta (N:M con historial; ≤1 activa por Cuenta). */
@Entity(
    tableName = "consolidated_collection_accounts",
    indices = [Index("consolidatedId"), Index("accountId")],
)
data class ConsolidatedCollectionAccountEntity(
    @PrimaryKey val id: String,
    val consolidatedId: String,
    val accountId: String,
    val position: Int,
    val createdAt: Long,
)

/**
 * F7 — Ajuste de Pago (append-only, auditado): `CORRECTION` fija un nuevo
 * importe efectivo (`newEffectiveAmount` solicitado por el usuario);
 * `ANNULMENT` es terminal y una sola vez. El servidor conserva el efectivo
 * antes/después, lo liberado y su origen (`releasedFromType/Id`); esos
 * campos se aplican desde el estado autoritativo al sincronizar.
 */
@Entity(tableName = "payment_adjustments", indices = [Index("paymentId"), Index("userId")])
data class PaymentAdjustmentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val paymentId: String,
    /** CORRECTION | ANNULMENT */
    val kind: String,
    val reason: String,
    val newEffectiveAmount: String? = null,
    val effectiveBefore: String = "",
    val effectiveAfter: String = "",
    val releasedAmount: String = "0.00",
    /** ACCOUNT | CONSOLIDATED | null cuando no hubo liberación. */
    val releasedFromType: String? = null,
    val releasedFromId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * F7 — Reembolso (append-only, irreversible): movimiento nuevo vinculado
 * al Pago origen; nunca edita ni elimina el Pago. Solo hasta el remanente
 * disponible (efectivo − aplicado − reembolsado); el dinero aplicado debe
 * liberarse antes con una operación auditada. Sin reversión aprobada.
 */
@Entity(tableName = "payment_refunds", indices = [Index("paymentId"), Index("userId")])
data class PaymentRefundEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val paymentId: String,
    val amount: String,
    val refundedAtEpochDay: Long,
    val collectionMethodId: String? = null,
    val reason: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Dao
interface CollectionAccountDao {
    @Query("SELECT * FROM collection_accounts WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<CollectionAccountEntity>>

    @Query("SELECT * FROM collection_accounts WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun observeForClient(clientId: String): Flow<List<CollectionAccountEntity>>

    @Query("SELECT * FROM collection_accounts WHERE id = :id")
    fun observeOne(id: String): Flow<CollectionAccountEntity?>

    @Query("SELECT * FROM collection_accounts WHERE id = :id")
    suspend fun get(id: String): CollectionAccountEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM collection_accounts WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("UPDATE collection_accounts SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE collection_accounts SET officialNumber = :num, updatedAt = :now WHERE id = :id")
    suspend fun updateOfficial(id: String, num: String?, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: CollectionAccountEntity)
}

@Dao
interface CollectionAccountItemDao {
    @Query("SELECT * FROM collection_account_items WHERE accountId = :accountId ORDER BY position, createdAt")
    fun observeForAccount(accountId: String): Flow<List<CollectionAccountItemEntity>>

    @Query("SELECT * FROM collection_account_items WHERE accountId = :accountId ORDER BY position, createdAt")
    suspend fun forAccount(accountId: String): List<CollectionAccountItemEntity>

    @Query("DELETE FROM collection_account_items WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CollectionAccountItemEntity>)
}

@Dao
interface CollectionAccountJobDao {
    @Query("SELECT * FROM collection_account_jobs WHERE accountId = :accountId ORDER BY position, createdAt")
    fun observeForAccount(accountId: String): Flow<List<CollectionAccountJobEntity>>

    @Query("SELECT * FROM collection_account_jobs WHERE accountId = :accountId ORDER BY position, createdAt")
    suspend fun forAccount(accountId: String): List<CollectionAccountJobEntity>

    @Query("SELECT * FROM collection_account_jobs WHERE jobId = :jobId")
    suspend fun forJob(jobId: String): List<CollectionAccountJobEntity>

    @Query("SELECT * FROM collection_account_jobs")
    fun observeAll(): Flow<List<CollectionAccountJobEntity>>

    @Query("DELETE FROM collection_account_jobs WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<CollectionAccountJobEntity>)
}

@Dao
interface CollectionAccountQuoteDao {
    @Query("SELECT * FROM collection_account_quotes WHERE accountId = :accountId ORDER BY position, createdAt")
    fun observeForAccount(accountId: String): Flow<List<CollectionAccountQuoteEntity>>

    @Query("SELECT * FROM collection_account_quotes WHERE accountId = :accountId ORDER BY position, createdAt")
    suspend fun forAccount(accountId: String): List<CollectionAccountQuoteEntity>

    @Query("SELECT * FROM collection_account_quotes WHERE quoteId = :quoteId")
    suspend fun forQuote(quoteId: String): List<CollectionAccountQuoteEntity>

    @Query("SELECT * FROM collection_account_quotes")
    fun observeAll(): Flow<List<CollectionAccountQuoteEntity>>

    @Query("DELETE FROM collection_account_quotes WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<CollectionAccountQuoteEntity>)
}

@Dao
interface CollectionMethodDao {
    @Query("SELECT * FROM collection_methods WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<CollectionMethodEntity>>

    @Query("SELECT * FROM collection_methods WHERE id = :id")
    suspend fun get(id: String): CollectionMethodEntity?

    @Query("UPDATE collection_methods SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(method: CollectionMethodEntity)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun observeForClient(clientId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE assignedAccountId = :accountId ORDER BY createdAt DESC")
    fun observeForAccount(accountId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE id = :id")
    fun observeOne(id: String): Flow<PaymentEntity?>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun get(id: String): PaymentEntity?

    @Query("UPDATE payments SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payment: PaymentEntity)
}

@Dao
interface PaymentAssignmentDao {
    @Query("SELECT * FROM payment_assignments WHERE userId = :userId ORDER BY createdAt, id")
    fun observeForUser(userId: String): Flow<List<PaymentAssignmentEntity>>

    @Query("SELECT * FROM payment_assignments WHERE paymentId = :paymentId ORDER BY createdAt, id")
    fun observeForPayment(paymentId: String): Flow<List<PaymentAssignmentEntity>>

    @Query("SELECT * FROM payment_assignments WHERE paymentId = :paymentId ORDER BY createdAt, id")
    suspend fun forPayment(paymentId: String): List<PaymentAssignmentEntity>

    @Query("SELECT * FROM payment_assignments WHERE accountId = :accountId OR fromAccountId = :accountId ORDER BY createdAt, id")
    fun observeForAccount(accountId: String): Flow<List<PaymentAssignmentEntity>>

    @Query("SELECT * FROM payment_assignments WHERE id = :id")
    suspend fun get(id: String): PaymentAssignmentEntity?

    @Query("UPDATE payment_assignments SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: PaymentAssignmentEntity)
}

@Dao
interface ConsolidatedCollectionDao {
    @Query("SELECT * FROM consolidated_collections WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<ConsolidatedCollectionEntity>>

    @Query("SELECT * FROM consolidated_collections WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun observeForClient(clientId: String): Flow<List<ConsolidatedCollectionEntity>>

    @Query("SELECT * FROM consolidated_collections WHERE id = :id")
    fun observeOne(id: String): Flow<ConsolidatedCollectionEntity?>

    @Query("SELECT * FROM consolidated_collections WHERE id = :id")
    suspend fun get(id: String): ConsolidatedCollectionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM consolidated_collections WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("UPDATE consolidated_collections SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE consolidated_collections SET officialNumber = :num, updatedAt = :now WHERE id = :id")
    suspend fun updateOfficial(id: String, num: String?, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(consolidated: ConsolidatedCollectionEntity)
}

@Dao
interface ConsolidatedCollectionAccountDao {
    @Query("SELECT * FROM consolidated_collection_accounts WHERE consolidatedId = :consolidatedId ORDER BY position, createdAt")
    fun observeForConsolidated(consolidatedId: String): Flow<List<ConsolidatedCollectionAccountEntity>>

    @Query("SELECT * FROM consolidated_collection_accounts WHERE consolidatedId = :consolidatedId ORDER BY position, createdAt")
    suspend fun forConsolidated(consolidatedId: String): List<ConsolidatedCollectionAccountEntity>

    /** Membresías de la Cuenta en cualquier Consolidado (historial N:M). */
    @Query(
        """SELECT l.* FROM consolidated_collection_accounts l
           JOIN consolidated_collections c ON c.id = l.consolidatedId
           WHERE l.accountId = :accountId""",
    )
    fun observeForAccount(accountId: String): Flow<List<ConsolidatedCollectionAccountEntity>>

    /** ¿La Cuenta pertenece a un Consolidado no cancelado? (anti doble vínculo). */
    @Query(
        """SELECT c.* FROM consolidated_collections c
           JOIN consolidated_collection_accounts l ON l.consolidatedId = c.id
           WHERE l.accountId = :accountId AND c.status != 'CANCELADA'
           LIMIT 1""",
    )
    suspend fun activeConsolidatedForAccount(accountId: String): ConsolidatedCollectionEntity?

    /** Todas las membresías en Consolidados no cancelados (para UI/validación). */
    @Query(
        """SELECT l.* FROM consolidated_collection_accounts l
           JOIN consolidated_collections c ON c.id = l.consolidatedId
           WHERE c.status != 'CANCELADA'""",
    )
    fun observeActiveMemberships(): Flow<List<ConsolidatedCollectionAccountEntity>>

    /** Pagos con aplicación vigente sobre el Consolidado. */
    @Query("SELECT * FROM payments WHERE assignedConsolidatedId = :consolidatedId ORDER BY createdAt DESC")
    fun observePaymentsForConsolidated(consolidatedId: String): Flow<List<PaymentEntity>>

    @Query("DELETE FROM consolidated_collection_accounts WHERE consolidatedId = :consolidatedId")
    suspend fun deleteForConsolidated(consolidatedId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<ConsolidatedCollectionAccountEntity>)
}

@Dao
interface PaymentAdjustmentDao {
    @Query("SELECT * FROM payment_adjustments WHERE paymentId = :paymentId ORDER BY createdAt, id")
    fun observeForPayment(paymentId: String): Flow<List<PaymentAdjustmentEntity>>

    @Query("SELECT * FROM payment_adjustments WHERE paymentId = :paymentId ORDER BY createdAt, id")
    suspend fun forPayment(paymentId: String): List<PaymentAdjustmentEntity>

    @Query("SELECT * FROM payment_adjustments WHERE id = :id")
    suspend fun get(id: String): PaymentAdjustmentEntity?

    @Query("UPDATE payment_adjustments SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: PaymentAdjustmentEntity)
}

@Dao
interface PaymentRefundDao {
    @Query("SELECT * FROM payment_refunds WHERE paymentId = :paymentId ORDER BY createdAt, id")
    fun observeForPayment(paymentId: String): Flow<List<PaymentRefundEntity>>

    @Query("SELECT * FROM payment_refunds WHERE paymentId = :paymentId ORDER BY createdAt, id")
    suspend fun forPayment(paymentId: String): List<PaymentRefundEntity>

    @Query("SELECT * FROM payment_refunds WHERE id = :id")
    suspend fun get(id: String): PaymentRefundEntity?

    @Query("UPDATE payment_refunds SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: PaymentRefundEntity)
}

// ---------- Inventario y Compras (F8) ----------

/**
 * Material del catálogo global del Usuario/Titular (docs/03 §6): nombre y
 * unidad requeridos, descripción opcional, archivado sin efectos de stock.
 * `NUEVO`/`USADO` es una dimensión del stock del material — no son dos
 * materiales separados. Entidad documental con concurrencia optimista.
 */
@Entity(tableName = "materials", indices = [Index("userId")])
data class MaterialEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val unit: String,
    val description: String = "",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Saldo proyectado por Material (F8): fila espejo de la entidad
 * `inventory_stock` del servidor — la fuente de verdad son los movimientos
 * append-only y el servidor recalcula este saldo atómicamente con cada
 * movimiento. El cliente jamás la escribe ni la encola (solo la recibe
 * por pull); el stock mostrado en pantalla suma además los movimientos
 * locales pendientes.
 */
@Entity(tableName = "material_stocks")
data class MaterialStockEntity(
    @PrimaryKey val materialId: String,
    val stockNew: String = "0",
    val stockUsed: String = "0",
    val updatedAt: Long,
    val serverVersion: Int = 0,
)

/**
 * Compra documental (docs/03 §6): numeración provisional `COM-PEND-XXXX`
 * local → oficial `COM-AAAAMM-NNN` en el primer registro aceptado por el
 * servidor sobre el mismo `entity_id`. Ciclo BORRADOR (sin movimientos) →
 * CONFIRMADA (entradas por línea, editable por diferencia) → ANULADA
 * terminal con movimientos inversos; `archived` es acción separada que no
 * revierte nada. `currency` es la moneda global del titular (el servidor
 * la fija) y `totalAmount` es derivado por el servidor.
 */
@Entity(
    tableName = "purchases",
    indices = [
        Index("userId"),
        Index(value = ["provisionalNumber"], unique = true),
    ],
)
data class PurchaseEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val provisionalNumber: String,
    val status: String,
    val purchaseDateEpochDay: Long,
    val supplierPlace: String = "",
    val reference: String = "",
    val notes: String = "",
    val currency: String,
    val totalAmount: String = "0.00",
    val archived: Boolean = false,
    val officialNumber: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

/**
 * Línea de Compra: identifica Material, cantidad, unidad, precio unitario
 * y condición de stock. `materialNameSnapshot` fotografía el nombre al
 * registrarla — el historial no cambia si el catálogo se edita después.
 * Las filas viajan dentro del payload de la Compra (mismo patrón que las
 * líneas de la Cuenta de Cobro) y se reconstruyen desde el pull.
 */
@Entity(tableName = "purchase_items", indices = [Index("purchaseId"), Index("materialId")])
data class PurchaseItemEntity(
    @PrimaryKey val id: String,
    val purchaseId: String,
    val position: Int,
    val materialId: String,
    val materialNameSnapshot: String = "",
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val lineTotal: String = "",
    val condition: String = "NUEVO",
    val createdAt: Long,
)

/**
 * Movimiento de inventario (append-only, fuente de verdad del stock):
 * nunca se edita ni se borra. `movementType` ∈ {PURCHASE_IN,
 * JOB_CONSUMPTION, ADJUSTMENT_IN, ADJUSTMENT_OUT}; `origin` explica el
 * evento ∈ {COMPRA, TRABAJO, SOBRANTE, AJUSTE}; la condición NUEVO/USADO
 * dimensiona el saldo. El stock negativo está permitido (control interno,
 * no restrictivo).
 */
@Entity(
    tableName = "inventory_movements",
    indices = [Index("materialId"), Index("purchaseId"), Index("jobId"), Index("userId")],
)
data class InventoryMovementEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val materialId: String,
    val condition: String,
    val movementType: String,
    val origin: String,
    val quantity: String,
    val unit: String,
    val unitCost: String? = null,
    val purchaseId: String? = null,
    val jobId: String? = null,
    val jobMaterialId: String? = null,
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
    val serverVersion: Int = 0,
)

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM materials WHERE userId = :userId AND archived = 0 ORDER BY name")
    fun observeActiveForUser(userId: String): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM materials WHERE id = :id")
    fun observeOne(id: String): Flow<MaterialEntity?>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun get(id: String): MaterialEntity?

    @Query("UPDATE materials SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(material: MaterialEntity)
}

@Dao
interface MaterialStockDao {
    @Query("SELECT * FROM material_stocks WHERE materialId = :materialId")
    fun observeOne(materialId: String): Flow<MaterialStockEntity?>

    @Query("SELECT * FROM material_stocks WHERE materialId = :materialId")
    suspend fun get(materialId: String): MaterialStockEntity?

    @Query("SELECT * FROM material_stocks")
    fun observeAll(): Flow<List<MaterialStockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stock: MaterialStockEntity)
}

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchases WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeForUser(userId: String): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE id = :id")
    fun observeOne(id: String): Flow<PurchaseEntity?>

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun get(id: String): PurchaseEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM purchases WHERE provisionalNumber = :number)")
    suspend fun provisionalNumberExists(number: String): Boolean

    @Query("SELECT * FROM purchases WHERE userId = :userId AND pendingSync = 1")
    fun observePendingForUser(userId: String): Flow<List<PurchaseEntity>>

    @Query("UPDATE purchases SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("UPDATE purchases SET officialNumber = :num, updatedAt = :now WHERE id = :id")
    suspend fun updateOfficial(id: String, num: String?, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(purchase: PurchaseEntity)
}

@Dao
interface PurchaseItemDao {
    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId ORDER BY position, createdAt")
    fun observeForPurchase(purchaseId: String): Flow<List<PurchaseItemEntity>>

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId ORDER BY position, createdAt")
    suspend fun forPurchase(purchaseId: String): List<PurchaseItemEntity>

    @Query("DELETE FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun deleteForPurchase(purchaseId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PurchaseItemEntity>)
}

@Dao
interface InventoryMovementDao {
    @Query("SELECT * FROM inventory_movements WHERE materialId = :materialId ORDER BY occurredAt, createdAt, id")
    fun observeForMaterial(materialId: String): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE purchaseId = :purchaseId ORDER BY occurredAt, createdAt, id")
    fun observeForPurchase(purchaseId: String): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE jobId = :jobId ORDER BY occurredAt, createdAt, id")
    fun observeForJob(jobId: String): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE userId = :userId ORDER BY occurredAt DESC, createdAt DESC")
    fun observeForUser(userId: String): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE materialId = :materialId AND pendingSync = 1")
    suspend fun pendingForMaterial(materialId: String): List<InventoryMovementEntity>

    @Query("SELECT * FROM inventory_movements WHERE purchaseId = :purchaseId AND pendingSync = 1")
    suspend fun pendingForPurchase(purchaseId: String): List<InventoryMovementEntity>

    @Query("SELECT * FROM inventory_movements WHERE id = :id")
    suspend fun get(id: String): InventoryMovementEntity?

    @Query("UPDATE inventory_movements SET pendingSync = 0, serverVersion = :v, updatedAt = :now WHERE id = :id")
    suspend fun markSynced(id: String, v: Int, now: Long)

    @Query("DELETE FROM inventory_movements WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(movement: InventoryMovementEntity)
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

/** Estado de la última operación de una entidad (proyección para UI). */
data class LatestOpState(
    val entityType: String,
    val entityId: String,
    val state: String,
    val resultCode: String?,
    val resultJson: String?,
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

    /** Todas las operaciones de la entidad (historial completo, cualquier estado). */
    @Query("SELECT * FROM sync_operations WHERE entityType = :type AND entityId = :id ORDER BY seq ASC")
    suspend fun allOpsForEntity(type: String, id: String): List<SyncOperationEntity>

    /** Todas las operaciones sin resolver (en cola o pausadas), en orden. */
    @Query("SELECT * FROM sync_operations WHERE state IN ('PENDING','RETRY','PAUSED') ORDER BY seq ASC")
    suspend fun unresolvedOps(): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE state = 'PAUSED' ORDER BY seq ASC")
    suspend fun pausedOpsNow(): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE opId = :opId")
    suspend fun opById(opId: String): SyncOperationEntity?

    /** Suma 1 al contador de intentos sin tocar el resultado registrado. */
    @Query("UPDATE sync_operations SET attempts = attempts + 1, updatedAt = :now WHERE opId = :opId")
    suspend fun bumpOpAttempts(opId: String, now: Long)

    /** Reinicia el presupuesto de reintentos (reintento manual de INTERNAL_ERROR). */
    @Query("UPDATE sync_operations SET attempts = 0, updatedAt = :now WHERE opId = :opId")
    suspend fun resetOpAttempts(opId: String, now: Long)

    /** Reintento programado: state=RETRY conservando código/resultado del último fallo. */
    @Query("UPDATE sync_operations SET state = 'RETRY', resultJson = :resultJson, updatedAt = :now WHERE opId = :opId")
    suspend fun rescheduleOp(opId: String, resultJson: String?, now: Long)

    /**
     * Última operación registrada por entidad (cualquier estado): base para
     * mostrar el estado de sincronización de cada fila y para separar el
     * efecto pendiente del rechazado en los agregados financieros.
     */
    @Query(
        """SELECT o.entityType AS entityType, o.entityId AS entityId,
                  o.state AS state, o.resultCode AS resultCode, o.resultJson AS resultJson
           FROM sync_operations o
           INNER JOIN (
               SELECT entityType, entityId, MAX(seq) AS mseq
               FROM sync_operations GROUP BY entityType, entityId
           ) t ON o.entityType = t.entityType AND o.entityId = t.entityId
              AND o.seq = t.mseq""",
    )
    fun observeLatestOpStates(): Flow<List<LatestOpState>>

    /** Último payload aplicado por el servidor (para proyectar deltas locales). */
    @Query("SELECT payloadJson FROM sync_operations WHERE entityType = :type AND entityId = :id AND state = 'DONE' AND resultJson IS NOT NULL ORDER BY seq DESC LIMIT 1")
    suspend fun lastAppliedPayload(type: String, id: String): String?

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
        CollectionAccountEntity::class, CollectionAccountItemEntity::class,
        CollectionAccountJobEntity::class, CollectionAccountQuoteEntity::class,
        CollectionMethodEntity::class, PaymentEntity::class, PaymentAssignmentEntity::class,
        ConsolidatedCollectionEntity::class, ConsolidatedCollectionAccountEntity::class,
        PaymentAdjustmentEntity::class, PaymentRefundEntity::class,
        MaterialEntity::class, MaterialStockEntity::class,
        PurchaseEntity::class, PurchaseItemEntity::class,
        InventoryMovementEntity::class,
        SyncOperationEntity::class, SyncConflictEntity::class,
        SyncMetaEntity::class,
    ],
    version = 10,
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
    abstract fun collectionAccountDao(): CollectionAccountDao
    abstract fun collectionAccountItemDao(): CollectionAccountItemDao
    abstract fun collectionAccountJobDao(): CollectionAccountJobDao
    abstract fun collectionAccountQuoteDao(): CollectionAccountQuoteDao
    abstract fun collectionMethodDao(): CollectionMethodDao
    abstract fun paymentDao(): PaymentDao
    abstract fun paymentAssignmentDao(): PaymentAssignmentDao
    abstract fun consolidatedCollectionDao(): ConsolidatedCollectionDao
    abstract fun consolidatedCollectionAccountDao(): ConsolidatedCollectionAccountDao
    abstract fun paymentAdjustmentDao(): PaymentAdjustmentDao
    abstract fun paymentRefundDao(): PaymentRefundDao
    abstract fun materialDao(): MaterialDao
    abstract fun materialStockDao(): MaterialStockDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun purchaseItemDao(): PurchaseItemDao
    abstract fun inventoryMovementDao(): InventoryMovementDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        @Volatile private var openedDbName: String? = null

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

        /**
         * Migración v6→v7 (F6): Cuentas de Cobro con líneas y vínculos a
         * Trabajos/Cotizaciones, Métodos de Cobro del Usuario y Pagos
         * append-only con sus eventos auditados de asignación.
         */
        // Interna (no privada): la prueba de migración la ejerce directamente.
        internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `collection_accounts` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `profileId` TEXT NOT NULL,
                        `provisionalNumber` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `currency` TEXT NOT NULL, `gross` TEXT NOT NULL,
                        `totalRetained` TEXT NOT NULL, `net` TEXT NOT NULL,
                        `appliedAmount` TEXT NOT NULL, `balance` TEXT NOT NULL,
                        `issuedAt` INTEGER NOT NULL, `dueDateEpochDay` INTEGER,
                        `conditions` TEXT NOT NULL, `clientSnapshotJson` TEXT NOT NULL,
                        `profileSnapshotJson` TEXT NOT NULL, `retentionsSnapshotJson` TEXT NOT NULL,
                        `clientNameSnapshot` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                        `officialNumber` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_accounts_userId` ON `collection_accounts` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_accounts_clientId` ON `collection_accounts` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_accounts_profileId` ON `collection_accounts` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_accounts_provisionalNumber` ON `collection_accounts` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `collection_account_items` (
                        `id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL, `conceptType` TEXT NOT NULL,
                        `description` TEXT NOT NULL, `quantity` TEXT NOT NULL,
                        `unit` TEXT NOT NULL, `unitPrice` TEXT NOT NULL,
                        `lineTotal` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_account_items_accountId` ON `collection_account_items` (`accountId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `collection_account_jobs` (
                        `id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL,
                        `jobId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_account_jobs_accountId` ON `collection_account_jobs` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_account_jobs_jobId` ON `collection_account_jobs` (`jobId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `collection_account_quotes` (
                        `id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL,
                        `quoteId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_account_quotes_accountId` ON `collection_account_quotes` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_account_quotes_quoteId` ON `collection_account_quotes` (`quoteId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `collection_methods` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL, `detail` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_methods_userId` ON `collection_methods` (`userId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `payments` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `collectionMethodId` TEXT,
                        `amount` TEXT NOT NULL, `paidAtEpochDay` INTEGER NOT NULL,
                        `reference` TEXT NOT NULL, `notes` TEXT NOT NULL,
                        `appliedAmount` TEXT NOT NULL, `unassignedAmount` TEXT NOT NULL,
                        `assignedAccountId` TEXT, `assignmentStatus` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_userId` ON `payments` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_clientId` ON `payments` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_assignedAccountId` ON `payments` (`assignedAccountId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `payment_assignments` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `paymentId` TEXT NOT NULL, `action` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL, `fromAccountId` TEXT,
                        `amount` TEXT NOT NULL, `releasedAmount` TEXT,
                        `reason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_paymentId` ON `payment_assignments` (`paymentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_accountId` ON `payment_assignments` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_userId` ON `payment_assignments` (`userId`)")
            }
        }

        /**
         * Migración v7→v8 (F7): Consolidados de Cuentas de Cobro con su
         * membresía N:M histórica, eventos append-only de ajuste
         * (`payment_adjustments`: CORRECTION/ANNULMENT) y de Reembolso
         * (`payment_refunds`). Los Pagos ganan el importe efectivo
         * (`effectiveAmount` = `amount` en datos previos), el acumulado
         * reembolsado, la anulación terminal y el destino genérico
         * (`assignedTargetType` + `assignedConsolidatedId`); los eventos de
         * asignación se reconstruyen con `targetType` y los ids de
         * Consolidado (`accountId` pasa a ser opcional). Datos y cola de
         * sincronización se conservan intactos.
         */
        // Interna (no privada): la prueba de migración la ejerce directamente.
        internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Pagos: invariante efectivo/aplicado/reembolsado y destino
                // genérico. Datos F6 previos: efectivo = amount recibido.
                db.execSQL("ALTER TABLE payments ADD COLUMN effectiveAmount TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE payments ADD COLUMN refundedAmount TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE payments ADD COLUMN annulled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE payments ADD COLUMN assignedConsolidatedId TEXT")
                db.execSQL("ALTER TABLE payments ADD COLUMN assignedTargetType TEXT")
                db.execSQL("UPDATE payments SET effectiveAmount = amount WHERE effectiveAmount = ''")
                db.execSQL(
                    "UPDATE payments SET assignedTargetType = 'ACCOUNT' WHERE assignedAccountId IS NOT NULL",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_assignedConsolidatedId` ON `payments` (`assignedConsolidatedId`)")

                // Eventos de asignación: la columna `accountId` pasa a ser
                // opcional y se añade el discriminador de destino — exige
                // reconstruir la tabla preservando todas las filas.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `payment_assignments_new` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `paymentId` TEXT NOT NULL, `action` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL DEFAULT 'ACCOUNT',
                        `accountId` TEXT, `consolidatedId` TEXT,
                        `fromAccountId` TEXT, `fromConsolidatedId` TEXT,
                        `amount` TEXT NOT NULL, `releasedAmount` TEXT,
                        `reason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL(
                    """INSERT INTO payment_assignments_new
                        (id, userId, paymentId, action, targetType, accountId, consolidatedId,
                         fromAccountId, fromConsolidatedId, amount, releasedAmount, reason,
                         createdAt, updatedAt, pendingSync, serverVersion)
                       SELECT id, userId, paymentId, action, 'ACCOUNT', accountId, NULL,
                              fromAccountId, NULL, amount, releasedAmount, reason,
                              createdAt, updatedAt, pendingSync, serverVersion
                       FROM payment_assignments""",
                )
                db.execSQL("DROP TABLE payment_assignments")
                db.execSQL("ALTER TABLE payment_assignments_new RENAME TO payment_assignments")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_paymentId` ON `payment_assignments` (`paymentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_accountId` ON `payment_assignments` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_consolidatedId` ON `payment_assignments` (`consolidatedId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_assignments_userId` ON `payment_assignments` (`userId`)")

                // Consolidado documental + membresía N:M.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `consolidated_collections` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `clientId` TEXT NOT NULL, `provisionalNumber` TEXT NOT NULL,
                        `status` TEXT NOT NULL, `currency` TEXT NOT NULL,
                        `totalAmount` TEXT NOT NULL, `appliedAmount` TEXT NOT NULL,
                        `balance` TEXT NOT NULL, `clientNameSnapshot` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL, `officialNumber` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_consolidated_collections_userId` ON `consolidated_collections` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_consolidated_collections_clientId` ON `consolidated_collections` (`clientId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_consolidated_collections_provisionalNumber` ON `consolidated_collections` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `consolidated_collection_accounts` (
                        `id` TEXT NOT NULL PRIMARY KEY, `consolidatedId` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_consolidated_collection_accounts_consolidatedId` ON `consolidated_collection_accounts` (`consolidatedId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_consolidated_collection_accounts_accountId` ON `consolidated_collection_accounts` (`accountId`)")

                // Eventos append-only F7: ajustes y reembolsos.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `payment_adjustments` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `paymentId` TEXT NOT NULL, `kind` TEXT NOT NULL,
                        `reason` TEXT NOT NULL, `newEffectiveAmount` TEXT,
                        `effectiveBefore` TEXT NOT NULL, `effectiveAfter` TEXT NOT NULL,
                        `releasedAmount` TEXT NOT NULL,
                        `releasedFromType` TEXT, `releasedFromId` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_adjustments_paymentId` ON `payment_adjustments` (`paymentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_adjustments_userId` ON `payment_adjustments` (`userId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `payment_refunds` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `paymentId` TEXT NOT NULL, `amount` TEXT NOT NULL,
                        `refundedAtEpochDay` INTEGER NOT NULL, `collectionMethodId` TEXT,
                        `reason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_refunds_paymentId` ON `payment_refunds` (`paymentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_refunds_userId` ON `payment_refunds` (`userId`)")
            }
        }

        /**
         * F8 — Inventario y Compras (docs/03 §6): conserva íntegramente los
         * datos F1–F7 (solo ALTER ADD/CREATE). Vínculo opcional JobMaterial→
         * Material (historiales intactos), catálogo de Materiales, saldo
         * proyectado, Compras con líneas y movimientos append-only.
         */
        internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `job_materials` ADD COLUMN `materialId` TEXT")
                db.execSQL("ALTER TABLE `job_materials` ADD COLUMN `condition` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_materials_materialId` ON `job_materials` (`materialId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `materials` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL, `unit` TEXT NOT NULL,
                        `description` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_userId` ON `materials` (`userId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `material_stocks` (
                        `materialId` TEXT NOT NULL PRIMARY KEY,
                        `stockNew` TEXT NOT NULL, `stockUsed` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `purchases` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `provisionalNumber` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `purchaseDateEpochDay` INTEGER NOT NULL,
                        `supplierPlace` TEXT NOT NULL, `reference` TEXT NOT NULL,
                        `notes` TEXT NOT NULL, `currency` TEXT NOT NULL,
                        `totalAmount` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                        `officialNumber` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchases_userId` ON `purchases` (`userId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_purchases_provisionalNumber` ON `purchases` (`provisionalNumber`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `purchase_items` (
                        `id` TEXT NOT NULL PRIMARY KEY, `purchaseId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL, `materialId` TEXT NOT NULL,
                        `materialNameSnapshot` TEXT NOT NULL,
                        `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL,
                        `unitPrice` TEXT NOT NULL, `lineTotal` TEXT NOT NULL,
                        `condition` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_items_purchaseId` ON `purchase_items` (`purchaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_items_materialId` ON `purchase_items` (`materialId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `inventory_movements` (
                        `id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL,
                        `materialId` TEXT NOT NULL, `condition` TEXT NOT NULL,
                        `movementType` TEXT NOT NULL, `origin` TEXT NOT NULL,
                        `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL,
                        `unitCost` TEXT, `purchaseId` TEXT, `jobId` TEXT,
                        `jobMaterialId` TEXT, `note` TEXT NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_materialId` ON `inventory_movements` (`materialId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_purchaseId` ON `inventory_movements` (`purchaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_jobId` ON `inventory_movements` (`jobId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_userId` ON `inventory_movements` (`userId`)")
            }
        }

        /**
         * v10 — espejo autoritativo de derivados financieros (resolución de
         * operaciones PAUSED): los campos `appliedAmount`/`balance`/
         * `unassignedAmount`/`refundedAmount` de la fila son optimistas y
         * pueden incluir efectos de eventos aún no aceptados o rechazados
         * por el servidor. Las columnas `server*` conservan el último valor
         * que el servidor reportó como aplicado (vía pull o `server_state`
         * del resultado). El backfill solo copia el valor vigente en filas
         * ya confirmadas (pendingSync=0 con versión de servidor) Y sin
         * operaciones sin resolver ni rechazadas que pudieran haber alterado
         * los derivados locales de forma optimista — se detecta cualquier op
         * PENDING/RETRY/PAUSED (o cerrada como rechazo reconocido) cuya
         * entityId sea la fila o cuyo payloadJson la referencie (las
         * asignaciones/ajustes/reembolsos apuntan al Pago/Cuenta/Consolidado
         * desde su payload, no por entityId). Esas filas quedan en el piso
         * confirmado '0.00': un efecto no aceptado jamás infla el saldo
         * confirmado — sus efectos se muestran aparte.
         */
        internal val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Predicado compartido: ¿hay una operación sin resolver o
                // reconocida como rechazada que referencia a esta fila?
                fun dirtyOps(table: String) =
                    "EXISTS (SELECT 1 FROM `sync_operations` so WHERE" +
                        " (so.state IN ('PENDING','RETRY','PAUSED')" +
                        "  OR so.resultCode IN ('REJECTED_ACKNOWLEDGED','DISCARDED_LOCAL'," +
                        "                       'DISCARDED_CASCADE','PREREQUISITE_DISCARDED'))" +
                        " AND (so.entityId = `$table`.id" +
                        "      OR so.payloadJson LIKE '%' || `$table`.id || '%'))"
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `serverAppliedAmount` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `serverUnassignedAmount` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `serverRefundedAmount` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL(
                    """UPDATE `payments` SET `serverAppliedAmount` = `appliedAmount`,
                        `serverUnassignedAmount` = `unassignedAmount`,
                        `serverRefundedAmount` = `refundedAmount`
                        WHERE `pendingSync` = 0 AND `serverVersion` > 0
                        AND NOT ${dirtyOps("payments")}""",
                )
                db.execSQL("ALTER TABLE `collection_accounts` ADD COLUMN `serverAppliedAmount` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE `collection_accounts` ADD COLUMN `serverBalance` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL(
                    """UPDATE `collection_accounts` SET `serverAppliedAmount` = `appliedAmount`,
                        `serverBalance` = `balance`
                        WHERE `pendingSync` = 0 AND `serverVersion` > 0
                        AND NOT ${dirtyOps("collection_accounts")}""",
                )
                db.execSQL("ALTER TABLE `consolidated_collections` ADD COLUMN `serverAppliedAmount` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE `consolidated_collections` ADD COLUMN `serverBalance` TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL(
                    """UPDATE `consolidated_collections` SET `serverAppliedAmount` = `appliedAmount`,
                        `serverBalance` = `balance`
                        WHERE `pendingSync` = 0 AND `serverVersion` > 0
                        AND NOT ${dirtyOps("consolidated_collections")}""",
                )
            }
        }

        internal val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10,
        )

        fun get(context: Context): AppDatabase {
            // El archivo servido es siempre el que la migración preparó:
            // en producción cotixgo.db; con hook de prueba, el nombre
            // inyectado. Una instancia abierta con otro nombre es un error
            // de aislamiento de pruebas — fallar en lugar de servirla.
            val name = DbMigrationManager.resolvedDbName()
            instance?.let {
                check(openedDbName == name) {
                    "AppDatabase ya abierta como '$openedDbName'; se pidió '$name'"
                }
                return it
            }
            return synchronized(this) {
                instance ?: run {
                    // Gate exclusivo: migración fail-closed a base cifrada
                    // (SQLCipher) antes de cualquier apertura. Lanza
                    // DbBlockedException si la app debe quedar bloqueada.
                    DbMigrationManager.ensureReady(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        name,
                    )
                        .openHelperFactory(DbMigrationManager.encryptedFactory(context))
                        .addMigrations(*ALL_MIGRATIONS)
                        .build().also { instance = it; openedDbName = name }
                }
            }
        }

        /** Solo para pruebas: cierra y olvida el singleton. */
        internal fun resetForTest() = synchronized(this) {
            instance?.close()
            instance = null
            openedDbName = null
        }
    }
}
