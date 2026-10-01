package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.util.formatCalibrationUnitLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes the doses-and-labs table as RFC 4180 CSV.
 *
 * Pure Kotlin: no Android types, so the escaping and ordering rules are unit
 * testable on the JVM.
 */
object DataExportCsvEncoder {
    /**
     * Excel on Windows reads a BOM-less UTF-8 CSV as the ANSI codepage and turns
     * any non-ASCII field into mojibake — including every Chinese header and any
     * medicine name the user typed. The BOM costs a `utf-8-sig` flag for
     * `pandas` readers; that trade is the right way round for a file whose
     * audience is a person opening it in a spreadsheet.
     */
    const val UTF8_BOM: Char = '\uFEFF'

    /** RFC 4180 mandates CRLF, and every mainstream spreadsheet accepts it. */
    private const val RECORD_SEPARATOR = "\r\n"

    private val offsetDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun write(
        bundle: DataExportBundle,
        labels: DataExportLabels,
        out: Appendable,
    ) {
        out.append(UTF8_BOM)
        appendRecord(out, labels.csvHeaders)
        buildRecords(bundle, labels).forEach { record ->
            appendRecord(out, record)
        }
    }

    /**
     * Convenience for tests — returns one String.
     *
     * Production goes through [write], which appends to the sink instead of building
     * a String. It still materializes the row set first, because doses and labs are
     * merged into one chronological order and that needs every sort key before the
     * first record can be emitted; at 20k rows that costs ~24 ms and a few MB.
     */
    fun encodeToString(
        bundle: DataExportBundle,
        labels: DataExportLabels,
    ): String = StringBuilder().also { write(bundle, labels, it) }.toString()

    /**
     * The row-set, already ordered. Doses and labs share one chronological
     * timeline so the file reads as a single history rather than two appended
     * tables; ties break on the record-type column and then the name column so
     * repeated exports of the same data are byte-identical.
     */
    private fun buildRecords(
        bundle: DataExportBundle,
        labels: DataExportLabels,
    ): List<List<String>> {
        val records = buildList {
            bundle.doseRows.forEach { row ->
                add(
                    CsvRecord(
                        instant = row.appliedAt,
                        typeOrder = 0,
                        name = row.medicineName,
                        fields = listOf(
                            labels.valueDose,
                            offsetDateTime(row.appliedAt, row.appliedAtTimeZoneId),
                            row.appliedAtTimeZoneId,
                            row.categoryLabel,
                            row.medicineName,
                            row.routeLabel,
                            row.doseText,
                            exportNumber(row.amountMg),
                            "",
                            "",
                            "",
                        ),
                    )
                )
            }
            bundle.labRows.forEach { row ->
                add(
                    CsvRecord(
                        instant = row.collectedAt,
                        typeOrder = 1,
                        name = row.analyteLabel,
                        fields = listOf(
                            labels.valueLab,
                            offsetDateTime(row.collectedAt, row.collectedAtTimeZoneId),
                            row.collectedAtTimeZoneId,
                            "",
                            row.analyteLabel,
                            "",
                            "",
                            "",
                            exportNumber(row.value),
                            formatCalibrationUnitLabel(row.unitSnapshot),
                            row.panelNotes.orEmpty(),
                        ),
                    )
                )
            }
        }
        return records
            .sortedWith(
                compareBy<CsvRecord> { it.instant }
                    .thenBy { it.typeOrder }
                    .thenBy { it.name }
            )
            .map { it.fields }
    }

    private fun appendRecord(out: Appendable, fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) {
                out.append(',')
            }
            out.append(escapeField(field))
        }
        out.append(RECORD_SEPARATOR)
    }

    /**
     * Quotes a field only when it has to be quoted, doubling any embedded quote.
     *
     * A field containing CR or LF is quoted rather than stripped: the newline is
     * the note's own text, and the RFC has a way to carry it.
     */
    internal fun escapeField(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuoting) {
            return field
        }
        return buildString(field.length + 2) {
            append('"')
            field.forEach { character ->
                if (character == '"') {
                    append("\"\"")
                } else {
                    append(character)
                }
            }
            append('"')
        }
    }

    /** "2026-10-02T08:30:00+08:00" — unambiguous, and carries the record's own offset. */
    internal fun offsetDateTime(instant: Instant, zoneId: String): String {
        return try {
            instant.atZone(ZoneId.of(zoneId)).format(offsetDateTimeFormatter)
        } catch (_: Exception) {
            // An unknown or malformed zone id must not fail the whole export;
            // fall back to UTC, which is at least unambiguous.
            instant.atZone(ZoneId.of("UTC")).format(offsetDateTimeFormatter)
        }
    }

    private data class CsvRecord(
        val instant: Instant,
        val typeOrder: Int,
        val name: String,
        val fields: List<String>,
    )
}
