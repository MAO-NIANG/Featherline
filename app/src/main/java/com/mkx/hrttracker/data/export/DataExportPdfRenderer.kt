package com.mkx.hrttracker.data.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import javax.inject.Inject

/**
 * Renders the report with the platform's [PdfDocument].
 *
 * Deliberately dependency-free: the alternative (a PDF library) would add a
 * licence-report entry and a large dependency for a two-table document, and the
 * platform renderer goes through the same Skia font stack as the rest of the app,
 * which is what makes CJK text work without bundling a font.
 *
 * All layout is decided by [DataExportPdfLayout] before anything is drawn —
 * `Canvas.drawText` neither wraps nor clips, so a line that was not measured and
 * split up front is a bug, not a rendering artifact.
 */
class DataExportPdfRenderer @Inject constructor() {
    fun render(
        document: PdfDocumentModel,
        pageLabel: (page: Int, totalPages: Int) -> String,
        out: OutputStream,
        geometry: PdfPageGeometry = PdfPageGeometry(),
    ) {
        val paints = PdfPaints()
        val measurer = TextMeasurer { text, style -> paints.width(text, style) }
        val pages = DataExportPdfLayout.layout(document, measurer, geometry)

        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val pageInfo = PdfDocument.PageInfo
                    .Builder(
                        geometry.pageWidth.toInt(),
                        geometry.pageHeight.toInt(),
                        index + 1,
                    )
                    .create()
                val pdfPage = pdf.startPage(pageInfo)
                val canvas = pdfPage.canvas

                page.ops.forEach { op ->
                    when (op) {
                        is PdfDrawOp.Text ->
                            canvas.drawText(op.text, op.x, op.baselineY, paints.style(op.style))

                        is PdfDrawOp.Rule ->
                            canvas.drawLine(
                                geometry.contentLeft,
                                op.y,
                                geometry.contentRight,
                                op.y,
                                paints.rule,
                            )
                    }
                }

                canvas.drawText(
                    pageLabel(index + 1, pages.size),
                    geometry.contentLeft,
                    geometry.pageHeight - geometry.margin * 0.6f,
                    paints.footer,
                )
                pdf.finishPage(pdfPage)
            }
            // writeTo must come after the last finishPage and before close; closing a
            // document with an unfinished page throws.
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private class PdfPaints {
        // Resolve through the "sans-serif" family rather than a concrete typeface so
        // the framework walks its full fallback chain and reaches Noto Sans CJK for
        // Chinese text instead of drawing tofu boxes.
        private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        private val byStyle: Map<PdfTextStyle, Paint> =
            PdfTextStyle.entries.associateWith { style ->
                Paint().apply {
                    isAntiAlias = true
                    color = Color.BLACK
                    textAlign = Paint.Align.LEFT
                    textSize = PdfMetrics.textSize(style)
                    typeface = if (style.isEmphasised()) bold else regular
                }
            }

        val rule: Paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            strokeWidth = 0.6f
        }

        val footer: Paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textAlign = Paint.Align.LEFT
            textSize = PdfMetrics.SUBTITLE_TEXT_SIZE
            typeface = regular
        }

        fun style(style: PdfTextStyle): Paint = byStyle.getValue(style)

        fun width(text: String, style: PdfTextStyle): Float = byStyle.getValue(style).measureText(text)
    }
}

private fun PdfTextStyle.isEmphasised(): Boolean = when (this) {
    PdfTextStyle.TITLE,
    PdfTextStyle.SECTION,
    PdfTextStyle.TABLE_HEADER,
    -> true

    PdfTextStyle.SUBTITLE,
    PdfTextStyle.TABLE_BODY,
    -> false
}
