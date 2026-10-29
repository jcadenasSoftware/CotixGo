package com.cotixgo.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.cotixgo.app.core.Ids
import java.io.File

/**
 * Almacén inmutable de logos del perfil profesional.
 *
 * - Binarios en `filesDir/logos/` — privados, persistentes entre arranques.
 * - `logoRef` es el nombre del archivo (`logo_<uuid>.<ext>`): una referencia
 *   estable e inmutable que los snapshots de documentos congelan. Una imagen
 *   nueva SIEMPRE crea un archivo nuevo — nunca se sobrescribe uno que
 *   snapshots anteriores puedan seguir referenciando.
 * - La referencia viaja en el payload del perfil (JSON); el binario viaja
 *   por /logos (autenticado, por usuario) — nunca embebido en el JSON.
 */
object LogoStore {
    private val REF_RE = Regex("^logo_[0-9a-f-]{8,64}\\.(png|jpg|jpeg|webp)$")
    private const val MAX_BYTES = 4L * 1024 * 1024

    fun dir(context: Context): File =
        File(context.filesDir, "logos").apply { mkdirs() }

    fun isValidRef(ref: String?): Boolean = ref != null && REF_RE.matches(ref)

    fun file(context: Context, ref: String?): File? =
        if (isValidRef(ref)) File(dir(context), ref) else null

    fun exists(context: Context, ref: String?): Boolean =
        file(context, ref)?.let { it.exists() && it.length() > 0 } == true

    fun bytes(context: Context, ref: String?): ByteArray? =
        file(context, ref)?.takeIf { it.exists() }?.readBytes()

    fun decode(context: Context, ref: String?): Bitmap? =
        file(context, ref)?.takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Copia la imagen elegida a un archivo nuevo `logo_<uuid>.<ext>` y
     * devuelve su referencia. Extensión por MIME real del contenido; se
     * rechaza lo que no sea imagen decodificable.
     */
    fun import(context: Context, uri: Uri): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val ext = when (bounds.outMimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> return null
        }
        val ref = "logo_${Ids.new()}.$ext"
        return runCatching {
            File(dir(context), ref).writeBytes(bytes); ref
        }.getOrNull()
    }

    /** Binario descargado del servidor — nunca pisa nada (ref es única). */
    fun saveRemote(context: Context, ref: String?, bytes: ByteArray): Boolean {
        val dst = file(context, ref) ?: return false
        if (dst.exists()) return true // inmutable: ya está
        val tmp = File(dir(context), ".tmp_${Ids.new()}")
        return runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(dst)
        }.getOrDefault(false).also { if (!it) tmp.delete() }
    }
}
