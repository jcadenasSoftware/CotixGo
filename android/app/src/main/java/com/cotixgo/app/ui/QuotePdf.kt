package com.cotixgo.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.cotixgo.app.data.QuoteLineSnapshot
import com.cotixgo.app.data.QuoteSnapshot
import java.io.FileOutputStream

/**
 * Vista previa local de una cotización emitida, con jerarquía de propuesta
 * comercial: marca del negocio, identificación del documento y cliente,
 * tabla limpia de conceptos, totales destacados y condiciones al final.
 *
 * Fuente: QuoteSnapshot congelado de la versión emitida (nunca entidades
 * vivas). PdfDocument + PrintManager del sistema, papel Carta (612×792pt),
 * sin persistir ni compartir archivos.
 *
 * La paginación mide el texto real (Paint.measureText / breakText) y
 * reparte por capacidad verdadera de cada página: ninguna línea se pierde
 * ni se duplica y la última página reserva el bloque de totales+condiciones.
 */
private const val QP_W = 612            // Carta en pt
private const val QP_H = 792
private const val QP_MARGIN = 48f
private const val QP_RIGHT = QP_W - QP_MARGIN   // 564
private const val QP_ROW_H = 13f        // alto por línea envuelta de descripción
private const val QP_ROW_PAD = 5f       // espacio tras cada concepto
private const val QP_DESC_W = 300f      // columna de descripción
private const val QP_QTY_X = 418f       // bordes derechos de columnas
private const val QP_PRICE_X = 496f
private const val QP_HEADER_END_CONT = 108f

private val NAVY = Color.rgb(0x0A, 0x16, 0x33)
private val BLUE = Color.rgb(0x2E, 0x7C, 0xF6)
private val SLATE = Color.rgb(0x54, 0x61, 0x7E)
private val HAIR = Color.rgb(0xE3, 0xE8, 0xF0)
private val BAND = Color.rgb(0xDC, 0xE9, 0xFF)

fun printQuotePdf(context: Context, s: QuoteSnapshot) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(
        "cotixgo-${s.provisionalNumber}-v${s.version}",
        QuotePdfAdapter(s, context.applicationContext),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .build(),
    )
}

private class Paints {
    val brand = Paint().apply { color = NAVY; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val docTag = Paint().apply { color = BLUE; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val num = Paint().apply { color = NAVY; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    // Título de la propuesta — subtítulo prominente, no un campo etiquetado.
    val propTitle = Paint().apply { color = NAVY; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val head = Paint().apply { color = NAVY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val label = Paint().apply { color = SLATE; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val body = Paint().apply { color = Color.rgb(0x1A, 0x22, 0x38); textSize = 10.5f; isAntiAlias = true }
    val meta = Paint().apply { color = SLATE; textSize = 9.5f; isAntiAlias = true }
    val small = Paint().apply { color = SLATE; textSize = 9f; isAntiAlias = true }
    val ruleBold = Paint().apply { color = NAVY; strokeWidth = 1.5f }
    val ruleThin = Paint().apply { color = SLATE; strokeWidth = 0.6f }
    val ruleHair = Paint().apply { color = HAIR; strokeWidth = 0.4f }
    val band = Paint().apply { color = BAND }
    val neto = Paint().apply { color = NAVY; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
}

/** Envuelve por medida real del Paint; corta palabras largas con breakText. */
private fun wrapMeasured(text: String, paint: Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    for (raw in text.split('\n')) {
        var rest = raw.trim()
        while (rest.isNotEmpty()) {
            if (paint.measureText(rest) <= width) { out += rest; break }
            var take = paint.breakText(rest, true, width, null)
            val space = rest.take(take).lastIndexOf(' ')
            if (space > 0) take = space
            if (take <= 0) take = 1
            out += rest.take(take).trimEnd()
            rest = rest.drop(take).trimStart()
        }
        if (raw.isBlank()) out += ""
    }
    return out.ifEmpty { listOf("") }
}

/** Tramo de descripción de un concepto dibujado en una página concreta. */
private class Chunk(val lineIndex: Int, val from: Int, val to: Int)

private class Plan(
    val pages: List<List<Chunk>>,
    val descLines: List<List<String>>,
    val titleLines: List<String>,
    val footerH: Float,
)

private fun plan(s: QuoteSnapshot, p: Paints, hasLogo: Boolean): Plan {
    val descLines = s.lines.map { wrapMeasured(it.description, p.body, QP_DESC_W) }
    val titleLines = wrapMeasured(s.title.ifBlank { "—" }, p.propTitle, QP_RIGHT - QP_MARGIN)
    val contactParts = listOf(s.client.phone, s.client.email, s.client.address)
        .filter { it.isNotBlank() }
    val contactLines = contactParts.joinToString("  ·  ")
        .let { if (it.isBlank()) emptyList() else wrapMeasured(it, p.meta, QP_RIGHT - QP_MARGIN - 62f) }
    // Debe coincidir con la cabecera dibujada en drawPage (pág. 1):
    // 133f base + contacto + título (6+18línea+10) + sección + cabecera de
    // tabla; con logo la fila de marca ocupa 44f en vez de 22f (+22).
    val headerEndP1 = (if (contactLines.isEmpty()) 135f else 133f + contactLines.size * 12f) +
        titleLines.size * 18f + 48f + (if (hasLogo) 22f else 0f)

    // Pie del último bloque: totales + condiciones + información comercial.
    var footerH = 14f + (1 + s.retentions.size) * 14f + 30f
    if (s.notes.isNotBlank()) {
        footerH += 16f + wrapMeasured(s.notes, p.small, QP_RIGHT - QP_MARGIN).size * 11.5f
    }
    if (!s.profile.commercialInfo.isNullOrBlank()) {
        footerH += 10f + wrapMeasured(s.profile.commercialInfo, p.small, QP_RIGHT - QP_MARGIN).size * 11.5f
    }

    // Unidad de reparto: (índice de concepto, índice de línea envuelta).
    val pending = ArrayDeque<Pair<Int, Int>>().apply {
        s.lines.indices.forEach { i -> descLines[i].indices.forEach { j -> addLast(i to j) } }
    }
    val pages = mutableListOf<List<Chunk>>()
    var pageIndex = 0
    while (pending.isNotEmpty()) {
        val startY = if (pageIndex == 0) headerEndP1 else QP_HEADER_END_CONT
        val lastLimit = QP_H - QP_MARGIN - footerH - 8f
        val fullLimit = QP_H - QP_MARGIN - 14f
        var remH = 0f
        pending.forEach { (li, j) ->
            remH += QP_ROW_H
            if (j == descLines[li].lastIndex) remH += QP_ROW_PAD
        }
        // Si el resto cabe en la página, el pie siempre se reserva: cuando
        // cupiera sin pie pero no con él, se empujan filas a una página más
        // en vez de solapar el pie sobre el contenido.
        val limit = if (remH <= fullLimit - startY) lastLimit else fullLimit

        var y = startY
        val chunks = mutableListOf<Chunk>()
        while (pending.isNotEmpty()) {
            val li = pending.first().first
            val j0 = pending.first().second
            val k = pending.takeWhile { it.first == li }.size
            var take = 0
            var cy = y
            while (take < k && cy + QP_ROW_H <= limit) { cy += QP_ROW_H; take++ }
            if (take == 0) break
            val endsHere = j0 + take == descLines[li].size
            if (endsHere && cy + QP_ROW_PAD <= limit) cy += QP_ROW_PAD
            chunks += Chunk(li, j0, j0 + take)
            repeat(take) { pending.removeFirst() }
            y = cy
            if (!endsHere) break // página llena a mitad de concepto
        }
        if (chunks.isEmpty() && pending.isNotEmpty()) {
            // Ni una línea cabe (defensivo): fuerza una para no entrar en bucle.
            val (li, j0) = pending.removeFirst()
            chunks += Chunk(li, j0, j0 + 1)
        }
        pages += chunks
        pageIndex++
        if (pageIndex > 100) break // salvaguarda
    }
    return Plan(pages, descLines, titleLines, footerH)
}

private class QuotePdfAdapter(
    private val s: QuoteSnapshot,
    private val appCtx: Context,
) : PrintDocumentAdapter() {
    private val paints by lazy { Paints() }
    // Logo congelado: solo desde la referencia del snapshot, nunca del perfil vivo.
    private val logoBitmap by lazy {
        s.profile.logoRef?.let { com.cotixgo.app.data.LogoStore.decode(appCtx, it) }
    }
    private val plan by lazy { plan(s, paints, logoBitmap != null) }

    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled(); return
        }
        val info = PrintDocumentInfo.Builder("cotizacion.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(plan.pages.size)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled(); return
        }
        val doc = PdfDocument()
        plan.pages.forEachIndexed { pageIndex, chunks ->
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(QP_W, QP_H, pageIndex + 1).create()
            )
            drawPage(page.canvas, pageIndex, chunks, pageIndex == plan.pages.lastIndex)
            doc.finishPage(page)
        }
        try {
            doc.writeTo(FileOutputStream(destination.fileDescriptor))
            callback.onWriteFinished(pages)
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            doc.close()
        }
    }

    private fun drawRight(c: Canvas, p: Paint, text: String, x: Float, y: Float) {
        c.drawText(text, x - p.measureText(text), y, p)
    }

    private fun tableHeader(c: Canvas, p: Paints, y: Float): Float {
        c.drawText("CONCEPTO", QP_MARGIN, y, p.label)
        drawRight(c, p.label, "CANT.", QP_QTY_X, y)
        drawRight(c, p.label, "P. UNIT.", QP_PRICE_X, y)
        drawRight(c, p.label, "IMPORTE", QP_RIGHT, y)
        val ry = y + 7f
        c.drawLine(QP_MARGIN, ry, QP_RIGHT, ry, p.ruleThin)
        return ry + QP_ROW_H + 2f
    }

    private fun drawPage(
        c: Canvas, pageIndex: Int, chunks: List<Chunk>, isLast: Boolean,
    ) {
        val p = paints
        var y = QP_MARGIN
        if (pageIndex == 0) {
            // Cabecera de propuesta: marca, tipo y número del documento.
            val logo = logoBitmap
            if (logo != null) {
                // Logo del snapshot — caja 150×34f manteniendo proporción;
                // el nombre comercial queda junto a la imagen.
                val scale = minOf(150f / logo.width, 34f / logo.height)
                val dw = logo.width * scale; val dh = logo.height * scale
                c.drawBitmap(logo, null,
                    android.graphics.RectF(QP_MARGIN, y - 30f, QP_MARGIN + dw, y - 30f + dh), null)
                c.drawText(s.profile.businessName, QP_MARGIN + dw + 10f, y + 4f, p.brand)
                drawRight(c, p.docTag, "COTIZACIÓN", QP_RIGHT, y + 2f)
                y += 44f
            } else {
                c.drawText(s.profile.businessName, QP_MARGIN, y + 4f, p.brand)
                drawRight(c, p.docTag, "COTIZACIÓN", QP_RIGHT, y + 2f)
                y += 22f
            }
            drawRight(c, p.num, s.provisionalNumber, QP_RIGHT, y)
            y += 14f
            c.drawLine(QP_MARGIN, y, QP_RIGHT, y, p.ruleBold)
            y += 20f
            val vence = "Vence: ${s.expiryDate} (${s.validityDays} días)"
            c.drawText("Emitida: ${s.issueDate}", QP_MARGIN, y, p.meta)
            c.drawText(vence, QP_MARGIN + 170f, y, p.meta)
            drawRight(c, p.meta, s.currency, QP_RIGHT, y)
            y += 15f
            c.drawText("Cliente:", QP_MARGIN, y, p.meta)
            c.drawText(s.client.name, QP_MARGIN + 62f, y, p.head)
            y += 14f
            val contact = listOf(s.client.phone, s.client.email, s.client.address)
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            if (contact.isNotBlank()) {
                wrapMeasured(contact, p.meta, QP_RIGHT - QP_MARGIN - 62f).forEach {
                    c.drawText(it, QP_MARGIN + 62f, y, p.meta); y += 12f
                }
            } else y += 2f
            // Título de la propuesta — subtítulo propio, sin etiqueta.
            y += 6f
            plan.titleLines.forEach { c.drawText(it, QP_MARGIN, y, p.propTitle); y += 18f }
            y += 10f
            c.drawText("Conceptos", QP_MARGIN, y, p.head); y += 10f
            y = tableHeader(c, p, y)
        } else {
            c.drawText(s.profile.businessName, QP_MARGIN, y, p.meta)
            drawRight(c, p.meta, "COTIZACIÓN ${s.provisionalNumber}", QP_RIGHT, y)
            y += 12f
            c.drawLine(QP_MARGIN, y, QP_RIGHT, y, p.ruleBold)
            y += 16f
            c.drawText("Conceptos (continuación)", QP_MARGIN, y, p.head); y += 10f
            y = tableHeader(c, p, y)
        }

        // Filas de conceptos — solo los tramos asignados a esta página.
        for (ch in chunks) {
            val l = s.lines[ch.lineIndex]
            val desc = plan.descLines[ch.lineIndex]
            for (j in ch.from until ch.to) {
                c.drawText(desc[j], QP_MARGIN, y, p.body)
                if (j == 0) {
                    // Columnas solo en la primera línea del concepto.
                    drawRight(c, p.body, "${l.quantity} ${l.unit}", QP_QTY_X, y)
                    drawRight(c, p.body, l.unitPrice, QP_PRICE_X, y)
                    drawRight(c, p.body, l.lineTotal, QP_RIGHT, y)
                }
                y += QP_ROW_H
            }
            if (ch.to == desc.size) {
                y += QP_ROW_PAD - 1f
                c.drawLine(QP_MARGIN, y - QP_ROW_PAD + 3f, QP_RIGHT, y - QP_ROW_PAD + 3f, p.ruleHair)
                y += 1f
            }
        }

        // Pie — solo última página: totales destacados, condiciones e info.
        if (isLast) {
            var fy = QP_H - QP_MARGIN - plan.footerH
            val labelW = 218f
            fun totalLine(text: String, amount: String, paint: Paint) {
                c.drawText(text, QP_RIGHT - labelW - 130f, fy, p.small)
                drawRight(c, paint, amount, QP_RIGHT, fy)
                fy += 14f
            }
            c.drawLine(QP_MARGIN, fy - 6f, QP_RIGHT, fy - 6f, p.ruleThin); fy += 4f
            totalLine("Bruto", "${s.gross} ${s.currency}", p.body)
            s.retentions.forEach { r ->
                totalLine("${r.name} ${r.ratePercent}% sobre ${r.base}", "−${r.amount}", p.small)
            }
            // Neto destacado sobre banda suave.
            c.drawRect(QP_RIGHT - labelW - 140f, fy - 11f, QP_RIGHT, fy + 5f, p.band)
            c.drawText("Total a pagar", QP_RIGHT - labelW - 130f, fy, p.head)
            drawRight(c, p.neto, "${s.net} ${s.currency}", QP_RIGHT, fy)
            fy += 24f
            if (s.notes.isNotBlank()) {
                c.drawText("CONDICIONES", QP_MARGIN, fy, p.label); fy += 12f
                wrapMeasured(s.notes, p.small, QP_RIGHT - QP_MARGIN).forEach {
                    c.drawText(it, QP_MARGIN, fy, p.small); fy += 11.5f
                }
                fy += 4f
            }
            if (!s.profile.commercialInfo.isNullOrBlank()) {
                c.drawText("INFORMACIÓN COMERCIAL", QP_MARGIN, fy, p.label); fy += 12f
                wrapMeasured(s.profile.commercialInfo, p.small, QP_RIGHT - QP_MARGIN).forEach {
                    c.drawText(it, QP_MARGIN, fy, p.small); fy += 11.5f
                }
            }
        }
    }
}
