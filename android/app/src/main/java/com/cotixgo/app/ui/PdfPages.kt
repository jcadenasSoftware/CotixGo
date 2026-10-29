package com.cotixgo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF

/**
 * Geometría A4 y paginación de conceptos compartida por las vistas previas
 * PDF locales (Cotización y Cuenta de cobro). El reparto se calcula por
 * capacidad real: ninguna línea se descarta sin dibujar y la última página
 * reserva espacio para el pie.
 */
internal const val PDF_PAGE_W = 595          // A4 en pt aprox.
internal const val PDF_PAGE_H = 842

/** Paleta sobria de los documentos al cliente (misma del tema Compose). */
internal val PDF_NAVY = android.graphics.Color.rgb(0x0A, 0x16, 0x33)
internal val PDF_BLUE = android.graphics.Color.rgb(0x2E, 0x7C, 0xF6)
internal val PDF_SLATE = android.graphics.Color.rgb(0x54, 0x61, 0x7E)
internal val PDF_HAIR = android.graphics.Color.rgb(0xD9, 0xDE, 0xE8)
internal val PDF_BAND = android.graphics.Color.rgb(0xDC, 0xE9, 0xFF)

/**
 * Dibuja el logo del snapshot ajustado a una caja (proporción conservada,
 * centrado vertical). Devuelve el ancho ocupado — el texto de marca se
 * coloca a continuación. La referencia y el archivo son inmutables, así el
 * documento siempre muestra el logo congelado, no el perfil actual.
 */
internal fun pdfDrawLogo(
    c: Canvas, bmp: Bitmap,
    x: Float, top: Float, maxW: Float, maxH: Float,
): Float {
    val scale = minOf(maxW / bmp.width, maxH / bmp.height)
    val dw = bmp.width * scale
    val dh = bmp.height * scale
    c.drawBitmap(bmp, null, RectF(x, top, x + dw, top + dh), null)
    return dw
}
internal const val PDF_MARGIN = 48f
internal const val PDF_LINE_H = 16f

// Alto que consume cada concepto (descripción + detalle: (LINE_H-2)+(LINE_H+2)).
// PDF_LINES_TOP_* deben coincidir con el y que deja la cabecera de cada
// renderizador en cada tipo de página (misma estructura de 5 filas en pág. 1
// y una fila en continuaciones).
internal const val PDF_LINE_BLOCK = 2 * PDF_LINE_H            // 32f
internal const val PDF_LINES_TOP_FIRST = 174f
internal const val PDF_LINES_TOP_CONT = 64f
internal const val PDF_LINES_LIMIT = PDF_PAGE_H - PDF_MARGIN - 40f   // 754f
internal const val PDF_FOOTER_TOP = PDF_PAGE_H - PDF_MARGIN - 130f   // 664f

internal fun pdfLinesTop(pageIndex: Int): Float =
    if (pageIndex == 0) PDF_LINES_TOP_FIRST else PDF_LINES_TOP_CONT

internal fun pdfLinesLimit(isLast: Boolean): Float =
    if (isLast) PDF_FOOTER_TOP - PDF_LINE_BLOCK else PDF_LINES_LIMIT

/**
 * Filas que caben en la página: límite distinto si el pie ocupa abajo.
 * rowH = PDF_LINE_BLOCK para conceptos de dos filas (cotización/cuenta);
 * PDF_LINE_H para filas de texto sueltas (informe).
 */
internal fun pdfLineCapacity(
    pageIndex: Int, isLast: Boolean, rowH: Float = PDF_LINE_BLOCK,
    topFirst: Float = PDF_LINES_TOP_FIRST, topCont: Float = PDF_LINES_TOP_CONT,
    limit: Float = PDF_LINES_LIMIT, footerTop: Float = PDF_FOOTER_TOP,
): Int {
    var y = if (pageIndex == 0) topFirst else topCont
    val lim = if (isLast) footerTop - PDF_LINE_BLOCK else limit
    var n = 0
    while (y + rowH <= lim) { y += rowH; n++ }
    return n
}

/**
 * Reparto de líneas por página según capacidad real (nunca se descartan
 * líneas no dibujadas). La última página reserva espacio para el pie: si
 * todo cupiera pero el pie no, se dejan líneas para una página extra.
 */
internal fun pdfPageSplits(
    lineCount: Int, rowH: Float = PDF_LINE_BLOCK,
    topFirst: Float = PDF_LINES_TOP_FIRST, topCont: Float = PDF_LINES_TOP_CONT,
    limit: Float = PDF_LINES_LIMIT, footerTop: Float = PDF_FOOTER_TOP,
): List<Int> {
    val out = mutableListOf<Int>()
    var rem = lineCount
    var idx = 0
    while (rem > 0) {
        val take = when {
            rem <= pdfLineCapacity(idx, isLast = true, rowH, topFirst, topCont, limit, footerTop) -> rem
            rem <= pdfLineCapacity(idx, isLast = false, rowH, topFirst, topCont, limit, footerTop) ->
                pdfLineCapacity(idx, isLast = true, rowH, topFirst, topCont, limit, footerTop)
            else -> pdfLineCapacity(idx, isLast = false, rowH, topFirst, topCont, limit, footerTop)
        }
        out += take
        rem -= take
        idx++
    }
    return out.ifEmpty { listOf(0) }
}
