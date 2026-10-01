package com.mkx.hrttracker.data.export

import android.net.Uri
import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.medication.DoseInstruction
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationCategory
import com.mkx.hrttracker.model.medication.Medicine
import java.time.Instant
import java.util.UUID

/** Which file a prepared export will produce. */
enum class DataExportFormat {
    CSV,
    PDF,
    JSON,
}

/**
 * JSON has two shapes with different audiences and different fidelity.
 *
 * [OYAMA] is the interchange format: readable by Oyama's HRT Tracker and by this
 * app's own external-import path, at the cost of dropping everything that format
 * cannot express. [NATIVE] is this app's own backup snapshot, complete but
 * readable only by this app's (encrypted-only) restore path.
 */
enum class JsonExportFormat {
    OYAMA,
    NATIVE,
}

/**
 * One logged dose, carrying both the raw values an encoder needs to re-derive
 * semantics and the display strings a human-facing file needs.
 *
 * The display strings are resolved once at bundle-build time against the
 * app-language context, so [DataExportCsvEncoder] and the PDF renderer stay free
 * of Android string lookups and remain unit-testable.
 */
data class DoseExportRow(
    val uuid: UUID,
    val appliedAt: Instant,
    val appliedAtTimeZoneId: String,
    val category: MedicationCategory,
    val applicationType: MedicationApplicationType,
    val medicine: Medicine?,
    val doseInstruction: DoseInstruction,
    val count: Int,
    val doseAmountDelta: Double?,
    val equivalentE2Mg: Double?,
    /** Null for a PATCH_OFF entry (no medicine to measure). */
    val amountMg: Double?,
    val categoryLabel: String,
    val medicineName: String,
    val routeLabel: String,
    /** Localized "1½ tablet · 1.5 mg" summary; empty for PATCH_OFF / Noop doses. */
    val doseText: String,
    /** Localized short date for the PDF report, in [appliedAtTimeZoneId]. */
    val dateText: String,
    /** Localized short time for the PDF report, in [appliedAtTimeZoneId]. */
    val timeText: String,
)

/**
 * One blood-test result, flattened out of its panel so that CSV and PDF can list
 * results and doses on one timeline. Panel-level fields are denormalized onto
 * every result.
 */
data class LabExportRow(
    val uuid: UUID,
    val panelUuid: UUID,
    val collectedAt: Instant,
    val collectedAtTimeZoneId: String,
    val panelNotes: String?,
    /** Null when the result is for a user-defined analyte. */
    val analyteKey: BloodAnalyteKey?,
    /** Value in the analyte's canonical unit — E2 in pg/mL, T in ng/dL. */
    val canonicalValue: Double,
    /** The value exactly as the user entered it, in [unitSnapshot]. */
    val value: Double,
    /**
     * For a built-in analyte this is a [BloodUnitKey] storage value ("pg_ml");
     * for a custom analyte it is the user's own unit text. Resolve through
     * `formatCalibrationUnitLabel`, which handles both.
     */
    val unitSnapshot: String,
    val analyteLabel: String,
    /** Localized short date for the PDF report, in [collectedAtTimeZoneId]. */
    val dateText: String,
    /** Localized short time for the PDF report, in [collectedAtTimeZoneId]. */
    val timeText: String,
)

/**
 * Everything the file encoders need, already localized and chronologically
 * sorted. Built by [DataExportService.buildBundle].
 */
data class DataExportBundle(
    val generatedAt: Instant,
    val doseRows: List<DoseExportRow>,
    val labRows: List<LabExportRow>,
    val weightKg: Double?,
) {
    val isEmpty: Boolean
        get() = doseRows.isEmpty() && labRows.isEmpty()
}

/**
 * Counts shown to the user before they commit to an export, so a lossy format
 * (Oyama) can never silently drop records.
 */
data class DataExportSummary(
    val doseCount: Int,
    val labCount: Int,
    val representableDoseCount: Int,
    val representableLabCount: Int,
) {
    val omittedDoseCount: Int
        get() = doseCount - representableDoseCount

    val omittedLabCount: Int
        get() = labCount - representableLabCount

    val hasOmissions: Boolean
        get() = omittedDoseCount > 0 || omittedLabCount > 0

    val isEmpty: Boolean
        get() = doseCount == 0 && labCount == 0

    /**
     * The figures to quote for an export in the given format.
     *
     * An Oyama-compatible file holds only the representable rows, so its report
     * has to name the shortfall; every other format writes everything and has
     * nothing to declare. Kept here rather than in the composable so the choice is
     * unit-testable — reporting the wrong count is how a lossy export stops being
     * honest.
     */
    fun reportFor(oyamaCompatible: Boolean): DataExportReport =
        if (oyamaCompatible) {
            DataExportReport(
                doses = representableDoseCount,
                labs = representableLabCount,
                omitted = omittedDoseCount + omittedLabCount,
            )
        } else {
            DataExportReport(doses = doseCount, labs = labCount, omitted = 0)
        }
}

/** What a finished export should tell the user it wrote. */
data class DataExportReport(
    val doses: Int,
    val labs: Int,
    val omitted: Int,
)

/**
 * An export rendered into a cache temp file, awaiting the user's choice of
 * destination. Mirrors `PreparedBackupExport`: holding the bytes on disk keeps
 * the payload out of the ViewModel and survives the picker round trip.
 */
data class PreparedDataExport(
    val displayName: String,
    val tempFilePath: String,
    /**
     * What this payload actually contains.
     *
     * Carried on the prepared export rather than re-queried after the write, so
     * the confirmation the user sees describes the file that was just saved — and
     * so a failure to re-read the database can never turn a successful export into
     * an error message.
     */
    val summary: DataExportSummary,
)

data class DataExportExportedFile(
    val displayName: String,
    val uri: Uri,
)
