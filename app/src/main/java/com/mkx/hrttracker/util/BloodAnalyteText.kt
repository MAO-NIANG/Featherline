package com.mkx.hrttracker.util

import androidx.annotation.StringRes
import com.mkx.hrttracker.R
import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import java.util.Locale

/**
 * Analyte label mapping shared by the calibration UI and the data-export layer.
 *
 * This lives in `util` rather than in `ui.calibration` so that `data.export` can
 * name an analyte without depending on a Compose screen. `util` is the
 * established home for this kind of label mapping — see `MedicationCatalogText.kt`
 * and `MedicationDisplayText.kt`, both of which already hold `@StringRes` lookups
 * consumed by `ui`.
 */

/**
 * The short code shown for a built-in analyte ("E2", "T", "PROG", ...).
 *
 * Deliberately `Locale.ROOT`: the code is an identifier, and a locale-sensitive
 * `uppercase()` would produce "İ" under a Turkish locale, changing the value
 * that reaches an export.
 */
fun calibrationAnalyteLabel(analyteKey: BloodAnalyteKey): String {
    return analyteKey.storageValue.uppercase(Locale.ROOT)
}

@StringRes
fun calibrationAnalyteFullNameRes(analyteKey: BloodAnalyteKey): Int {
    return when (analyteKey) {
        BloodAnalyteKey.E2 -> R.string.medication_category_estradiol
        BloodAnalyteKey.T -> R.string.medication_category_testosterone
        BloodAnalyteKey.PROG -> R.string.settings_calibration_analyte_prog
        BloodAnalyteKey.PRL -> R.string.settings_calibration_analyte_prl
        BloodAnalyteKey.FSH -> R.string.settings_calibration_analyte_fsh
        BloodAnalyteKey.LH -> R.string.settings_calibration_analyte_lh
    }
}
