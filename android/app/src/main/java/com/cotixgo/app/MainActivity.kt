package com.cotixgo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.sync.SyncScheduler
import com.cotixgo.app.ui.CotixGoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(applicationContext)
        val session = Session(applicationContext, db)
        val sync = SyncRepository(db, session)
        val auth = AuthRepository(db, session, sync)
        val repo = AppRepository(db, sync)
        SyncScheduler.schedulePeriodic(applicationContext)
        setContent {
            CotixGoApp(repo, sync, auth)
        }
    }
}
