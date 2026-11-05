package com.cotixgo.app.ui

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.Lifecycle
import com.cotixgo.app.data.DbMigrationManager
import com.cotixgo.app.data.security.AppLock
import kotlinx.coroutines.flow.first

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
    var status by remember { mutableStateOf<String?>(null) }

    // Vía garantizada de PIN/patrón/contraseña del dispositivo, independiente
    // del BiometricPrompt: en MIUI el prompt combinado puede no ofrecer la
    // credencial del dispositivo o perder el callback de resultado.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        android.util.Log.i("AppLockGate", "credential result code=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            AppLock.markUnlocked(); onUnlocked()
        } else {
            status = "Credencial no verificada. Inténtalo de nuevo."
        }
    }

    fun launchCredential() {
        val km = activity.getSystemService(KeyguardManager::class.java)
        val intent = km?.createConfirmDeviceCredentialIntent(
            "Desbloquear CotixGo",
            "Usa el PIN, patrón o contraseña del dispositivo",
        )
        if (intent != null) {
            credentialLauncher.launch(intent)
        } else {
            // Sin credencial de dispositivo no hay barrera posible.
            AppLock.markUnlocked(); onUnlocked()
        }
    }

    fun launch() {
        val bm = BiometricManager.from(activity)
        val can = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            val km = activity.getSystemService(KeyguardManager::class.java)
            if (km?.isDeviceSecure == true) launchCredential()
            else { AppLock.markUnlocked(); onUnlocked() }
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    android.util.Log.i("AppLockGate", "onAuthenticationSucceeded authType=${result.authenticationType}")
                    AppLock.markUnlocked(); onUnlocked()
                }
                override fun onAuthenticationFailed() {
                    android.util.Log.i("AppLockGate", "onAuthenticationFailed")
                    status = "Huella no reconocida. Inténtalo de nuevo o usa tu PIN, patrón o contraseña."
                }
                override fun onAuthenticationError(code: Int, err: CharSequence) {
                    android.util.Log.i("AppLockGate", "onAuthenticationError code=$code err=$err")
                    status = when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED ->
                            "Autenticación cancelada. Toca «Desbloquear» para reintentar."
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            "Demasiados intentos de huella. Usa tu PIN, patrón o contraseña."
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                            "La huella no está disponible. Usa tu PIN, patrón o contraseña."
                        else -> err.toString().ifBlank { "No se pudo verificar tu identidad." }
                    }
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
        android.util.Log.i("AppLockGate", "authenticate() lifecycle=${activity.lifecycle.currentState}")
        prompt.authenticate(info)
    }

    // En MIUI el BiometricPrompt lanzado antes de ON_RESUME puede perder el
    // resultado: el sistema autentica pero el callback nunca llega a la app.
    // Se espera a RESUMED antes de invocar authenticate().
    LaunchedEffect(Unit) {
        activity.lifecycle.currentStateFlow
            .first { it.isAtLeast(Lifecycle.State.RESUMED) }
        launch()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CotixGo bloqueado", style = MaterialTheme.typography.headlineSmall)
        status?.let { Text(it, Modifier.padding(top = 8.dp)) }
        Button(onClick = { launch() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Desbloquear")
        }
        OutlinedButton(
            onClick = { launchCredential() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Usar PIN, patrón o contraseña")
        }
    }
}
