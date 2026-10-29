package com.cotixgo.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.cotixgo.app.core.Ids
import java.io.File

/**
 * Almacenamiento local de fotos de evidencia de Trabajos.
 *
 * - Binarios en `filesDir/photos/{jobId}/{fileName}` — privados, persistentes
 *   y al lado de la BD cifrada; nunca una ruta compartible.
 * - `fileName` = `{uuid}.jpg` generado en el dispositivo — no predecible.
 * - La captura usa un URI temporal de cache vía FileProvider
 *   (`{applicationId}.fileprovider`), sin permiso CAMERA: la app de cámara del
 *   sistema escribe ahí y nosotros lo importamos con compresión.
 * - Importación: redimensión a máx. 1600 px en el lado mayor (evidencia
 *   legible) + JPEG calidad 85.
 */
object PhotoStore {
    const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    fun dir(context: Context, jobId: String): File =
        File(context.filesDir, "photos/$jobId").apply { mkdirs() }

    fun file(context: Context, photo: JobPhotoEntity): File =
        File(context.filesDir, "photos/${photo.jobId}/${photo.fileName}")

    fun dirRoot(context: Context): File = File(context.filesDir, "photos")

    /** Archivo temporal de captura (cache), no persistido como evidencia. */
    fun newCaptureFile(context: Context): File =
        File(context.cacheDir, "photo_tmp").apply { mkdirs() }
            .let { File(it, "${Ids.new()}.jpg") }

    /** URI de FileProvider para entregar a la cámara del sistema. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Copia el origen (URI temporal o archivo) al almacenamiento privado del
     * trabajo, redimensionado. Devuelve el `fileName` o null si no es imagen.
     */
    fun import(context: Context, jobId: String, src: File, photoId: String): String? {
        val bmp = decodeScaled(src.absolutePath, MAX_SIDE) ?: return null
        val name = "$photoId.jpg"
        val dst = File(dir(context, jobId), name)
        dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return name
    }

    fun importUri(context: Context, jobId: String, uri: Uri, photoId: String): String? {
        val bmp = decodeScaledUri(context, uri, MAX_SIDE) ?: return null
        val name = "$photoId.jpg"
        val dst = File(dir(context, jobId), name)
        dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return name
    }

    fun delete(context: Context, photo: JobPhotoEntity) {
        file(context, photo).takeIf { it.exists() }?.delete()
    }

    /** Miniatura (~400 px) o completa (~1600 px) para Compose. */
    fun thumb(context: Context, photo: JobPhotoEntity): Bitmap? =
        decodeScaled(file(context, photo).absolutePath, 400)

    fun full(context: Context, photo: JobPhotoEntity): Bitmap? =
        decodeScaled(file(context, photo).absolutePath, MAX_SIDE)

    fun decodeScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeScaledUri(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** Escribe bytes descargados del servidor bajo el fileName del registro. */
    fun saveRemote(context: Context, photo: JobPhotoEntity, bytes: ByteArray): Boolean {
        return runCatching {
            File(dir(context, photo.jobId), photo.fileName).writeBytes(bytes)
        }.isSuccess
    }

    /** Verifica tamaño/márgenes mínimos sin descodificar. */
    fun fileBytes(context: Context, photo: JobPhotoEntity): ByteArray? =
        file(context, photo).takeIf { it.exists() }?.readBytes()
}
