package com.cotixgo.app.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cotixgo.app.data.DbMigrationManager
import com.cotixgo.app.data.security.AppLock

/**
 * Pantalla bloqueante cuando la migración/cifrado de la base falla o la
 * limpieza de archivos en claro quedó incompleta: la app NO sirve datos
 * hasta resolverlo. Opciones: reintentar, o borrar el estado local y
 * resincronizar (con aviso de pérdida de operaciones pendientes).
 */
@Composable
fun DbBlockedScreen(
    reason: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Protección local bloqueada", style = MaterialTheme.typography.headlineSmall)
        Text(
            "La migración a la base de datos cifrada no se completó de forma segura " +
                "($reason). CotixGo no mostrará tus datos hasta resolverlo para no " +
                "operar sobre una copia sin cifrar.",
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
        if (!confirming) {
            OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Borrar datos locales y resincronizar")
            }
        } else {
            Text(
                "Se borrarán TODOS los datos locales, incluidas las operaciones " +
                    "pendientes de sincronizar que aún no llegaron al servidor.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Confirmar borrado y resincronizar")
            }
            TextButton(onClick = { confirming = false }) { Text("Cancelar") }
        }
    }
}

/**
 * Barrera de interfaz (UX B): biometría/PIN al abrir la app. No es
 * cifrado adicional ni autenticación del almacén — la base está
 * protegida por SQLCipher+Keystore; esto solo reduce el acceso casual
 * con el dispositivo desbloqueado.
 */
@Composable
fun AppLockGate(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var promptShown by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun launch() {
        val bm = BiometricManager.from(activity)
        val can = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Sin credenciales de dispositivo no hay barrera posible:
            // se desbloquea la interfaz (la base sigue cifrada igualmente).
            AppLock.markUnlocked(); onUnlocked(); return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AppLock.markUnlocked(); onUnlocked()
                }
                override fun onAuthenticationError(code: Int, err: CharSequence) {
                    failed = true; promptShown = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear CotixGo")
            .setSubtitle("Protección de acceso a la aplicación")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        promptShown = true
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) { if (!promptShown) launch() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CotixGo bloqueado", style = MaterialTheme.typography.headlineSmall)
        if (failed) {
            Text("No se pudo verificar tu identidad.", Modifier.padding(top = 8.dp))
        }
        Button(onClick = { launch() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Desbloquear")
        }
    }
}
