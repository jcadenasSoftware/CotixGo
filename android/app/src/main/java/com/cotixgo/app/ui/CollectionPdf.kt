package com.cotixgo.app.ui

import android.content.Context
import android.graphics.Canvas
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
import com.cotixgo.app.data.AppliedRetentionSnapshot
import com.cotixgo.app.data.ClientSnapshot
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.CollectionAccountItemEntity
import com.cotixgo.app.data.LogoStore
import com.cotixgo.app.data.ProfileSnapshot
import com.cotixgo.app.data.SnapshotJson
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Vista previa PDF de una Cuenta de cobro a partir del documento congelado:
 * snapshots embebidos en la entidad (cliente, perfil y retenciones
 * persistidos en la misma transacción que las líneas) más
 * `collection_account_items`, inmutable tras el primer pago. Nunca se leen
 * el perfil, el cliente ni las retenciones vivos; el logo se decodifica solo
 * desde `ProfileSnapshot.logoRef`.
 *
 * Papel Carta (612×792 pt) y maquetación comercial: marca, "CUENTA DE
 * COBRO" + número, destinatario, conceptos en tabla ligera, neto a cobrar
 * destacado y condiciones/comercialInfo congelados al final.
 */
data class CollectionPdfDoc(
    val number: String,
    val status: String,
    val currency: String,
    val issueDate: String,
    val dueDate: String?,
    val client: ClientSnapshot,
    val profile: ProfileSnapshot,
    val items: List<CollectionAccountItemEntity>,
    val retentions: List<AppliedRetentionSnapshot>,
    val gross: String,
    val totalRetained: String,
    val net: String,
    val appliedAmount: String,
    val balance: String,
    val conditions: String,
)

/**
 * Documento imprimible desde los snapshots embebidos de la Cuenta. null si
 * el registro no conserva los snapshots completos (datos antiguos) — la UI
 * muestra el estado claro en lugar de completar datos desde entidades vivas.
 */
fun collectionPdfDoc(
    account: CollectionAccountEntity,
    items: List<CollectionAccountItemEntity>,
): CollectionPdfDoc? {
    if (account.clientSnapshotJson.isBlank() || account.profileSnapshotJson.isBlank()) return null
    return runCatching {
        CollectionPdfDoc(
            number = account.officialNumber ?: account.provisionalNumber,
            status = account.status,
            currency = account.currency,
            issueDate = Instant.ofEpochMilli(account.issuedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            dueDate = account.dueDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() },
            client = SnapshotJson.json.decodeFromString(ClientSnapshot.serializer(), account.clientSnapshotJson),
            profile = SnapshotJson.json.decodeFromString(ProfileSnapshot.serializer(), account.profileSnapshotJson),
            retentions = SnapshotJson.decodeRetentions(account.retentionsSnapshotJson),
            items = items.sortedBy { it.position },
            gross = account.gross,
            totalRetained = account.totalRetained,
            net = account.net,
            appliedAmount = account.appliedAmount,
            balance = account.balance,
            conditions = account.conditions,
        )
    }.getOrNull()
}

fun printCollectionPdf(context: Context, doc: CollectionPdfDoc) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(
        "cotixgo-${doc.number}",
        CollectionPdfAdapter(doc, context.applicationContext),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .build(),
    )
}

private const val CP_PAGE_W = 612           // Carta: 612×792 pt
private const val CP_PAGE_H = 792
private const val CP_MARGIN = 48f
private const val CP_RIGHT = CP_PAGE_W - CP_MARGIN          // 564
private const val CP_LINE_H = 16f
private const val CP_BLOCK = 34f            // concepto: desc(14) + detalle(20)
private const val CP_TOP_FIRST = 196f       // fin de cabecera pág. 1
private const val CP_TOP_FIRST_LOGO = 218f  // con bloque de logo (+22)
private const val CP_TOP_CONT = 96f
private const val CP_LIMIT = CP_PAGE_H - CP_MARGIN - 36f    // 708
private const val CP_FOOTER_TOP = CP_PAGE_H - CP_MARGIN - 158f // 586

private class CPaints {
    val brand = Paint().apply { color = PDF_NAVY; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val docTag = Paint().apply { color = PDF_BLUE; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val num = Paint().apply { color = PDF_NAVY; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val head = Paint().apply { color = PDF_NAVY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val body = Paint().apply { color = android.graphics.Color.rgb(0x1A, 0x22, 0x38); textSize = 10.5f; isAntiAlias = true }
    val meta = Paint().apply { color = PDF_SLATE; textSize = 9.5f; isAntiAlias = true }
    val small = Paint().apply { color = PDF_SLATE; textSize = 9f; isAntiAlias = true }
    val ruleBold = Paint().apply { color = PDF_NAVY; strokeWidth = 1.5f }
    val ruleHair = Paint().apply { color = PDF_HAIR; strokeWidth = 0.4f }
    val band = Paint().apply { color = PDF_BAND }
    val neto = Paint().apply { color = PDF_NAVY; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
}

private fun drawRight(c: Canvas, p: Paint, text: String, right: Float, y: Float) =
    c.drawText(text, right - p.measureText(text), y, p)

private class CollectionPdfAdapter(
    private val s: CollectionPdfDoc,
    private val appCtx: Context,
) : PrintDocumentAdapter() {
    private val paints by lazy { CPaints() }
    // Logo congelado del snapshot — nunca el perfil vivo.
    private val logoBitmap by lazy {
        s.profile.logoRef?.let { LogoStore.decode(appCtx, it) }
    }
    private val topFirst get() =
        if (logoBitmap != null) CP_TOP_FIRST_LOGO else CP_TOP_FIRST
    private val splits by lazy {
        pdfPageSplits(
            s.items.size, CP_BLOCK,
            topFirst = topFirst, topCont = CP_TOP_CONT,
            limit = CP_LIMIT, footerTop = CP_FOOTER_TOP,
        )
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled(); return
        }
        val info = PrintDocumentInfo.Builder("cuenta-cobro.pdf")
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
        var remaining = s.items.toList()
        splits.forEachIndexed { pageIndex, take ->
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(CP_PAGE_W, CP_PAGE_H, pageIndex + 1).create()
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
        remaining: List<CollectionAccountItemEntity>,
        isLast: Boolean,
    ) {
        val p = paints
        var y = CP_MARGIN

        if (pageIndex == 0) {
            // Cabecera comercial: marca/logo, tipo y número del documento.
            val logo = logoBitmap
            if (logo != null) {
                val dw = pdfDrawLogo(c, logo, CP_MARGIN, y - 26f, 150f, 34f)
                c.drawText(s.profile.businessName, CP_MARGIN + dw + 10f, y + 4f, p.brand)
                drawRight(c, p.docTag, "CUENTA DE COBRO", CP_RIGHT, y + 2f)
                y += 44f
            } else {
                c.drawText(s.profile.businessName, CP_MARGIN, y + 4f, p.brand)
                drawRight(c, p.docTag, "CUENTA DE COBRO", CP_RIGHT, y + 2f)
                y += 22f
            }
            drawRight(c, p.num, s.number, CP_RIGHT, y)
            y += 14f
            c.drawLine(CP_MARGIN, y, CP_RIGHT, y, p.ruleBold)
            y += 20f
            val vence = s.dueDate?.let { "Vence: $it" } ?: "Sin vencimiento"
            c.drawText("Emitida: ${s.issueDate}", CP_MARGIN, y, p.meta)
            c.drawText(vence, CP_MARGIN + 170f, y, p.meta)
            drawRight(c, p.meta, "${s.currency} · ${s.status}", CP_RIGHT, y)
            y += 15f
            c.drawText("Cliente:", CP_MARGIN, y, p.meta)
            c.drawText(s.client.name, CP_MARGIN + 62f, y, p.head)
            y += 14f
            val contact = listOf(s.client.phone, s.client.email, s.client.address)
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            if (contact.isNotBlank()) {
                c.drawText(contact, CP_MARGIN + 62f, y, p.meta); y += 12f
            }
            y += 8f
            c.drawText("Conceptos", CP_MARGIN, y, p.head); y += 14f
            c.drawText("CONCEPTO", CP_MARGIN, y, p.small)
            drawRight(c, p.small, "TOTAL", CP_RIGHT, y)
            y += 8f
            c.drawLine(CP_MARGIN, y - 4f, CP_RIGHT, y - 4f, p.ruleHair)
            // El draw debe empezar exactamente donde el planificador dice —
            // empezar antes permite una fila que el plan ya asignó pero que
            // después se corta en silencio (p. ej. A16 perdida).
            if (y < topFirst) y = topFirst
        } else {
            c.drawText(s.profile.businessName, CP_MARGIN, y, p.meta)
            drawRight(c, p.meta, "CUENTA DE COBRO ${s.number}", CP_RIGHT, y)
            y += 12f
            c.drawLine(CP_MARGIN, y, CP_RIGHT, y, p.ruleBold)
            y += 18f
            c.drawText("Conceptos (continuación)", CP_MARGIN, y, p.head); y += 14f
            y += 4f
            if (y < CP_TOP_CONT) y = CP_TOP_CONT
        }
        // Líneas — solo las asignadas a esta página y si caben enteras.
        val limit = if (isLast) CP_FOOTER_TOP - CP_BLOCK else CP_LIMIT
        for (l in remaining.take(take)) {
            if (y + CP_BLOCK > limit) break
            c.drawText(l.description, CP_MARGIN, y, p.body)
            drawRight(c, p.body, l.lineTotal, CP_RIGHT, y)
            y += CP_LINE_H - 2f
            c.drawText("[${l.conceptType}] · ${l.quantity} ${l.unit} × ${l.unitPrice}",
                CP_MARGIN + 12f, y, p.small)
            y += CP_LINE_H + 4f
            c.drawLine(CP_MARGIN, y - 6f, CP_RIGHT, y - 6f, p.ruleHair)
        }
        // Pie/totales — última página.
        if (isLast) {
            var fy = CP_FOOTER_TOP
            c.drawLine(CP_MARGIN, fy - 10f, CP_RIGHT, fy - 10f, p.ruleHair)
            s.retentions.forEach { r ->
                c.drawText("${r.name}: ${r.ratePercent}% sobre ${r.base} = ${r.amount}",
                    CP_MARGIN, fy, p.small); fy += CP_LINE_H - 4f
            }
            fy += 4f
            drawRight(c, p.meta, "Bruto: ${s.gross} ${s.currency}", CP_RIGHT, fy); fy += 6f
            drawRight(c, p.meta, "Retenido: −${s.totalRetained} ${s.currency}", CP_RIGHT, fy + CP_LINE_H)
            fy += CP_LINE_H * 2
            c.drawRect(CP_RIGHT - 240f, fy - 12f, CP_RIGHT, fy + 8f, p.band)
            c.drawText("Total a cobrar", CP_RIGHT - 232f, fy + 2f, p.neto)
            drawRight(c, p.neto, "${s.net} ${s.currency}", CP_RIGHT - 8f, fy + 2f)
            fy += 26f
            drawRight(c, p.meta, "Aplicado: ${s.appliedAmount} · Saldo: ${s.balance}",
                CP_RIGHT, fy)
            fy += 18f
            if (s.conditions.isNotBlank()) {
                c.drawText("CONDICIONES", CP_MARGIN, fy, p.small); fy += 13f
                c.drawText(s.conditions, CP_MARGIN, fy, p.meta); fy += CP_LINE_H
            }
            if (!s.profile.commercialInfo.isNullOrBlank()) {
                fy += 4f
                c.drawText("INFORMACIÓN COMERCIAL", CP_MARGIN, fy, p.small); fy += 13f
                c.drawText(s.profile.commercialInfo, CP_MARGIN, fy, p.meta)
            }
        }
    }
}
