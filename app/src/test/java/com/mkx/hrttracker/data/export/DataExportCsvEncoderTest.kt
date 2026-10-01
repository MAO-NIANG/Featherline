package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.data.export.DataExportCsvEncoder.escapeField
import com.mkx.hrttracker.data.export.DataExportCsvEncoder.offsetDateTime
import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.medication.DoseInstruction
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationKey
import com.mkx.hrttracker.model.medication.MedicinePreparation
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExportCsvEncoderTest {
    private val labels = testLabels()

    private fun encode(bundle: DataExportBundle): String =
        DataExportCsvEncoder.encodeToString(bundle, labels)

    private fun dataLines(csv: String): List<String> =
        csv.removePrefix(DataExportCsvEncoder.UTF8_BOM.toString())
            .split("\r\n")
            .filter { it.isNotEmpty() }

    @Test
    fun `starts with a UTF-8 BOM so Excel reads the file as UTF-8`() {
        val csv = encode(bundleOf())

        assertEquals(DataExportCsvEncoder.UTF8_BOM, csv.first())
    }

    @Test
    fun `separates records with CRLF as RFC 4180 requires`() {
        val csv = encode(
            bundleOf(
                doses = listOf(
                    doseRow(
                        medicine = catalogMedicine(
                            MedicationKey.ESTRADIOL,
                            MedicinePreparation.Pill(2.0),
                        ),
                        applicationType = MedicationApplicationType.ORAL,
                        id = "one",
                    )
                )
            )
        )

        // One header row plus one data row, each terminated.
        assertTrue(csv.endsWith("\r\n"))
        val withoutBom = csv.removePrefix(DataExportCsvEncoder.UTF8_BOM.toString())
        assertEquals(3, withoutBom.split("\r\n").size)
    }

    @Test
    fun `an empty bundle still writes the header row`() {
        val csv = encode(bundleOf())

        val lines = dataLines(csv)
        assertEquals(1, lines.size)
        assertEquals(labels.csvHeaders.joinToString(","), lines.single())
    }

    @Test
    fun `quotes a field containing a comma`() {
        assertEquals("\"a,b\"", escapeField("a,b"))
    }

    @Test
    fun `quotes a field containing a quote and doubles the quote`() {
        assertEquals("\"say \"\"hi\"\"\"", escapeField("say \"hi\""))
    }

    @Test
    fun `quotes a field containing a newline rather than dropping it`() {
        assertEquals("\"line one\nline two\"", escapeField("line one\nline two"))
        assertEquals("\"line one\r\nline two\"", escapeField("line one\r\nline two"))
    }

    @Test
    fun `leaves an ordinary field unquoted`() {
        assertEquals("Estradiol", escapeField("Estradiol"))
        assertEquals("", escapeField(""))
    }

    @Test
    fun `quotes a note that would otherwise break the row apart`() {
        val csv = encode(
            bundleOf(
                labs = listOf(
                    labRow(
                        analyteKey = BloodAnalyteKey.E2,
                        canonicalValue = 120.0,
                        notes = "fasted, 08:00",
                    )
                )
            )
        )

        assertTrue(csv.contains("\"fasted, 08:00\""))
    }

    @Test
    fun `writes numbers without a locale-specific decimal separator`() {
        // A German device formats a double as "1,5", which would split a CSV field.
        // The encoder must not consult the default locale for numbers.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val csv = encode(
                bundleOf(
                    doses = listOf(
                        doseRow(
                            medicine = catalogMedicine(
                                MedicationKey.ESTRADIOL_VALERATE,
                                MedicinePreparation.InjectionSingleUseVial(10.0),
                            ),
                            applicationType = MedicationApplicationType.INJECTION,
                            amountMg = 1.5,
                            id = "german-locale",
                        )
                    ),
                    labs = listOf(
                        labRow(BloodAnalyteKey.E2, canonicalValue = 0.25, value = 0.25)
                    ),
                )
            )

            assertTrue("Amount column was not locale-neutral: $csv", csv.contains(",1.5,"))
            assertTrue("Lab value was not locale-neutral: $csv", csv.contains(",0.25,"))
            assertTrue("A comma decimal separator leaked into the file", !csv.contains(",1,5,"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `drops trailing zeros instead of padding the number`() {
        val csv = encode(
            bundleOf(
                doses = listOf(
                    doseRow(
                        medicine = catalogMedicine(
                            MedicationKey.ESTRADIOL,
                            MedicinePreparation.Pill(2.0),
                        ),
                        applicationType = MedicationApplicationType.ORAL,
                        amountMg = 2.0,
                        id = "whole-number",
                    )
                )
            )
        )

        assertTrue("Expected 2 rather than 2.0: $csv", csv.contains(",2,"))
    }

    @Test
    fun `merges doses and labs into one chronological table`() {
        val csv = encode(
            bundleOf(
                doses = listOf(
                    doseRow(
                        medicine = catalogMedicine(
                            MedicationKey.ESTRADIOL,
                            MedicinePreparation.Pill(2.0),
                        ),
                        applicationType = MedicationApplicationType.ORAL,
                        appliedAt = TEST_INSTANT.plusSeconds(600),
                        id = "later-dose",
                    )
                ),
                labs = listOf(
                    labRow(
                        analyteKey = BloodAnalyteKey.E2,
                        canonicalValue = 120.0,
                        collectedAt = TEST_INSTANT,
                    )
                ),
            )
        )

        val lines = dataLines(csv)
        assertTrue("The lab came first", lines[1].startsWith(labels.valueLab))
        assertTrue("The dose came second", lines[2].startsWith(labels.valueDose))
    }

    @Test
    fun `orders records at the same instant deterministically`() {
        // Doses sort before labs on a tie, and the whole file must be byte-identical
        // across runs of the same data.
        val bundle = bundleOf(
            doses = listOf(
                doseRow(
                    medicine = catalogMedicine(
                        MedicationKey.ESTRADIOL,
                        MedicinePreparation.Pill(2.0),
                    ),
                    applicationType = MedicationApplicationType.ORAL,
                    id = "tie-dose",
                )
            ),
            labs = listOf(labRow(BloodAnalyteKey.E2, canonicalValue = 120.0)),
        )

        val first = encode(bundle)
        val second = encode(bundle)
        assertEquals(first, second)

        val lines = dataLines(first)
        assertTrue(lines[1].startsWith(labels.valueDose))
        assertTrue(lines[2].startsWith(labels.valueLab))
    }

    @Test
    fun `writes the timestamp as an ISO offset date time in the record's own zone`() {
        assertEquals(
            "2026-10-02T16:30:00+08:00",
            offsetDateTime(Instant.parse("2026-10-02T08:30:00Z"), "Asia/Shanghai"),
        )
    }

    @Test
    fun `falls back to UTC for an unparseable zone rather than failing the export`() {
        assertEquals(
            "2026-10-02T08:30:00Z",
            offsetDateTime(Instant.parse("2026-10-02T08:30:00Z"), "Not/AZone"),
        )
    }

    @Test
    fun `leaves dose-only columns blank on a lab row and the reverse`() {
        val csv = encode(
            bundleOf(
                doses = listOf(
                    doseRow(
                        medicine = catalogMedicine(
                            MedicationKey.ESTRADIOL,
                            MedicinePreparation.Pill(2.0),
                        ),
                        applicationType = MedicationApplicationType.ORAL,
                        amountMg = 2.0,
                        id = "dose-only",
                    )
                ),
                labs = listOf(labRow(BloodAnalyteKey.E2, canonicalValue = 120.0)),
            )
        )

        val lines = dataLines(csv)
        val doseFields = lines[1].split(",")
        val labFields = lines[2].split(",")
        // Columns: 0 type, 1 datetime, 2 zone, 3 category, 4 name, 5 route,
        // 6 dose, 7 amount mg, 8 value, 9 unit, 10 note.
        // A lab row names its analyte in the shared name column, so the only blanks
        // it carries are the category, route, dose and amount columns.
        assertEquals(listOf("", "", ""), doseFields.subList(8, 11))
        assertEquals("", labFields[3])
        assertEquals("", labFields[5])
        assertEquals(listOf("", ""), labFields.subList(6, 8))
        assertTrue("A lab row must name its analyte", labFields[4].isNotEmpty())
        assertTrue("A lab row must carry its value", labFields[8].isNotEmpty())
    }
}
