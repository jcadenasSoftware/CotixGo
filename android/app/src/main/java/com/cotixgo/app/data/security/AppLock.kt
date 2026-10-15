package com.cotixgo.app.data.security

import android.content.Context

/**
 * Bloqueo de interfaz opcional (UX B de la ficha aprobada): barrera de
 * interfaz al abrir la app con biometría/PIN del dispositivo. NO está
 * ligado a la clave de la base — no es cifrado adicional ni
 * autenticación del almacén — y no impide la sincronización en segundo
 * plano. Se desbloquea una vez por proceso.
 */
object AppLock {

    private const val PREFS = "cotixgo.settings"
    private const val KEY = "app_lock_enabled"

    @Volatile
    var unlockedThisProcess: Boolean = false
        private set

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    fun markUnlocked() { unlockedThisProcess = true }

    fun needsUnlock(context: Context): Boolean =
        isEnabled(context) && !unlockedThisProcess
}
