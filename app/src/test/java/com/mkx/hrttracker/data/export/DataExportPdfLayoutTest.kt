package com.mkx.hrttracker.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page-breaking arithmetic, with a fake measurer.
 *
 * The real renderer needs a `PdfDocument`, which is an inert stub in JVM tests, so
 * the layout is split out precisely so these rules — header repetition, boundary
 * rows, over-tall rows — can be asserted instead of eyeballed on a rendered page.
 */
class DataExportPdfLayoutTest {
    /** Every character is 5pt wide, so a cell's line count is predictable. */
    private val monospaceMeasurer = TextMeasurer { text, _ -> text.length * 5f }

    /** Every character is 10pt wide, standing in for full-width CJK glyphs. */
    private val wideMeasurer = TextMeasurer { text, _ -> text.length * 10f }

    private fun PdfPage.texts(): List<String> = ops.filterIsInstance<PdfDrawOp.Text>().map { it.text }

    @Test
    fun `a document with no rows renders one page carrying the notice`() {
        val pages = DataExportPdfLayout.layout(documentName(emptyList()), monospaceMeasurer)

        assertEquals(1, pages.size)
        assertTrue(pages.single().texts().contains("EMPTY"))
    }

    @Test
    fun `a row ending exactly at the content bottom stays on the page`() {
        // Geometry chosen so two 17pt rows end precisely on the content bottom.
        // A row that fits must not be pushed off, or every table would waste a
        // page's worth of space at each break.
        val geometry = PdfPageGeometry(
            pageWidth = 400f,
            pageHeight = 101f + 2f * 17f,
            margin = 0f,
            footerBand = 0f,
        )
        val pages = DataExportPdfLayout.layout(
            documentName(rows = listOf(listOf("a"), listOf("b"), listOf("c"))),
            monospaceMeasurer,
            geometry,
        )

        assertEquals(2, pages.size)
        assertTrue(pages[0].texts().contains("a"))
        assertTrue(pages[0].texts().contains("b"))
        assertTrue("The third row spilled onto page two", !pages[0].texts().contains("c"))
        assertTrue(pages[1].texts().contains("c"))
    }

    @Test
    fun `the column header is repeated on every page`() {
        val rows = (1..200).map { listOf("r$it") }
        val pages = DataExportPdfLayout.layout(documentName(rows), monospaceMeasurer)

        assertTrue("Expected more than one page", pages.size > 1)
        pages.forEachIndexed { index, page ->
            assertTrue(
                "Page ${index + 1} lost its column header",
                page.texts().contains("H"),
            )
        }
    }

    @Test
    fun `the document title appears only on the first page`() {
        val rows = (1..200).map { listOf("r$it") }
        val pages = DataExportPdfLayout.layout(documentName(rows), monospaceMeasurer)

        assertTrue(pages.first().texts().contains("T"))
        pages.drop(1).forEach { page ->
            assertTrue(!page.texts().contains("T"))
        }
    }

    @Test
    fun `a row taller than a page is clipped rather than dropped`() {
        val tallCell = "x".repeat(400)
        val pages = DataExportPdfLayout.layout(
            documentName(rows = listOf(listOf(tallCell))),
            monospaceMeasurer,
        )

        // The record still appears: its first wrapped line is drawn somewhere, even
        // though the row cannot fit inside one page.
        val drawn = pages.flatMap { it.texts() }
        assertTrue(drawn.any { it.startsWith("x") })
        // And it was clipped to the page rather than drawn past the bottom margin.
        pages.forEach { page ->
            val baselineLimit = PdfPageGeometry().contentBottom + PdfMetrics.TABLE_LINE_HEIGHT
            page.ops.filterIsInstance<PdfDrawOp.Text>().forEach { op ->
                assertTrue(
                    "Text drawn below the content area: ${op.baselineY}",
                    op.baselineY <= baselineLimit,
                )
            }
        }
    }

    @Test
    fun `every row reaches some page`() {
        val rows = (1..120).map { listOf("r$it") }
        val pages = DataExportPdfLayout.layout(documentName(rows), monospaceMeasurer)

        val drawn = pages.flatMap { it.texts() }.toSet()
        rows.forEach { row ->
            assertTrue("Row ${row.single()} was lost", drawn.contains(row.single()))
        }
    }

    @Test
    fun `an empty section is not given a heading`() {
        // Driving the real report: only one of the two tables usually has rows, and
        // an empty one must not leave a bare heading behind.
        val pages = DataExportPdfLayout.layout(
            documentName(rows = listOf(listOf("a")), includeSecondEmptySection = true),
            monospaceMeasurer,
        )

        assertTrue(pages.single().texts().contains("SEC"))
        assertTrue(!pages.single().texts().contains("EMPTYSEC"))
    }

    private fun documentName(
        rows: List<List<String>>,
        includeSecondEmptySection: Boolean = false,
    ): PdfDocumentModel {
        val sections = buildList {
            add(
                PdfSection(
                    title = "SEC",
                    headers = listOf("H"),
                    columnFractions = listOf(1f),
                    rows = rows,
                )
            )
            if (includeSecondEmptySection) {
                add(
                    PdfSection(
                        title = "EMPTYSEC",
                        headers = listOf("H2"),
                        columnFractions = listOf(1f),
                        rows = emptyList(),
                    )
                )
            }
        }
        return PdfDocumentModel(
            title = "T",
            subtitle = "S",
            emptyNotice = "EMPTY",
            sections = sections,
        )
    }

    @Test
    fun `every column is wide enough for its header and widest value`() {
        // CJK glyphs are about twice as wide as Latin ones at the same size, so a
        // column sized against an English sample wraps mid-word once the UI is
        // Chinese. The date column is the tightest: `2026年12月30日` against an
        // English `2026-12-30`.
        val cjkAwareMeasurer = TextMeasurer { text, _ ->
            text.sumOf { character ->
                if (character.code > 0x2E7F) 8.5 else 4.7
            }.toFloat()
        }

        // Atomic columns hold a value that must not be split across lines. The
        // trailing note column is free text and is meant to wrap, so it is only
        // required to fit its own header — and to be the widest column, so long
        // notes get the most room available.
        val doseColumns = listOf(
            "2026年12月30日",
            "22:11",
            "醋酸环丙孕酮",
            "给药途径",
            "1/4 片 · 12.5 mg",
            "雌二醇当量（mg）",
        )
        val labColumns = listOf(
            "2026年12月30日",
            "22:11",
            "PROG",
            "-1234.56",
            "mIU/mL",
            "备注",
        )

        listOf(
            DataExportPdfLayout.DOSE_COLUMN_FRACTIONS to doseColumns,
            DataExportPdfLayout.LAB_COLUMN_FRACTIONS to labColumns,
        ).forEach { (fractions, samples) ->
            assertEquals("Column fractions must fill the content width", 1.0f, fractions.sum(), 0.0001f)
            assertEquals(samples.size, fractions.size)
            samples.forEachIndexed { index, sample ->
                val available = DataExportPdfLayout.columnTextWidth(fractions, index)
                assertTrue(
                    "Column $index is too narrow for '$sample': " +
                        "needs ${cjkAwareMeasurer.width(sample, PdfTextStyle.TABLE_BODY)}, has $available",
                    cjkAwareMeasurer.width(sample, PdfTextStyle.TABLE_BODY) <= available,
                )
            }
        }

        // Only the lab table ends in free text; the dose table's last column is a
        // derived figure.
        val labFractions = DataExportPdfLayout.LAB_COLUMN_FRACTIONS
        assertEquals(
            "The free-text column should be the widest",
            labFractions.max(),
            labFractions.last(),
        )
    }

    @Test
    fun `a Chinese date fits its column without wrapping off the last character`() {
        val cjkAwareMeasurer = TextMeasurer { text, _ ->
            text.sumOf { character ->
                if (character.code > 0x2E7F) 8.5 else 4.7
            }.toFloat()
        }
        val available = DataExportPdfLayout.columnTextWidth(
            DataExportPdfLayout.DOSE_COLUMN_FRACTIONS,
            index = 0,
        )

        val lines = wrapText("2026年12月30日", available, PdfTextStyle.TABLE_BODY, cjkAwareMeasurer)

        assertEquals("The trailing 日 wrapped onto its own line", 1, lines.size)
    }

    @Test
    fun `each cell is measured once rather than once per layout pass`() {
        // Measuring is the expensive half of the layout — with a real Paint each
        // call crosses into Skia — and the height pass used to wrap every cell a
        // second time, which measured as 67 measurer calls per row. This is a guard
        // against reintroducing that second pass, not a pin on the wrapper's
        // internals, so the budget is set well above the current cost.
        val counting = object : TextMeasurer {
            var calls = 0
            override fun width(text: String, style: PdfTextStyle): Float {
                calls++
                return text.length * 5f
            }
        }
        val rows = (1..200).map {
            listOf("row number $it", "22:11", "戊酸雌二醇", "舌下", "2 片 · 2 mg", "0.76")
        }

        DataExportPdfLayout.layout(documentName(rows), counting)

        val perRow = counting.calls.toDouble() / rows.size
        assertTrue(
            "Expected well under 67 measurer calls per row, saw $perRow",
            perRow < 30.0,
        )
    }

    @Test
    fun `wrapping cost is bounded by the column, not by the cell's length`() {
        // The wrapper stops at the first candidate that overflows, so a very long
        // cell must not cost proportionally more than a short one per line.
        val counting = object : TextMeasurer {
            var characters = 0L
            override fun width(text: String, style: PdfTextStyle): Float {
                characters += text.length
                return text.length * 5f
            }
        }

        wrapText("word ".repeat(40), maxWidth = 100f, PdfTextStyle.TABLE_BODY, counting)

        // 200 characters at 20 per line: about 210 measured characters per line.
        assertTrue(
            "Measured ${counting.characters} characters for a 200-char cell",
            counting.characters < 4_000,
        )
    }

    @Test
    fun `wraps latin text at spaces`() {
        val lines = wrapText("hello world", maxWidth = 30f, PdfTextStyle.TABLE_BODY, monospaceMeasurer)

        assertEquals(listOf("hello", "world"), lines)
    }

    @Test
    fun `wraps CJK text at character boundaries`() {
        // Chinese has no spaces, so a word-based wrapper would overflow the cell
        // instead of breaking; the fallback has to cut between characters.
        val lines = wrapText("雌二醇片", maxWidth = 20f, PdfTextStyle.TABLE_BODY, wideMeasurer)

        assertEquals(listOf("雌二", "醇片"), lines)
    }

    @Test
    fun `keeps text that already fits on one line`() {
        val lines = wrapText("short", maxWidth = 100f, PdfTextStyle.TABLE_BODY, monospaceMeasurer)

        assertEquals(listOf("short"), lines)
    }

    @Test
    fun `returns a single empty line for empty text`() {
        val lines = wrapText("", maxWidth = 100f, PdfTextStyle.TABLE_BODY, monospaceMeasurer)

        assertEquals(listOf(""), lines)
    }
}
