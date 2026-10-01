package com.mkx.hrttracker.data.export

import android.content.Context
import com.mkx.hrttracker.R
import com.mkx.hrttracker.util.currentAppLocale
import com.mkx.hrttracker.util.isChineseLanguage
import com.mkx.hrttracker.util.uses24HourTimeFormat
import com.mkx.hrttracker.util.withAppLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every localized string an export file needs, resolved once.
 *
 * Encoders take this rather than a `Context` so that CSV and PDF layout logic
 * stays free of Android resource lookups and can be unit-tested on the JVM.
 * Both are user-facing documents, so their headers are localized; the
 * machine-readable structure lives in the JSON formats instead.
 */
data class DataExportLabels(
    val csvHeaders: List<String>,
    val pdfTitle: String,
    val pdfExportedAt: String,
    val pdfDosesSection: String,
    val pdfLabsSection: String,
    val pdfEmpty: String,
    val pdfDoseHeaders: List<String>,
    val pdfLabHeaders: List<String>,
    /** Value written in the CSV record-type column for a dose row. */
    val valueDose: String,
    /** Value written in the CSV record-type column for a lab row. */
    val valueLab: String,
    /** Positional pattern for "Page N / M", carrying the locale's own ordering. */
    val pdfPagePattern: String,
) {
    fun pdfPageLabel(page: Int, totalPages: Int): String =
        // Locale.ROOT keeps page digits ASCII, matching the rest of the app's
        // numeric rendering and sidestepping the device locale's digit shapes.
        String.format(Locale.ROOT, pdfPagePattern, page, totalPages)
}

/** Localized date and time formatting for the human-facing PDF report. */
data class DataExportDateFormatters(
    private val dateFormatter: DateTimeFormatter,
    private val timeFormatter: DateTimeFormatter,
) {
    fun date(instant: Instant, zoneId: ZoneId): String =
        instant.atZone(zoneId).format(dateFormatter)

    fun time(instant: Instant, zoneId: ZoneId): String =
        instant.atZone(zoneId).format(timeFormatter)
}

/**
 * Resolves every export string against the app language.
 *
 * Uses [withAppLanguage] so a PDF renders in the language chosen inside the app
 * rather than the device language — the two differ whenever the per-app locale
 * override is in play.
 */
fun buildDataExportLabels(context: Context): DataExportLabels {
    val localized = context.withAppLanguage()
    return DataExportLabels(
        csvHeaders = listOf(
            localized.getString(R.string.export_header_record_type),
            localized.getString(R.string.export_header_datetime),
            localized.getString(R.string.export_header_timezone),
            localized.getString(R.string.export_header_category),
            localized.getString(R.string.export_header_name),
            localized.getString(R.string.export_header_route),
            localized.getString(R.string.export_header_dose),
            localized.getString(R.string.export_header_amount_mg),
            localized.getString(R.string.export_header_value),
            localized.getString(R.string.export_header_unit),
            localized.getString(R.string.export_header_note),
        ),
        pdfTitle = localized.getString(R.string.export_pdf_title),
        pdfExportedAt = localized.getString(R.string.export_pdf_exported_at),
        pdfDosesSection = localized.getString(R.string.export_pdf_doses_section),
        pdfLabsSection = localized.getString(R.string.export_pdf_labs_section),
        pdfEmpty = localized.getString(R.string.export_pdf_empty),
        pdfDoseHeaders = listOf(
            localized.getString(R.string.export_header_date),
            localized.getString(R.string.export_header_time),
            localized.getString(R.string.export_header_medicine),
            localized.getString(R.string.export_header_route),
            localized.getString(R.string.export_header_dose),
            localized.getString(R.string.export_header_e2_equivalent),
        ),
        pdfLabHeaders = listOf(
            localized.getString(R.string.export_header_date),
            localized.getString(R.string.export_header_time),
            localized.getString(R.string.export_header_analyte),
            localized.getString(R.string.export_header_value),
            localized.getString(R.string.export_header_unit),
            localized.getString(R.string.export_header_note),
        ),
        valueDose = localized.getString(R.string.export_value_dose),
        valueLab = localized.getString(R.string.export_value_lab),
        pdfPagePattern = localized.getString(R.string.export_pdf_page),
    )
}

fun buildDataExportDateFormatters(context: Context): DataExportDateFormatters {
    return buildDataExportDateFormatters(
        // Read the locale off the localized context: below API 33 the per-app
        // language override never reaches the application context, so reading it
        // from `context` directly would format dates in the system language while
        // the surrounding strings are in the app language.
        locale = context.withAppLanguage().currentAppLocale(),
        uses24HourFormat = context.uses24HourTimeFormat(),
    )
}

internal fun buildDataExportDateFormatters(
    locale: Locale,
    uses24HourFormat: Boolean,
): DataExportDateFormatters {
    // An export is an archival document that routinely spans years, so the date
    // always carries the year — unlike the in-app labels, which elide it for dates
    // inside the current year.
    val datePattern = if (locale.isChineseLanguage()) "yyyy年M月d日" else "yyyy-MM-dd"
    val timePattern = when {
        uses24HourFormat -> "HH:mm"
        locale.isChineseLanguage() -> "ah:mm"
        else -> "h:mm a"
    }
    return DataExportDateFormatters(
        dateFormatter = DateTimeFormatter.ofPattern(datePattern, locale),
        timeFormatter = DateTimeFormatter.ofPattern(timePattern, locale),
    )
}
