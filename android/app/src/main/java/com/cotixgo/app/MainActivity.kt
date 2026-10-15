package com.cotixgo.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.data.DbMigrationManager
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.data.security.AppLock
import com.cotixgo.app.sync.SyncScheduler
import com.cotixgo.app.ui.AppLockGate
import com.cotixgo.app.ui.CotixGoApp
import com.cotixgo.app.ui.DbBlockedScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var blockedReason by remember { mutableStateOf<String?>(null) }
            var appReady by remember { mutableStateOf<AppReady?>(null) }
            var lockNonce by remember { mutableIntStateOf(0) }

            if (blockedReason == null && appReady == null) {
                try {
                    val db = AppDatabase.get(applicationContext)
                    val session = Session(applicationContext, db)
                    val sync = SyncRepository(db, session)
                    val auth = AuthRepository(db, session, sync)
                    val repo = AppRepository(db, sync)
                    SyncScheduler.schedulePeriodic(applicationContext)
                    appReady = AppReady(session, repo, sync, auth)
                } catch (e: DbMigrationManager.DbBlockedException) {
                    blockedReason = e.reason
                } catch (e: com.cotixgo.app.data.security.DbKeyProvider.KeyUnavailableException) {
                    blockedReason = "key_unavailable"
                }
            }

            val ready = appReady
            val reason = blockedReason
            when {
                reason != null -> DbBlockedScreen(
                    reason = reason,
                    onRetry = {
                        try {
                            DbMigrationManager.ensureReady(applicationContext)
                            blockedReason = null
                        } catch (e: DbMigrationManager.DbBlockedException) {
                            blockedReason = e.reason
                        }
                    },
                    onReset = {
                        // Acción informada del usuario: borra TODO el estado
                        // local; las operaciones pendientes se pierden.
                        DbMigrationManager.hardReset(applicationContext, andKey = true)
                        ready?.session?.clearSession()
                        blockedReason = null
                    },
                )
                ready == null -> Unit
                AppLock.needsUnlock(applicationContext) -> AppLockGate {
                    lockNonce++ // recomponer: needsUnlock() ya es false
                }
                else -> {
                    @Suppress("UNUSED_EXPRESSION") lockNonce
                    CotixGoApp(ready.repo, ready.sync, ready.auth)
                }
            }
        }
    }

    private class AppReady(
        val session: Session,
        val repo: AppRepository,
        val sync: SyncRepository,
        val auth: AuthRepository,
    )
}
