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
import com.cotixgo.app.data.ReportSnapshot
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * Prototipo local: vista previa PDF de un Informe de trabajo emitido, a
 * partir del ReportSnapshot congelado en `report_versions` (nunca del
 * perfil, cliente o trabajos vivos).
 *
 * Misma mecánica que Cotización y Cuenta de cobro: PdfDocument +
 * PrintManager del sistema, papel ISO A4, sin persistir ni compartir
 * archivos. Geometría y paginación compartidas en PdfPages.kt; aquí las
 * filas son de texto simple (PDF_LINE_H), no bloques de dos líneas.
 */
fun printReportPdf(context: Context, s: ReportSnapshot) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(
        "cotixgo-${s.provisionalNumber}-v${s.version}",
        ReportPdfAdapter(s, context.applicationContext),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}

private enum class RowStyle { HEAD, BODY, SMALL }
private data class PdfRow(val text: String, val style: RowStyle = RowStyle.BODY)

/** Ajuste de texto por ancho aproximado (~92 chars a 11pt en 499pt útiles). */
private fun wrap(text: String, width: Int = 92, indent: String = ""): List<String> {
    val out = mutableListOf<String>()
    for (raw in text.split('\n')) {
        var line = raw.trim()
        var first = true
        while (line.isNotEmpty()) {
            val limit = width - (if (first) 0 else indent.length)
            val take = if (line.length <= limit) line.length else {
                val cut = line.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
                cut
            }
            out += (if (first) "" else indent) + line.take(take).trimEnd()
            line = line.drop(take).trimStart()
            first = false
        }
        if (raw.isBlank()) out += ""
    }
    return out.ifEmpty { listOf("") }
}

private fun epochDay(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun buildRows(s: ReportSnapshot): List<PdfRow> {
    val rows = mutableListOf<PdfRow>()
    wrap(s.content.ifBlank { "(sin contenido)" }).forEach { rows += PdfRow(it) }
    s.jobs.forEach { j ->
        rows += PdfRow("")
        rows += PdfRow("${j.number} — ${j.status.replace('_', ' ')}", RowStyle.HEAD)
        j.completedActivities.forEach { a ->
            val txt = "✓ ${a.description}" +
                (a.finishedAt?.let { " (${epochDay(it)})" } ?: "") +
                if (a.notes.isNotBlank()) " — ${a.notes}" else ""
            wrap(txt, indent = "  ").forEach { rows += PdfRow(it, RowStyle.SMALL) }
        }
        j.materials.forEach { m ->
            val txt = "Material: ${m.description}" +
                if (m.quantity.isNotBlank()) " (${m.quantity} ${m.unit})".trimEnd() else "" +
                if (m.origin == "CLIENTE") " [del cliente]" else ""
            wrap(txt, indent = "  ").forEach { rows += PdfRow(it, RowStyle.SMALL) }
        }
        if (j.notes.isNotBlank()) {
            wrap("Observaciones: ${j.notes}", indent = "  ").forEach { rows += PdfRow(it, RowStyle.SMALL) }
        }
    }
    return rows
}

private class ReportPdfAdapter(
    private val s: ReportSnapshot,
    private val appCtx: Context,
) : PrintDocumentAdapter() {
    private val rows by lazy { buildRows(s) }
    // Logo congelado del snapshot — nunca el perfil vivo.
    private val logoBitmap by lazy {
        s.profile.logoRef?.let { com.cotixgo.app.data.LogoStore.decode(appCtx, it) }
    }
    private val topFirst get() =
        if (logoBitmap != null) PDF_LINES_TOP_FIRST + 22f else PDF_LINES_TOP_FIRST
    private val splits by lazy {
        pdfPageSplits(rows.size, PDF_LINE_H, topFirst = topFirst)
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled(); return
        }
        val info = PrintDocumentInfo.Builder("informe.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(splits.size)
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
        var remaining = rows.toList()
        splits.forEachIndexed { pageIndex, take ->
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(PDF_PAGE_W, PDF_PAGE_H, pageIndex + 1).create()
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
        c: Canvas, pageIndex: Int, take: Int, remaining: List<PdfRow>, isLast: Boolean,
    ) {
        var y = PDF_MARGIN
        val bold = Paint().apply {
            color = Color.BLACK; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val h = Paint().apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val t = Paint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
        val small = Paint().apply { color = Color.DKGRAY; textSize = 9.5f; isAntiAlias = true }

        if (pageIndex == 0) {
            // Cabecera — marca y metadatos del snapshot emitido.
            val logo = logoBitmap
            if (logo != null) {
                val dw = pdfDrawLogo(c, logo, PDF_MARGIN, y - 26f, 150f, 34f)
                c.drawText(s.profile.businessName, PDF_MARGIN + dw + 10f, y + 4f, bold)
                y += 48f
            } else {
                c.drawText(s.profile.businessName, PDF_MARGIN, y, bold); y += 26f
            }
            c.drawText("INFORME", PDF_MARGIN, y, h)
            c.drawText(s.officialNumber ?: s.provisionalNumber, PDF_PAGE_W - PDF_MARGIN - 200f, y, h); y += 24f
            c.drawText("Título: ${s.title}", PDF_MARGIN, y, t); y += PDF_LINE_H
            val destino = if (s.destination == "CLIENTE") "Para el cliente" else "Interno"
            c.drawText("Cliente: ${s.client.name}  ·  $destino", PDF_MARGIN, y, t); y += PDF_LINE_H
            c.drawText("Emitido: ${epochDay(s.savedAtEpochMs)}", PDF_MARGIN, y, t); y += 28f
            c.drawLine(PDF_MARGIN, y - 18f, PDF_PAGE_W - PDF_MARGIN, y - 18f, t)
            c.drawText("Contenido", PDF_MARGIN, y, h); y += PDF_LINE_H
        } else {
            c.drawText("Contenido (continuación)", PDF_MARGIN, y, h); y += PDF_LINE_H
        }
        // Filas — solo las asignadas a esta página y si caben.
        val limit = pdfLinesLimit(isLast)
        for (r in remaining.take(take)) {
            if (y + PDF_LINE_H > limit) break
            val paint = when (r.style) {
                RowStyle.HEAD -> h
                RowStyle.SMALL -> small
                RowStyle.BODY -> t
            }
            c.drawText(r.text, PDF_MARGIN, y, paint); y += PDF_LINE_H
        }
        // Pie — última página.
        if (isLast) {
            y = PDF_FOOTER_TOP + 20f
            val destino = if (s.destination == "CLIENTE") "Para el cliente" else "Interno"
            c.drawText("Informe ${s.officialNumber ?: s.provisionalNumber} v${s.version} · Emitido: ${epochDay(s.savedAtEpochMs)} · $destino", PDF_MARGIN, y, small)
            if (!s.profile.commercialInfo.isNullOrBlank()) {
                y += PDF_LINE_H + 4f
                c.drawText(s.profile.commercialInfo, PDF_MARGIN, y, small)
            }
        }
    }
}
