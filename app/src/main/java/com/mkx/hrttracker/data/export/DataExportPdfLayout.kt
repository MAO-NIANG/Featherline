package com.mkx.hrttracker.data.export

/**
 * Pagination and table layout for the PDF report.
 *
 * Kept free of Android types so the page-breaking rules can be tested on the JVM:
 * whether a header repeats, whether a row landing exactly on the boundary moves to
 * the next page, and whether an over-tall row survives are all arithmetic that
 * would otherwise only be checkable by eyeballing a rendered document.
 */

/** Measures a string's width in the given style. Backed by a real `Paint` at render time. */
fun interface TextMeasurer {
    fun width(text: String, style: PdfTextStyle): Float
}

enum class PdfTextStyle {
    TITLE,
    SUBTITLE,
    SECTION,
    TABLE_HEADER,
    TABLE_BODY,
}

/** Font size and line advance per style. The renderer builds its Paints from this. */
object PdfMetrics {
    const val TITLE_TEXT_SIZE = 16f
    const val SUBTITLE_TEXT_SIZE = 9f
    const val SECTION_TEXT_SIZE = 13f
    const val TABLE_TEXT_SIZE = 8.5f

    const val TITLE_LINE_HEIGHT = 20f
    const val SUBTITLE_LINE_HEIGHT = 12f
    const val SECTION_LINE_HEIGHT = 17f
    const val TABLE_LINE_HEIGHT = 11f

    /** Space above a section heading, so it never butts against the previous table. */
    const val SECTION_TOP_GAP = 14f

    const val CELL_PADDING_X = 4f
    const val CELL_PADDING_Y = 3f

    fun textSize(style: PdfTextStyle): Float = when (style) {
        PdfTextStyle.TITLE -> TITLE_TEXT_SIZE
        PdfTextStyle.SUBTITLE -> SUBTITLE_TEXT_SIZE
        PdfTextStyle.SECTION -> SECTION_TEXT_SIZE
        PdfTextStyle.TABLE_HEADER,
        PdfTextStyle.TABLE_BODY,
        -> TABLE_TEXT_SIZE
    }

    fun lineHeight(style: PdfTextStyle): Float = when (style) {
        PdfTextStyle.TITLE -> TITLE_LINE_HEIGHT
        PdfTextStyle.SUBTITLE -> SUBTITLE_LINE_HEIGHT
        PdfTextStyle.SECTION -> SECTION_LINE_HEIGHT
        PdfTextStyle.TABLE_HEADER,
        PdfTextStyle.TABLE_BODY,
        -> TABLE_LINE_HEIGHT
    }
}

/**
 * A4 portrait in points. The PDF canvas is measured in points (1/72 inch), not
 * pixels, so nothing here is derived from `dp` or `sp`.
 */
data class PdfPageGeometry(
    val pageWidth: Float = 595f,
    val pageHeight: Float = 842f,
    val margin: Float = 40f,
    val footerBand: Float = 26f,
) {
    val contentLeft: Float get() = margin
    val contentRight: Float get() = pageWidth - margin
    val contentWidth: Float get() = contentRight - contentLeft
    val contentTop: Float get() = margin
    val contentBottom: Float get() = pageHeight - margin - footerBand
}

/** One table in the report, with column widths as fractions of the content width. */
data class PdfSection(
    val title: String,
    val headers: List<String>,
    val columnFractions: List<Float>,
    val rows: List<List<String>>,
)

data class PdfDocumentModel(
    val title: String,
    val subtitle: String,
    /** Shown instead of any table when the report has no data at all. */
    val emptyNotice: String,
    val sections: List<PdfSection>,
)

sealed interface PdfDrawOp {
    data class Text(
        val text: String,
        val x: Float,
        val baselineY: Float,
        val style: PdfTextStyle,
    ) : PdfDrawOp

    /** A horizontal rule under a section heading or table header. */
    data class Rule(val y: Float) : PdfDrawOp
}

data class PdfPage(val ops: List<PdfDrawOp>)

object DataExportPdfLayout {
    /**
     * Column widths as fractions of the content width.
     *
     * The date column is sized for the widest localized date the app writes —
     * the Chinese `2026年12月30日`, which is markedly wider than an English
     * `2026-12-30`. Too narrow, and the trailing 日 wraps onto a second line,
     * which is both ugly and a per-row height multiplier. `DataExportPdfLayoutTest`
     * pins that it fits.
     */
    internal val DOSE_COLUMN_FRACTIONS = listOf(0.17f, 0.08f, 0.22f, 0.13f, 0.19f, 0.21f)
    internal val LAB_COLUMN_FRACTIONS = listOf(0.17f, 0.08f, 0.13f, 0.13f, 0.13f, 0.36f)

    /** Width available to a cell's text, after its horizontal padding. */
    internal fun columnTextWidth(
        columnFractions: List<Float>,
        index: Int,
        geometry: PdfPageGeometry = PdfPageGeometry(),
    ): Float {
        val fraction = columnFractions.getOrNull(index) ?: return 0f
        return fraction * geometry.contentWidth - PdfMetrics.CELL_PADDING_X * 2
    }

    fun layout(
        document: PdfDocumentModel,
        measurer: TextMeasurer,
        geometry: PdfPageGeometry = PdfPageGeometry(),
    ): List<PdfPage> {
        val pages = mutableListOf<PdfPage>()
        val ops = mutableListOf<PdfDrawOp>()
        var y = geometry.contentTop

        // Snapshots the page and clears in place. Reassigning `ops` instead would
        // leave the table emitter — which holds a reference to the list, not the
        // variable — appending the new page's content to the page just closed.
        fun flushPage() {
            pages += PdfPage(ops.toList())
            ops.clear()
            y = geometry.contentTop
        }

        // The title block is page-one furniture; a page break never re-emits it.
        emitTitleBlock(document, measurer, geometry, ops, y).let { y = it }

        val hasRows = document.sections.any { it.rows.isNotEmpty() }
        if (!hasRows) {
            y = emitParagraph(document.emptyNotice, PdfTextStyle.SUBTITLE, measurer, geometry, ops, y)
            flushPage()
            return pages
        }

        document.sections.forEach { section ->
            if (section.rows.isEmpty()) {
                return@forEach
            }
            // Keep the heading with at least its table header rather than stranding
            // a heading at the foot of a page, unless we are already at the top.
            if (y > geometry.contentTop) {
                y += PdfMetrics.SECTION_TOP_GAP
            }
            if (y + PdfMetrics.SECTION_LINE_HEIGHT + PdfMetrics.TABLE_LINE_HEIGHT > geometry.contentBottom &&
                y > geometry.contentTop
            ) {
                flushPage()
            }
            y = emitSectionHeading(section.title, measurer, geometry, ops, y)
            y = emitTable(section, measurer, geometry, ops, y) { flushPage() }
        }

        if (ops.isNotEmpty()) {
            flushPage()
        }
        return pages
    }

    private fun emitTitleBlock(
        document: PdfDocumentModel,
        measurer: TextMeasurer,
        geometry: PdfPageGeometry,
        ops: MutableList<PdfDrawOp>,
        startY: Float,
    ): Float {
        var y = startY
        y = emitParagraph(document.title, PdfTextStyle.TITLE, measurer, geometry, ops, y)
        y = emitParagraph(document.subtitle, PdfTextStyle.SUBTITLE, measurer, geometry, ops, y)
        return y + PdfMetrics.SECTION_TOP_GAP
    }

    private fun emitSectionHeading(
        title: String,
        measurer: TextMeasurer,
        geometry: PdfPageGeometry,
        ops: MutableList<PdfDrawOp>,
        startY: Float,
    ): Float {
        val baseline = startY + PdfMetrics.SECTION_LINE_HEIGHT * 0.8f
        ops += PdfDrawOp.Text(title, geometry.contentLeft, baseline, PdfTextStyle.SECTION)
        val ruleY = startY + PdfMetrics.SECTION_LINE_HEIGHT
        ops += PdfDrawOp.Rule(ruleY)
        return ruleY + 4f
    }

    private fun emitParagraph(
        text: String,
        style: PdfTextStyle,
        measurer: TextMeasurer,
        geometry: PdfPageGeometry,
        ops: MutableList<PdfDrawOp>,
        startY: Float,
    ): Float {
        val lineHeight = PdfMetrics.lineHeight(style)
        var y = startY
        wrapText(text, geometry.contentWidth, style, measurer).forEach { line ->
            ops += PdfDrawOp.Text(line, geometry.contentLeft, y + lineHeight * 0.8f, style)
            y += lineHeight
        }
        return y
    }

    /**
     * Lays out one table, repeatedly re-emitting its column header after a page
     * break and calling [onPageBreak] when the page is full.
     */
    private fun emitTable(
        section: PdfSection,
        measurer: TextMeasurer,
        geometry: PdfPageGeometry,
        ops: MutableList<PdfDrawOp>,
        startY: Float,
        onPageBreak: () -> Unit,
    ): Float {
        val columnWidths = section.columnFractions.map { it * geometry.contentWidth }
        val columnOffsets = buildList {
            var x = geometry.contentLeft
            columnWidths.forEach { width ->
                add(x)
                x += width
            }
        }

        var y = startY
        var pageTop = startY

        fun emitHeader() {
            val height = rowHeight(section.headers, columnWidths, PdfTextStyle.TABLE_HEADER, measurer)
            emitRow(
                cells = section.headers,
                style = PdfTextStyle.TABLE_HEADER,
                columnWidths = columnWidths,
                columnOffsets = columnOffsets,
                rowTop = y,
                rowHeight = height,
                geometry = geometry,
                measurer = measurer,
                ops = ops,
            )
            y += height
            ops += PdfDrawOp.Rule(y)
            y += 3f
        }

        emitHeader()

        section.rows.forEach { row ->
            val height = rowHeight(row, columnWidths, PdfTextStyle.TABLE_BODY, measurer)
            if (y + height > geometry.contentBottom && y > pageTop) {
                onPageBreak()
                y = geometry.contentTop
                pageTop = y
                emitHeader()
            }
            emitRow(
                cells = row,
                style = PdfTextStyle.TABLE_BODY,
                columnWidths = columnWidths,
                columnOffsets = columnOffsets,
                rowTop = y,
                rowHeight = height,
                geometry = geometry,
                measurer = measurer,
                ops = ops,
            )
            y += height
        }
        return y
    }

    private fun emitRow(
        cells: List<String>,
        style: PdfTextStyle,
        columnWidths: List<Float>,
        columnOffsets: List<Float>,
        rowTop: Float,
        rowHeight: Float,
        geometry: PdfPageGeometry,
        measurer: TextMeasurer,
        ops: MutableList<PdfDrawOp>,
    ) {
        val lineHeight = PdfMetrics.lineHeight(style)
        cells.forEachIndexed { index, cell ->
            val width = columnWidths.getOrNull(index) ?: return@forEachIndexed
            val x = columnOffsets.getOrNull(index) ?: return@forEachIndexed
            val available = width - PdfMetrics.CELL_PADDING_X * 2
            if (available <= 0f) {
                return@forEachIndexed
            }
            var lineY = rowTop + PdfMetrics.CELL_PADDING_Y
            wrapText(cell, available, style, measurer).forEach { line ->
                val baseline = lineY + lineHeight * 0.8f
                // A row taller than a whole page is truncated rather than dropped:
                // the record still appears, and the lines that fit are drawn.
                if (baseline <= geometry.contentBottom) {
                    ops += PdfDrawOp.Text(
                        text = line,
                        x = x + PdfMetrics.CELL_PADDING_X,
                        baselineY = baseline,
                        style = style,
                    )
                }
                lineY += lineHeight
            }
        }
    }

    private fun rowHeight(
        cells: List<String>,
        columnWidths: List<Float>,
        style: PdfTextStyle,
        measurer: TextMeasurer,
    ): Float {
        val lineHeight = PdfMetrics.lineHeight(style)
        val maxLines = cells.mapIndexed { index, cell ->
            val width = columnWidths.getOrNull(index) ?: return@mapIndexed 1
            val available = width - PdfMetrics.CELL_PADDING_X * 2
            if (available <= 0f) 1 else wrapText(cell, available, style, measurer).size
        }.maxOrNull() ?: 1
        return maxLines * lineHeight + PdfMetrics.CELL_PADDING_Y * 2
    }
}

/**
 * Greedy word wrap that falls back to breaking mid-word.
 *
 * Latin text breaks at the last space that fits, but CJK has no spaces, so a run
 * with no break opportunity is cut at a character boundary instead. Without that
 * fallback, a Chinese medicine name at a column edge would overflow the cell
 * rather than wrap.
 */
internal fun wrapText(
    text: String,
    maxWidth: Float,
    style: PdfTextStyle,
    measurer: TextMeasurer,
): List<String> {
    if (text.isEmpty()) {
        return listOf("")
    }
    if (maxWidth <= 0f || measurer.width(text, style) <= maxWidth) {
        return listOf(text)
    }

    val lines = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = start
        var lastSpace = -1
        while (end < text.length) {
            val candidate = text.substring(start, end + 1)
            if (end > start && measurer.width(candidate, style) > maxWidth) {
                break
            }
            if (text[end] == ' ') {
                lastSpace = end
            }
            end++
        }

        val fitsToEnd = end >= text.length
        val lineEnd = if (!fitsToEnd && lastSpace >= start) lastSpace else end
        lines += text.substring(start, lineEnd).trimEnd()

        start = if (lineEnd == end) end else lineEnd + 1
    }
    return lines.ifEmpty { listOf("") }
}
