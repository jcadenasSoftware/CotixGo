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
import com.cotixgo.app.data.QuoteSnapshot
import java.io.FileOutputStream

/**
 * Prototipo local: vista previa PDF de una cotización emitida, a partir del
 * QuoteSnapshot congelado de la versión (nunca del perfil vivo).
 *
 * Usa PdfDocument + PrintManager del sistema: la vista previa de impresión
 * del SO muestra el PDF real paginado sin persistir ni compartir archivos.
 */
private const val PAGE_W = 595          // A4 en pt aprox.
private const val PAGE_H = 842
private const val MARGIN = 48f
private const val LINE_H = 16f

// Alto que consume cada concepto (descripción + detalle: (LINE_H-2)+(LINE_H+2)).
// LINES_TOP_* deben coincidir con el y que deja la cabecera de drawPage en
// cada tipo de página.
private const val LINE_BLOCK = 2 * LINE_H                  // 32f
private const val LINES_TOP_FIRST = 174f
private const val LINES_TOP_CONT = 64f
private const val LINES_LIMIT = PAGE_H - MARGIN - 40f      // 754f
private const val FOOTER_TOP = PAGE_H - MARGIN - 130f      // 664f

private fun linesTop(pageIndex: Int): Float =
    if (pageIndex == 0) LINES_TOP_FIRST else LINES_TOP_CONT

private fun linesLimit(isLast: Boolean): Float =
    if (isLast) FOOTER_TOP - LINE_BLOCK else LINES_LIMIT

/** Conceptos que caben en la página: límite distinto si el pie ocupa abajo. */
private fun capacity(pageIndex: Int, isLast: Boolean): Int {
    var y = linesTop(pageIndex)
    val limit = linesLimit(isLast)
    var n = 0
    while (y + LINE_BLOCK <= limit) { y += LINE_BLOCK; n++ }
    return n
}

/**
 * Reparto de líneas por página según capacidad real (nunca se descartan
 * líneas no dibujadas). La última página reserva espacio para el pie: si
 * todo cupiera pero el pie no, se dejan líneas para una página extra.
 */
private fun pageSplits(lineCount: Int): List<Int> {
    val out = mutableListOf<Int>()
    var rem = lineCount
    var idx = 0
    while (rem > 0) {
        val take = when {
            rem <= capacity(idx, isLast = true) -> rem
            rem <= capacity(idx, isLast = false) -> capacity(idx, isLast = true)
            else -> capacity(idx, isLast = false)
        }
        out += take
        rem -= take
        idx++
    }
    return out.ifEmpty { listOf(0) }
}

private fun pageCount(s: QuoteSnapshot): Int = pageSplits(s.lines.size).size

fun printQuotePdf(context: Context, s: QuoteSnapshot) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(
        "cotixgo-${s.provisionalNumber}-v${s.version}",
        QuotePdfAdapter(s),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}

private class QuotePdfAdapter(private val s: QuoteSnapshot) : PrintDocumentAdapter() {
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
            .setPageCount(pageCount(s))
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
        val splits = pageSplits(s.lines.size)
        var remaining = s.lines.toList()
        splits.forEachIndexed { pageIndex, take ->
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create()
            )
            drawPage(page.canvas, pageIndex, take, remaining,
                isLast = pageIndex == splits.lastIndex)
            remaining = remaining.drop(take)
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

    private fun drawPage(
        c: Canvas, pageIndex: Int, take: Int,
        remaining: List<com.cotixgo.app.data.QuoteLineSnapshot>,
        isLast: Boolean,
    ) {
        var y = MARGIN
        val bold = Paint().apply {
            color = Color.BLACK; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val h = Paint().apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val t = Paint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
        val small = Paint().apply { color = Color.DKGRAY; textSize = 9.5f; isAntiAlias = true }

        if (pageIndex == 0) {
            // Cabecera — marca: nombre comercial del snapshot.
            c.drawText(s.profile.businessName, MARGIN, y, bold); y += 26f
            c.drawText("COTIZACIÓN", MARGIN, y, h)
            c.drawText(s.provisionalNumber, PAGE_W - MARGIN - 200f, y, h); y += 24f
            c.drawText("Cliente: ${s.client.name}", MARGIN, y, t); y += LINE_H
            c.drawText("Título: ${s.title}", MARGIN, y, t); y += LINE_H
            c.drawText("Emisión: ${s.issueDate}  ·  Vence: ${s.expiryDate}  ·  ${s.currency}", MARGIN, y, t); y += 28f
            c.drawText("Conceptos", MARGIN, y, h); y += LINE_H
            c.drawLine(MARGIN, y - 6f, PAGE_W - MARGIN, y - 6f, t)
        } else {
            c.drawText("Conceptos (continuación)", MARGIN, y, h); y += LINE_H
        }
        // Líneas — solo las asignadas a esta página y si caben enteras.
        val limit = linesLimit(isLast)
        for (l in remaining.take(take)) {
            if (y + LINE_BLOCK > limit) break
            c.drawText("[${l.conceptType}] ${l.description}", MARGIN, y, t); y += LINE_H - 2f
            c.drawText(
                "${l.quantity} ${l.unit} × ${l.unitPrice} = ${l.lineTotal}",
                MARGIN + 12f, y, small
            ); y += LINE_H + 2f
        }
        // Pie/totales — última página.
        if (isLast) {
            y = FOOTER_TOP
            s.retentions.forEach { r ->
                c.drawText("${r.name}: ${r.ratePercent}% sobre ${r.base} = ${r.amount}", MARGIN, y, small); y += LINE_H - 4f
            }
            y += 8f
            c.drawText("Bruto: ${s.gross}  ·  Retenido: ${s.totalRetained}  ·  Neto: ${s.net}", MARGIN, y, h); y += 24f
            if (s.notes.isNotBlank()) {
                c.drawText("Observaciones: ${s.notes}", MARGIN, y, small); y += LINE_H
            }
            if (!s.profile.commercialInfo.isNullOrBlank()) {
                y += 8f
                c.drawText(s.profile.commercialInfo, MARGIN, y, small)
            }
        }
    }
}
