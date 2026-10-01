package com.mkx.hrttracker.data.export

import android.content.Context
import android.net.Uri
import com.mkx.hrttracker.data.backup.BackupExportService
import com.mkx.hrttracker.data.repository.BloodTestRepository
import com.mkx.hrttracker.data.repository.MedicationLogRepository
import com.mkx.hrttracker.data.repository.UserProfileRepository
import com.mkx.hrttracker.model.bloodtest.BloodTestPanel
import com.mkx.hrttracker.model.bloodtest.BloodTestResult
import com.mkx.hrttracker.model.bloodtest.BloodTestResultAnalyte
import com.mkx.hrttracker.model.medication.DoseInstructionCalculator
import com.mkx.hrttracker.model.medication.MedicationLogEntry
import com.mkx.hrttracker.util.backupFileNameTimestampFormatter
import com.mkx.hrttracker.util.calibrationAnalyteLabel
import com.mkx.hrttracker.util.doseInstructionText
import com.mkx.hrttracker.util.formatCalibrationUnitLabel
import com.mkx.hrttracker.util.labelRes
import com.mkx.hrttracker.util.medicationEntryTitle
import com.mkx.hrttracker.util.medicationRouteLabel
import com.mkx.hrttracker.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the user-facing exports: CSV, PDF, and two shapes of JSON.
 *
 * Distinct from `BackupExportService`, which produces the *encrypted* container
 * used for restore. These files are plaintext and, for CSV/PDF, deliberately
 * partial — they exist to be read by a person or another HRT app, not to restore
 * this one. The native JSON shape is the one exception: it is the same snapshot
 * schema the backup writes, minus the envelope.
 */
@Singleton
class DataExportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val medicationLogRepository: MedicationLogRepository,
    private val bloodTestRepository: BloodTestRepository,
    private val userProfileRepository: UserProfileRepository,
    private val backupExportService: BackupExportService,
    private val pdfRenderer: DataExportPdfRenderer,
) {

    /**
     * Counts for the confirmation dialog, computed through the same
     * [OyamaJsonExporter.plan] the file itself uses — so the number the user is
     * shown before committing is by construction the number the export honours.
     */
    suspend fun buildSummary(): DataExportSummary = withContext(Dispatchers.IO) {
        val plan = OyamaJsonExporter.plan(buildBundle())
        DataExportSummary(
            doseCount = plan.totalDoseCount,
            labCount = plan.totalLabCount,
            representableDoseCount = plan.doses.size,
            representableLabCount = plan.transfemLabs.size + plan.transmascLabs.size,
        )
    }

    /**
     * Renders an export into a cache temp file and returns it with the filename to
     * suggest. Holding the bytes on disk rather than in memory keeps a large
     * export out of the ViewModel and survives the picker round trip.
     */
    suspend fun prepareExport(
        format: DataExportFormat,
        jsonFormat: JsonExportFormat? = null,
    ): PreparedDataExport = withContext(Dispatchers.IO) {
        require(format != DataExportFormat.JSON || jsonFormat != null) {
            "A JSON export needs a JSON format."
        }
        sweepStalePreparedExports()

        val displayName = buildExportFileName(format, jsonFormat, Instant.now())
        val tempFile = File.createTempFile(
            PREPARED_EXPORT_PREFIX,
            PREPARED_EXPORT_SUFFIX,
            context.cacheDir,
        )
        try {
            val bundle = buildBundle()
            if (bundle.isEmpty) {
                // Surfaced as a distinct type so the caller can say "there is nothing
                // to export" rather than the generic failure message.
                throw EmptyDataExportException()
            }
            when (format) {
                DataExportFormat.CSV -> writeCsv(bundle, tempFile)

                DataExportFormat.PDF -> writePdf(bundle, tempFile)

                DataExportFormat.JSON -> writeJson(
                    json = buildJsonTextInternal(bundle, checkNotNull(jsonFormat)),
                    destination = tempFile,
                )
            }
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }

        PreparedDataExport(
            displayName = displayName,
            tempFilePath = tempFile.absolutePath,
        )
    }

    /**
     * Copies a prepared export to the document the user picked.
     *
     * Unlike the backup path this does **not** delete the destination on failure:
     * `ActivityResultContracts.CreateDocument` has already created (or replaced)
     * that document by the time we are called, so removing it would destroy a file
     * the user named — whereas a partially written one is at least inspectable.
     */
    suspend fun exportPreparedExport(
        destinationUri: Uri,
        prepared: PreparedDataExport,
    ): DataExportExportedFile = withContext(Dispatchers.IO) {
        val tempFile = File(prepared.tempFilePath)
        if (!tempFile.exists()) {
            throw IOException("Prepared export payload is no longer available.")
        }
        try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Unable to open an output stream for data export.")

            DataExportExportedFile(
                displayName = prepared.displayName,
                uri = destinationUri,
            )
        } finally {
            discardPreparedExport(prepared)
        }
    }

    suspend fun discardPreparedExport(prepared: PreparedDataExport) = withContext(Dispatchers.IO) {
        File(prepared.tempFilePath).delete()
        Unit
    }

    /** JSON text for the clipboard, which bypasses the file picker entirely. */
    suspend fun buildJsonText(jsonFormat: JsonExportFormat): String = withContext(Dispatchers.IO) {
        buildJsonTextInternal(buildBundle(), jsonFormat)
    }

    internal suspend fun buildBundle(): DataExportBundle = withContext(Dispatchers.IO) {
        val localized = context.withAppLanguage()
        val dates = buildDataExportDateFormatters(context)
        val entries = medicationLogRepository.getEntries()
        val panels = bloodTestRepository.getPanels()
        val profile = userProfileRepository.getCurrentProfile()

        DataExportBundle(
            generatedAt = Instant.now(),
            doseRows = entries.map { entry -> entry.toExportRow(localized, dates) },
            labRows = panels.flatMap { panel -> panel.toExportRows(dates) },
            weightKg = profile.weightKg,
        )
    }

    private suspend fun buildJsonTextInternal(
        bundle: DataExportBundle,
        jsonFormat: JsonExportFormat,
    ): String = when (jsonFormat) {
        // Reuses the backup serializer rather than a second copy of the snapshot
        // schema: this is the exact object the restore path consumes, so any future
        // change to BackupSnapshot flows through here automatically.
        JsonExportFormat.NATIVE -> backupExportService.buildBackupSnapshotJson(bundle.generatedAt)

        JsonExportFormat.OYAMA -> OyamaJsonExporter.buildJson(bundle, bundle.generatedAt)
    }

    private fun writeCsv(bundle: DataExportBundle, destination: File) {
        val labels = buildDataExportLabels(context)
        destination.bufferedWriter(Charsets.UTF_8).use { writer ->
            DataExportCsvEncoder.write(bundle, labels, writer)
        }
    }

    private fun writeJson(json: String, destination: File) {
        destination.writeText(json, Charsets.UTF_8)
    }

    private fun writePdf(bundle: DataExportBundle, destination: File) {
        val labels = buildDataExportLabels(context)
        val dates = buildDataExportDateFormatters(context)
        val model = buildPdfDocumentModel(bundle, labels, dates)
        destination.outputStream().use { outputStream ->
            pdfRenderer.render(
                document = model,
                pageLabel = labels::pdfPageLabel,
                out = outputStream,
            )
        }
    }

    private fun buildPdfDocumentModel(
        bundle: DataExportBundle,
        labels: DataExportLabels,
        dates: DataExportDateFormatters,
    ): PdfDocumentModel {
        val zoneId = ZoneId.systemDefault()
        val exportedAt = "${dates.date(bundle.generatedAt, zoneId)} ${dates.time(bundle.generatedAt, zoneId)}"

        val doseRows = bundle.doseRows.map { row ->
            listOf(
                row.dateText,
                row.timeText,
                row.medicineName,
                row.routeLabel,
                row.doseText,
                // Derived, so it is rounded for the report: the raw double would
                // print seventeen significant digits next to a hand-typed dose.
                exportReportNumber(row.equivalentE2Mg),
            )
        }
        val labRows = bundle.labRows.map { row ->
            listOf(
                row.dateText,
                row.timeText,
                row.analyteLabel,
                exportNumber(row.value),
                formatCalibrationUnitLabel(row.unitSnapshot),
                row.panelNotes.orEmpty(),
            )
        }

        return PdfDocumentModel(
            title = labels.pdfTitle,
            // A translation missing or reordering the placeholder must not fail the
            // export, so fall back to the bare timestamp.
            subtitle = runCatching { String.format(Locale.ROOT, labels.pdfExportedAt, exportedAt) }
                .getOrDefault(exportedAt),
            emptyNotice = labels.pdfEmpty,
            sections = listOf(
                PdfSection(
                    title = labels.pdfDosesSection,
                    headers = labels.pdfDoseHeaders,
                    columnFractions = DataExportPdfLayout.DOSE_COLUMN_FRACTIONS,
                    rows = doseRows,
                ),
                PdfSection(
                    title = labels.pdfLabsSection,
                    headers = labels.pdfLabHeaders,
                    columnFractions = DataExportPdfLayout.LAB_COLUMN_FRACTIONS,
                    rows = labRows,
                ),
            ),
        )
    }

    private fun MedicationLogEntry.toExportRow(
        localized: Context,
        dates: DataExportDateFormatters,
    ): DoseExportRow {
        val zoneId = exportZoneId(appliedAtTimeZoneId)
        return DoseExportRow(
            uuid = uuid,
            appliedAt = appliedAt,
            appliedAtTimeZoneId = appliedAtTimeZoneId,
            category = category,
            applicationType = applicationType,
            medicine = medicine,
            doseInstruction = doseInstruction,
            count = count,
            doseAmountDelta = doseAmountDelta,
            equivalentE2Mg = equivalentE2Mg,
            // Mirrors what the app shows for this entry: the administered amount,
            // already adjusted by any "took more/less" delta. Note this is the mass
            // of the compound given, not its estradiol equivalent — the two differ
            // (valerate is ~1.5x estradiol by mass) and the export must not conflate
            // them.
            amountMg = medicine?.let {
                DoseInstructionCalculator.totalAmountMg(
                    perUnitAmountMg = DoseInstructionCalculator.perUnitAmountMg(
                        medicine = it,
                        doseInstruction = doseInstruction,
                        doseAmountDelta = doseAmountDelta,
                    ),
                    count = count,
                )
            },
            categoryLabel = localized.getString(category.labelRes),
            medicineName = medicationEntryTitle(medicine, applicationType, localized),
            routeLabel = medicationRouteLabel(medicine, applicationType, localized),
            doseText = doseInstructionText(
                context = localized,
                medicine = medicine,
                doseInstruction = doseInstruction,
                count = count,
                doseAmountDelta = doseAmountDelta,
            ).orEmpty(),
            dateText = dates.date(appliedAt, zoneId),
            timeText = dates.time(appliedAt, zoneId),
        )
    }

    private fun BloodTestPanel.toExportRows(dates: DataExportDateFormatters): List<LabExportRow> {
        val zoneId = exportZoneId(collectedAtTimeZoneId)
        return results.map { result ->
            LabExportRow(
                uuid = result.uuid,
                panelUuid = uuid,
                collectedAt = collectedAt,
                collectedAtTimeZoneId = collectedAtTimeZoneId,
                panelNotes = notes,
                analyteKey = (result.analyte as? BloodTestResultAnalyte.Builtin)?.key,
                canonicalValue = result.canonicalValue,
                value = result.value,
                unitSnapshot = result.unitSnapshot,
                analyteLabel = result.exportAnalyteLabel(),
                dateText = dates.date(collectedAt, zoneId),
                timeText = dates.time(collectedAt, zoneId),
            )
        }
    }

    private fun BloodTestResult.exportAnalyteLabel(): String {
        return when (val current = analyte) {
            is BloodTestResultAnalyte.Builtin -> calibrationAnalyteLabel(current.key)
            is BloodTestResultAnalyte.Custom -> current.abbreviation
        }
    }

    /**
     * A record's stored zone id is free-form data that has survived migrations and
     * imports, so an unparseable value must not sink the export.
     */
    private fun exportZoneId(zoneId: String): ZoneId =
        runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())

    internal fun sweepStalePreparedExports() {
        val cutoff = System.currentTimeMillis() - STALE_PREPARED_EXPORT_MILLIS
        context.cacheDir
            .listFiles { file ->
                file.name.startsWith(PREPARED_EXPORT_PREFIX) && file.lastModified() < cutoff
            }
            ?.forEach { stale -> stale.delete() }
    }

    companion object {
        const val CSV_MIME_TYPE = "text/csv"
        const val PDF_MIME_TYPE = "application/pdf"
        const val JSON_MIME_TYPE = "application/json"

        /**
         * Android's clipboard is Binder-backed and most OEMs reject payloads around
         * a megabyte. Checked before copying so an oversized export surfaces a
         * pointer to the file export instead of an unexplained failure.
         */
        const val MAX_CLIPBOARD_JSON_CHARS = 512_000

        internal fun mimeTypeFor(format: DataExportFormat): String = when (format) {
            DataExportFormat.CSV -> CSV_MIME_TYPE
            DataExportFormat.PDF -> PDF_MIME_TYPE
            DataExportFormat.JSON -> JSON_MIME_TYPE
        }

        internal fun buildExportFileName(
            format: DataExportFormat,
            jsonFormat: JsonExportFormat?,
            exportedAt: Instant,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): String {
            val timestamp = backupFileNameTimestampFormatter().format(exportedAt.atZone(zoneId))
            // The two JSON shapes get different stems: only one of them can be read
            // back, and a user who later finds two identically named files has no way
            // to tell which is which.
            return when (format) {
                DataExportFormat.CSV -> "featherline-data-$timestamp.csv"
                DataExportFormat.PDF -> "featherline-data-$timestamp.pdf"
                DataExportFormat.JSON -> when (jsonFormat) {
                    JsonExportFormat.OYAMA -> "featherline-oyama-$timestamp.json"
                    else -> "featherline-backup-plaintext-$timestamp.json"
                }
            }
        }

        private const val PREPARED_EXPORT_PREFIX = "prepared-export-"
        private const val PREPARED_EXPORT_SUFFIX = ".tmp"
        private const val STALE_PREPARED_EXPORT_MILLIS = 6L * 60L * 60L * 1000L
    }
}

/** Raised when there is nothing to write, so the UI can say so specifically. */
class EmptyDataExportException : IllegalStateException("There is no data to export.")
