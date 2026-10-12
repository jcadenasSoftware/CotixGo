package com.cotixgo.app.data

import com.cotixgo.app.net.ApiClient
import com.cotixgo.app.net.ApiException
import com.cotixgo.app.net.str
import kotlinx.coroutines.flow.first

/**
 * Autenticación F3: registro e inicio de sesión por email/contraseña contra la
 * API local de desarrollo, recuperación por correo (mecanismo de prueba sin
 * envío real) y cambio de cuenta bajo la regla aprobada (reautenticación +
 * cola vacía + cero pendientes → limpiar y descargar; en caso contrario,
 * bloquear conservando los datos).
 *
 * Solo datos sintéticos: ninguna cuenta ni credencial real debe usarse hasta
 * que la protección local en reposo esté definida, implementada y verificada.
 */
class AuthRepository(
    private val db: AppDatabase,
    private val session: Session,
    private val sync: SyncRepository,
) {
    private val api get() = ApiClient(session.apiBaseUrl) { null }

    suspend fun register(name: String, email: String, password: String): String? {
        if (session.isBound) return "Ya hay una cuenta vinculada. Usa Cambiar de cuenta."
        return try {
            val res = api.register(name.trim(), email.trim(), password)
            sync.bindAccount(
                res.str("user_id") ?: return "Respuesta inválida del servidor.",
                email.trim(),
                res.str("token") ?: return "Respuesta inválida del servidor.",
            )
            null
        } catch (e: ApiException) {
            e.message
        } catch (e: Exception) {
            "Sin conexión con el servidor local."
        }
    }

    suspend fun login(email: String, password: String): String? {
        if (session.isBound) return "Ya hay una cuenta vinculada. Usa Cambiar de cuenta."
        return try {
            val res = api.login(email.trim(), password)
            val userId = res.str("user_id") ?: return "Respuesta inválida del servidor."
            val token = res.str("token") ?: return "Respuesta inválida del servidor."
            sync.bindAccount(userId, email.trim(), token)
            null
        } catch (e: ApiException) {
            e.message
        } catch (e: Exception) {
            "Sin conexión con el servidor local."
        }
    }

    /** Recuperación local de prueba: el servidor registra en mail_outbox; no se envía correo. */
    suspend fun recover(email: String): String = try {
        api.recover(email.trim())
        "Si el correo existe, recibirás instrucciones de recuperación (simulado en desarrollo local)."
    } catch (e: ApiException) {
        e.message ?: "No se pudo procesar la recuperación."
    } catch (e: Exception) {
        "Sin conexión con el servidor local."
    }

    /**
     * Cambio de cuenta (regla aprobada): reautenticación de la cuenta actual,
     * verificación de cola vacía y cero entidades pendientes; solo entonces
     * limpiar la copia local y entrar con la cuenta nueva. Cualquier fallo
     * bloquea el cambio y conserva los datos locales.
     */
    suspend fun switchAccount(
        currentPassword: String,
        newEmail: String,
        newPassword: String,
        confirmed: Boolean,
    ): String? {
        val currentEmail = session.accountEmail ?: return "No hay cuenta vinculada."
        if (!confirmed) return "Se requiere confirmación explícita."
        // 1. Reautenticación de la cuenta actual.
        try {
            api.login(currentEmail, currentPassword)
        } catch (e: ApiException) {
            return "Reautenticación fallida: ${e.message}"
        } catch (e: Exception) {
            return "Sin conexión con el servidor local; el cambio queda bloqueado."
        }
        // 2. Verificación de estado limpio.
        val blockers = sync.switchBlockers()
        val openConflicts = db.syncDao().observeConflicts().first().size
        if (openConflicts > 0) return "Cambio bloqueado: $openConflicts conflictos sin resolver."
        if (blockers.isNotEmpty()) return "Cambio bloqueado: ${blockers.joinToString("; ")}."
        // 3. Validar credenciales de la cuenta nueva antes de limpiar nada.
        val res = try {
            api.login(newEmail.trim(), newPassword)
        } catch (e: ApiException) {
            return "No se pudo entrar con la cuenta nueva: ${e.message}. Datos locales conservados."
        } catch (e: Exception) {
            return "Sin conexión con el servidor local; el cambio queda bloqueado."
        }
        val userId = res.str("user_id") ?: return "Respuesta inválida del servidor."
        val token = res.str("token") ?: return "Respuesta inválida del servidor."
        if (userId == session.accountUserId) return "La cuenta nueva es la misma que la actual."
        // 4. Limpiar y vincular.
        sync.wipeLocalData()
        session.clearSession()
        sync.bindAccount(userId, newEmail.trim(), token)
        return null
    }
}
