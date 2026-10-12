package com.cotixgo.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncRepository
import java.util.concurrent.TimeUnit

/**
 * Sincronización en segundo plano (F3): ejecuta un ciclo push/pull con
 * restricción de red y backoff ante fallos transitorios. Los errores que
 * requieren atención pausan operaciones pero no el worker (resultado SUCCESS:
 * el ciclo terminó; la pausa es visible en la UI).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val sync = SyncRepository(db, Session(applicationContext, db))
        if (!sync.session.isBound) return Result.success()
        return try {
            sync.syncNow()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "cotixgo.sync.periodic"
    private const val ONE_SHOT = "cotixgo.sync.now"

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Dispara un ciclo inmediato (tras escrituras o el botón Sincronizar). */
    fun requestNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
