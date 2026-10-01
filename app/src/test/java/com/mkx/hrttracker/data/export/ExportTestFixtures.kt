package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.medication.DoseInstruction
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationCategory
import com.mkx.hrttracker.model.medication.MedicationKey
import com.mkx.hrttracker.model.medication.Medicine
import com.mkx.hrttracker.model.medication.MedicinePreparation
import com.mkx.hrttracker.model.medication.MedicineSelection
import com.mkx.hrttracker.model.medication.MedicineStock
import java.time.Instant
import java.util.UUID

/**
 * Builders for the export tests.
 *
 * These construct the *row* types directly rather than running the real
 * repositories and display formatters: `Medicine` validates its own invariants,
 * but the row types carry pre-formatted strings, so a fixture can pin the
 * encoder's behaviour without dragging in `Context`, string resources, or the
 * app language override.
 */

internal val TEST_INSTANT: Instant = Instant.parse("2026-10-02T08:30:00Z")

/** Fixed ids so a failure names a row rather than a random UUID. */
internal fun testUuid(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

internal fun catalogMedicine(
    key: MedicationKey,
    preparation: MedicinePreparation,
    displayName: String? = null,
): Medicine = Medicine(
    uuid = testUuid("medicine-${key.name}"),
    selection = MedicineSelection.Catalog(key),
    category = key.category,
    preparation = preparation,
    displayName = displayName,
    identityKey = "test-catalog-${key.name}",
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
    archivedAt = null,
    stock = MedicineStock(),
)

/** A medicine created by the external importer; the only shape that may carry an imported preparation. */
internal fun importedMedicine(preparation: MedicinePreparation): Medicine = Medicine(
    uuid = testUuid("medicine-imported"),
    selection = MedicineSelection.Custom("External tracker"),
    category = MedicationCategory.ESTRADIOL,
    preparation = preparation,
    displayName = null,
    identityKey = "test-imported",
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
    archivedAt = null,
    stock = MedicineStock(),
    importedFromExternalTracker = true,
)

internal fun customMedicine(name: String): Medicine = Medicine(
    uuid = testUuid("medicine-custom"),
    selection = MedicineSelection.Custom(name),
    category = MedicationCategory.CUSTOM,
    preparation = MedicinePreparation.Pill(strengthMgPerTablet = 2.0),
    displayName = null,
    identityKey = "test-custom",
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
    archivedAt = null,
    stock = MedicineStock(),
)

internal fun doseRow(
    medicine: Medicine?,
    applicationType: MedicationApplicationType,
    category: MedicationCategory = medicine?.category ?: MedicationCategory.ESTRADIOL,
    doseInstruction: DoseInstruction = DoseInstruction.WholeUnit,
    amountMg: Double? = 2.0,
    appliedAt: Instant = TEST_INSTANT,
    count: Int = 1,
    equivalentE2Mg: Double? = null,
    id: String = "dose-${applicationType.name}-${amountMg}",
): DoseExportRow = DoseExportRow(
    uuid = testUuid(id),
    appliedAt = appliedAt,
    appliedAtTimeZoneId = "Asia/Shanghai",
    category = category,
    applicationType = applicationType,
    medicine = medicine,
    doseInstruction = doseInstruction,
    count = count,
    doseAmountDelta = null,
    equivalentE2Mg = equivalentE2Mg,
    amountMg = amountMg,
    categoryLabel = "Estradiol",
    medicineName = medicine?.identityKey ?: "Patch removal",
    routeLabel = applicationType.name,
    doseText = amountMg?.let { "$it mg" }.orEmpty(),
    dateText = "2026-10-02",
    timeText = "16:30",
)

internal fun labRow(
    analyteKey: BloodAnalyteKey?,
    canonicalValue: Double,
    collectedAt: Instant = TEST_INSTANT,
    unitSnapshot: String = "pg_ml",
    value: Double = canonicalValue,
    analyteLabel: String = analyteKey?.storageValue?.uppercase().orEmpty(),
    notes: String? = null,
): LabExportRow = LabExportRow(
    uuid = testUuid("lab-${analyteKey?.name}-$collectedAt"),
    panelUuid = testUuid("panel-$collectedAt"),
    collectedAt = collectedAt,
    collectedAtTimeZoneId = "Asia/Shanghai",
    panelNotes = notes,
    analyteKey = analyteKey,
    canonicalValue = canonicalValue,
    value = value,
    unitSnapshot = unitSnapshot,
    analyteLabel = analyteLabel,
    dateText = "2026-10-02",
    timeText = "16:30",
)

internal fun bundleOf(
    doses: List<DoseExportRow> = emptyList(),
    labs: List<LabExportRow> = emptyList(),
    weightKg: Double? = null,
    generatedAt: Instant = TEST_INSTANT,
): DataExportBundle = DataExportBundle(
    generatedAt = generatedAt,
    doseRows = doses,
    labRows = labs,
    weightKg = weightKg,
)

/** English labels, so the CSV and PDF assertions read as literals. */
internal fun testLabels(): DataExportLabels = DataExportLabels(
    csvHeaders = listOf(
        "Record type",
        "Date and time",
        "Time zone",
        "Category",
        "Name",
        "Route",
        "Dose",
        "Amount (mg)",
        "Value",
        "Unit",
        "Note",
    ),
    pdfTitle = "Featherline data export",
    pdfExportedAt = "Exported at %1\$s",
    pdfDosesSection = "Medication doses",
    pdfLabsSection = "Blood test results",
    pdfEmpty = "No data to display.",
    pdfDoseHeaders = listOf("Date", "Time", "Medicine", "Route", "Dose", "E2 equivalent (mg)"),
    pdfLabHeaders = listOf("Date", "Time", "Analyte", "Value", "Unit", "Note"),
    valueDose = "Dose",
    valueLab = "Blood test",
    pdfPagePattern = "Page %1\$d / %2\$d",
)
