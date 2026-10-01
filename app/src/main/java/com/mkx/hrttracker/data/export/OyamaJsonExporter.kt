package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationKey
import com.mkx.hrttracker.model.medication.Medicine
import com.mkx.hrttracker.model.medication.MedicinePreparation
import com.mkx.hrttracker.model.medication.MedicineSelection
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.round

/**
 * Writes dose and lab history in the JSON shape Oyama's HRT Tracker reads.
 *
 * This is an **interchange** format, not a backup: everything Oyama cannot
 * express is dropped, and [plan] reports exactly how much so the UI can warn
 * before the user commits. See `docs/data-export.md` for the full mapping.
 *
 * Every representability rule here mirrors a rule in
 * `com.mkx.hrttracker.data.importer.ExternalImportParser`. The pair is held
 * together by `OyamaJsonExportRoundTripTest`, which fails if this class ever
 * emits a row the parser would reject — a silent data loss that would otherwise
 * only surface as "the other app is missing doses".
 */
object OyamaJsonExporter {
    /** Matches `meta.version` in Oyama's own payloads. */
    const val EXPORT_VERSION: Int = 2

    private const val MODE_TRANSFEM = "transfem"
    private const val MODE_TRANSMASC = "transmasc"

    /**
     * `timeH` is an absolute hour count since the epoch, not an offset from some
     * anchor: the importer reverses it with `round(timeH * 3_600_000.0)`. A double
     * carries ~0.4 µs of error at present-day magnitudes, far below the 1 ms
     * needed for an exact round trip.
     */
    private const val MILLIS_PER_HOUR = 3_600_000.0

    // Oyama's own literal vocabulary, compared with exact equality by its importer
    // (`Object.values(Route).includes(route)`), NOT normalised. Casing is load
    // bearing: an unrecognised route drops the row outright, and an unrecognised
    // ester is silently rewritten to E2. Do not lowercase these.
    private const val ROUTE_ORAL = "oral"
    private const val ROUTE_SUBLINGUAL = "sublingual"
    private const val ROUTE_INJECTION = "injection"
    private const val ROUTE_GEL = "gel"
    private const val ROUTE_PATCH_APPLY = "patchApply"
    private const val ROUTE_PATCH_REMOVE = "patchRemove"

    private const val ESTER_E2 = "E2"
    private const val ESTER_EB = "EB"
    private const val ESTER_EV = "EV"
    private const val ESTER_EC = "EC"
    private const val ESTER_EN = "EN"
    private const val ESTER_EU = "EU"
    private const val ESTER_CPA = "CPA"

    private const val UNIT_PG_ML = "pg/ml"
    private const val UNIT_NG_DL = "ng/dl"

    private const val KEY_EVENTS = "events"
    private const val KEY_LAB_RESULTS = "labResults"
    private const val KEY_RELEASE_RATE_UG_PER_DAY = "releaseRateUGPerDay"

    private val injectableEstrogens = setOf(
        MedicationKey.ESTRADIOL,
        MedicationKey.ESTRADIOL_VALERATE,
        MedicationKey.ESTRADIOL_BENZOATE,
        MedicationKey.ESTRADIOL_CYPIONATE,
        MedicationKey.ESTRADIOL_ENANTHATE,
        MedicationKey.ESTRADIOL_UNDECYLATE,
    )

    private val moshi: Moshi = Moshi.Builder().build()
    private val payloadAdapter: JsonAdapter<Any> = moshi
        .adapter(Any::class.java)
        .indent("  ")

    /** What an Oyama export of a bundle would contain, and what it would cost. */
    data class Plan(
        val doses: List<PlannedDose>,
        val transfemLabs: List<PlannedLab>,
        val transmascLabs: List<PlannedLab>,
        val weightKg: Double?,
        val totalDoseCount: Int,
        val totalLabCount: Int,
    ) {
        val omittedDoseCount: Int
            get() = totalDoseCount - doses.size

        val omittedLabCount: Int
            get() = totalLabCount - transfemLabs.size - transmascLabs.size
    }

    data class PlannedDose(
        val id: String,
        val timeH: Double,
        val route: String,
        val ester: String,
        val doseMg: Double,
        val releaseRateUgPerDay: Double?,
    )

    data class PlannedLab(
        val id: String,
        val timeH: Double,
        val concValue: Double,
        val unit: String,
    )

    /**
     * Splits a bundle into the rows Oyama can carry.
     *
     * Called both to build a file and to answer "how much would be left out?",
     * so the number shown in the confirmation dialog is by construction the same
     * one the file will honour.
     */
    fun plan(bundle: DataExportBundle): Plan {
        val doses = bundle.doseRows.mapNotNull(::planDose)

        // The importer groups lab results into panels keyed on the timestamp and
        // dedupes on (panel, analyte), so two panels sharing a timestamp and an
        // analyte would silently collapse into one on re-import. Count the
        // casualty rather than inventing a timestamp that was never recorded.
        val seenPanelAnalytes = mutableSetOf<Pair<Long, BloodAnalyteKey>>()
        val labs = bundle.labRows.mapNotNull { row ->
            val planned = planLab(row) ?: return@mapNotNull null
            if (!seenPanelAnalytes.add(planned.panelKey to planned.analyte)) {
                return@mapNotNull null
            }
            planned
        }

        return Plan(
            doses = doses,
            transfemLabs = labs.filter { it.analyte == BloodAnalyteKey.E2 }.map { it.row },
            transmascLabs = labs.filter { it.analyte == BloodAnalyteKey.T }.map { it.row },
            weightKg = bundle.weightKg?.takeIf { it > 0.0 && it.isFinite() },
            totalDoseCount = bundle.doseRows.size,
            totalLabCount = bundle.labRows.size,
        )
    }

    fun buildJson(
        bundle: DataExportBundle,
        exportedAt: Instant = Instant.now(),
    ): String = buildJson(plan(bundle), exportedAt)

    fun buildJson(
        plan: Plan,
        exportedAt: Instant = Instant.now(),
    ): String {
        val events = plan.doses.map { it.toJson() }
        val transfemLabs = plan.transfemLabs.map { it.toJson() }

        // `modes.transmasc` deliberately carries only labResults. Oyama's importer
        // *replaces* the other mode's records rather than merging, so emitting an
        // empty `transmasc.events` would wipe a transfem user's transmasc doses.
        // Nothing reads it back either: doses come from `modes.transfem.events`
        // alone, and Featherline never has transmasc doses to contribute.
        val transfemBlock = linkedMapOf<String, Any?>(
            KEY_EVENTS to events,
            KEY_LAB_RESULTS to transfemLabs,
        )
        val transmascBlock = linkedMapOf<String, Any?>(
            KEY_LAB_RESULTS to plan.transmascLabs.map { it.toJson() },
        )

        // Root events/labResults mirror the active mode, exactly as Oyama's own
        // export does. The importer ignores them whenever `modes` is present.
        val payload = linkedMapOf<String, Any?>(
            "meta" to linkedMapOf(
                "version" to EXPORT_VERSION,
                "exportedAt" to DateTimeFormatter.ISO_INSTANT.format(exportedAt),
            ),
            "mode" to MODE_TRANSFEM,
            "modes" to linkedMapOf<String, Any?>(
                MODE_TRANSFEM to transfemBlock,
                MODE_TRANSMASC to transmascBlock,
            ),
            KEY_EVENTS to events,
            KEY_LAB_RESULTS to transfemLabs,
        )
        plan.weightKg?.let { weight -> payload["weight"] = weight }
        // No `encrypted` key at all: the importer treats an absent flag as
        // plaintext and rejects anything it cannot read as "definitely not
        // encrypted", and Oyama's own plaintext export omits the key too.

        return payloadAdapter.toJson(payload)
    }

    private fun planDose(row: DoseExportRow): PlannedDose? {
        val route = oyamaRouteFor(row.applicationType) ?: return null
        val timeH = row.appliedAt.toEpochMilli() / MILLIS_PER_HOUR

        if (row.applicationType == MedicationApplicationType.PATCH_OFF) {
            // The importer returns a removal candidate before it ever looks at the
            // ester, and a removal carries no amount. E2 is emitted only so the row
            // satisfies Oyama's "ester is a known code" expectation.
            return PlannedDose(
                id = row.uuid.toString(),
                timeH = timeH,
                route = route,
                ester = ESTER_E2,
                doseMg = 0.0,
                releaseRateUgPerDay = null,
            )
        }

        // A custom medicine has no Oyama code, and `medicine` is null only for the
        // PATCH_OFF case already handled above.
        val medicine = row.medicine ?: return null
        val compound = oyamaCompoundFor(medicine) ?: return null
        if (!routeAllowsCompound(route, compound.parserKey)) {
            return null
        }

        val releaseRate = (medicine.preparation as? MedicinePreparation.Patch)
            ?.specification
            ?.let { it as? MedicinePreparation.PatchSpecification.ReleaseRateMcgPerDay }

        if (releaseRate != null) {
            // The importer takes the release-rate branch before validating doseMG,
            // and Oyama's model reads the rate rather than a total. Sending 0 keeps
            // the number well-formed for readers that still expect the field.
            return PlannedDose(
                id = row.uuid.toString(),
                timeH = timeH,
                route = route,
                ester = compound.esterCode,
                doseMg = 0.0,
                releaseRateUgPerDay = releaseRate.valueMcgPerDay,
            )
        }

        // A patch specified as a total mass reports no release rate and is carried
        // by doseMG, which the importer turns back into PatchSpecification.TotalMg.
        val doseMg = row.amountMg ?: return null
        if (!doseMg.isFinite() || doseMg <= 0.0) {
            // The importer rejects a non-positive dose as malformed; dropping it here
            // keeps it inside the omission count the user is shown.
            return null
        }
        return PlannedDose(
            id = row.uuid.toString(),
            timeH = timeH,
            route = route,
            ester = compound.esterCode,
            doseMg = doseMg,
            releaseRateUgPerDay = null,
        )
    }

    private fun planLab(row: LabExportRow): PlannedLabResult? {
        val analyte = row.analyteKey ?: return null
        val unit = when (analyte) {
            BloodAnalyteKey.E2 -> UNIT_PG_ML
            BloodAnalyteKey.T -> UNIT_NG_DL
            // PROG, PRL, FSH, LH and user-defined analytes have no Oyama
            // representation: the importer maps an analyte from its unit, and only
            // these two pairings exist.
            else -> return null
        }
        val timeH = row.collectedAt.toEpochMilli() / MILLIS_PER_HOUR
        return PlannedLabResult(
            analyte = analyte,
            panelKey = panelKeyFor(timeH),
            row = PlannedLab(
                id = row.uuid.toString(),
                timeH = timeH,
                // canonicalValue is already normalised to the unit declared above
                // (E2 in pg/mL, T in ng/dL), so a result the user entered in pmol/L
                // or ng/mL still exports correctly.
                concValue = row.canonicalValue,
                unit = unit,
            ),
        )
    }

    /** The importer's own panel grouping key, derived the same way it derives it. */
    private fun panelKeyFor(timeH: Double): Long = round(timeH * MILLIS_PER_HOUR).toLong()

    private fun oyamaRouteFor(applicationType: MedicationApplicationType): String? =
        when (applicationType) {
            MedicationApplicationType.ORAL -> ROUTE_ORAL
            MedicationApplicationType.SUBLINGUAL -> ROUTE_SUBLINGUAL
            MedicationApplicationType.INJECTION -> ROUTE_INJECTION
            MedicationApplicationType.GEL -> ROUTE_GEL
            MedicationApplicationType.PATCH_ON -> ROUTE_PATCH_APPLY
            MedicationApplicationType.PATCH_OFF -> ROUTE_PATCH_REMOVE
        }

    /**
     * The medicine's Oyama code, paired with the key the importer will derive from
     * that code.
     *
     * The pairing matters: compatibility is checked against what the importer
     * resolves, not against the medicine's own key. A catalog estradiol patch is
     * `ESTRADIOL_PATCH` locally but its code is `E2`, which the importer reads back
     * as plain `ESTRADIOL` — and the gel/patch compatibility rule only accepts
     * `ESTRADIOL`.
     */
    private fun oyamaCompoundFor(medicine: Medicine): OyamaCompound? {
        val key = when (val preparation = medicine.preparation) {
            is MedicinePreparation.ImportedInjection -> preparation.ester
            is MedicinePreparation.ImportedGel -> MedicationKey.ESTRADIOL
            // A custom medicine has no Oyama code at all.
            else -> (medicine.selection as? MedicineSelection.Catalog)?.medicationKey ?: return null
        }
        return when (key) {
            MedicationKey.ESTRADIOL,
            MedicationKey.ESTRADIOL_GEL,
            MedicationKey.ESTRADIOL_PATCH,
            -> OyamaCompound(ESTER_E2, MedicationKey.ESTRADIOL)

            MedicationKey.ESTRADIOL_BENZOATE -> OyamaCompound(ESTER_EB, MedicationKey.ESTRADIOL_BENZOATE)
            MedicationKey.ESTRADIOL_VALERATE -> OyamaCompound(ESTER_EV, MedicationKey.ESTRADIOL_VALERATE)
            MedicationKey.ESTRADIOL_CYPIONATE -> OyamaCompound(ESTER_EC, MedicationKey.ESTRADIOL_CYPIONATE)
            MedicationKey.ESTRADIOL_ENANTHATE -> OyamaCompound(ESTER_EN, MedicationKey.ESTRADIOL_ENANTHATE)
            MedicationKey.ESTRADIOL_UNDECYLATE -> OyamaCompound(ESTER_EU, MedicationKey.ESTRADIOL_UNDECYLATE)
            MedicationKey.CYPROTERONE_ACETATE -> OyamaCompound(ESTER_CPA, MedicationKey.CYPROTERONE_ACETATE)

            // Testosterone, spironolactone, bicalutamide, finasteride, dutasteride,
            // SERMs and GnRH agonists: Oyama's own enum names some of these, but
            // Featherline's importer does not accept them from an Oyama source, so
            // emitting one would lose it on the way back in.
            else -> null
        }
    }

    /** Mirrors the importer's `isEstrogenCompatibleWithRoute` and antiandrogen route rule. */
    private fun routeAllowsCompound(route: String, parserKey: MedicationKey): Boolean {
        if (parserKey == MedicationKey.CYPROTERONE_ACETATE) {
            return route == ROUTE_ORAL || route == ROUTE_SUBLINGUAL
        }
        return when (route) {
            ROUTE_ORAL,
            ROUTE_SUBLINGUAL,
            -> parserKey == MedicationKey.ESTRADIOL || parserKey == MedicationKey.ESTRADIOL_VALERATE

            ROUTE_INJECTION -> parserKey in injectableEstrogens
            ROUTE_GEL,
            ROUTE_PATCH_APPLY,
            -> parserKey == MedicationKey.ESTRADIOL

            ROUTE_PATCH_REMOVE -> true
            else -> false
        }
    }

    private fun PlannedDose.toJson(): Map<String, Any?> {
        val json = linkedMapOf<String, Any?>(
            "id" to id,
            "timeH" to timeH,
            "route" to route,
            "ester" to ester,
            "doseMG" to doseMg,
        )
        releaseRateUgPerDay?.let { rate ->
            json["extras"] = linkedMapOf<String, Any?>(KEY_RELEASE_RATE_UG_PER_DAY to rate)
        }
        return json
    }

    private fun PlannedLab.toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "timeH" to timeH,
        "concValue" to concValue,
        "unit" to unit,
    )

    private data class OyamaCompound(val esterCode: String, val parserKey: MedicationKey)

    /** A planned lab plus what decides its mode block and its panel identity. */
    private data class PlannedLabResult(
        val analyte: BloodAnalyteKey,
        val panelKey: Long,
        val row: PlannedLab,
    )
}
