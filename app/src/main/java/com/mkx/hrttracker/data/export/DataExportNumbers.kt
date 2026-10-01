package com.mkx.hrttracker.data.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Renders a number for an exported file.
 *
 * Deliberately locale-independent, unlike the app's on-screen dose formatting:
 * a `de`-locale device would otherwise write `1,5` into a CSV column, where the
 * comma is the field separator. `toPlainString()` keeps large or small magnitudes
 * in positional notation instead of the `1E+2` that `stripTrailingZeros()` alone
 * would hand back.
 */
internal fun exportNumber(value: Double): String {
    if (!value.isFinite()) {
        return ""
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

internal fun exportNumber(value: Double?): String {
    return value?.let(::exportNumber).orEmpty()
}

/**
 * Renders a *derived* number for the printed report.
 *
 * Distinct from [exportNumber] on purpose. A value the user typed is exported
 * exactly as recorded, but a computed one carries full double precision —
 * the estradiol equivalent of a 2 mg dose prints as `0.7640953716690041`, which
 * is noise on a page someone reads or hands to a clinician. Two decimal places
 * is the useful resolution for a milligram figure.
 */
internal fun exportReportNumber(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return ""
    }
    return BigDecimal.valueOf(value)
        .setScale(REPORT_DECIMAL_PLACES, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

private const val REPORT_DECIMAL_PLACES = 2
