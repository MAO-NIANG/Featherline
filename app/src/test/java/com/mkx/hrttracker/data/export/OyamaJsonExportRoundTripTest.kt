package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.data.importer.ExternalImportParser
import com.mkx.hrttracker.data.importer.ExternalImportWarningReason
import com.mkx.hrttracker.data.importer.ExternalTrackerSourceApp
import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.bloodtest.BloodUnitKey
import com.mkx.hrttracker.model.medication.DoseInstruction
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationCategory
import com.mkx.hrttracker.model.medication.MedicationKey
import com.mkx.hrttracker.model.medication.Medicine
import com.mkx.hrttracker.model.medication.MedicinePreparation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds [OyamaJsonExporter]'s own output into the real [ExternalImportParser] and
 * asserts the result matches the source.
 *
 * This is the guard that keeps the two halves in lockstep. A row the exporter
 * emits but the parser rejects is silent data loss that would otherwise only
 * surface as "the other app is missing doses", so the final assertion here is
 * that no row was skipped at all.
 */
class OyamaJsonExportRoundTripTest {
    private val parser = ExternalImportParser()

    private val pill = MedicinePreparation.Pill(strengthMgPerTablet = 2.0)
    private val singleUseVial = MedicinePreparation.InjectionSingleUseVial(strengthMgPerVial = 10.0)
    private val multiUseVial = MedicinePreparation.InjectionMultiUseVial(
        concentrationMgPerMl = 40.0,
        vialVolumeMl = 1.0,
    )

    /** Source rows that Oyama's vocabulary can express, each at a distinct minute. */
    private fun representableDoses(): List<Pair<DoseExportRow, MedicationApplicationType>> {
        val estradiolPill = catalogMedicine(MedicationKey.ESTRADIOL, pill)
        val valerateVial = catalogMedicine(MedicationKey.ESTRADIOL_VALERATE, singleUseVial)
        val enanthateVial = catalogMedicine(MedicationKey.ESTRADIOL_ENANTHATE, multiUseVial)
        val gel = catalogMedicine(
            MedicationKey.ESTRADIOL_GEL,
            MedicinePreparation.GelSachet(concentrationPercent = 0.06, sachetWeightGrams = 2.5),
        )
        val patchReleaseRate = catalogMedicine(
            MedicationKey.ESTRADIOL_PATCH,
            MedicinePreparation.Patch(
                MedicinePreparation.PatchSpecification.ReleaseRateMcgPerDay(valueMcgPerDay = 100.0)
            ),
        )
        val patchTotalMg = catalogMedicine(
            MedicationKey.ESTRADIOL_PATCH,
            MedicinePreparation.Patch(
                MedicinePreparation.PatchSpecification.TotalMg(valueMg = 4.0)
            ),
        )
        val cyproterone = catalogMedicine(
            MedicationKey.CYPROTERONE_ACETATE,
            MedicinePreparation.Pill(strengthMgPerTablet = 50.0),
        )

        return listOf(
            doseRow(
                medicine = estradiolPill,
                applicationType = MedicationApplicationType.ORAL,
                doseInstruction = DoseInstruction.TabletFraction(numerator = 1, denominator = 1),
                amountMg = 2.0,
                appliedAt = TEST_INSTANT.plusSeconds(60),
                id = "oral-e2",
            ),
            doseRow(
                medicine = estradiolPill,
                applicationType = MedicationApplicationType.SUBLINGUAL,
                doseInstruction = DoseInstruction.TabletFraction(numerator = 1, denominator = 2),
                amountMg = 1.0,
                appliedAt = TEST_INSTANT.plusSeconds(120),
                id = "sublingual-e2",
            ),
            doseRow(
                medicine = valerateVial,
                applicationType = MedicationApplicationType.INJECTION,
                amountMg = 10.0,
                appliedAt = TEST_INSTANT.plusSeconds(180),
                id = "injection-ev",
            ),
            doseRow(
                medicine = enanthateVial,
                applicationType = MedicationApplicationType.INJECTION,
                doseInstruction = DoseInstruction.VolumeMl(valueMl = 0.5),
                amountMg = 20.0,
                appliedAt = TEST_INSTANT.plusSeconds(240),
                id = "injection-en",
            ),
            doseRow(
                medicine = importedMedicine(
                    MedicinePreparation.ImportedInjection(
                        administeredMg = 5.0,
                        ester = MedicationKey.ESTRADIOL_VALERATE,
                    )
                ),
                applicationType = MedicationApplicationType.INJECTION,
                amountMg = 5.0,
                appliedAt = TEST_INSTANT.plusSeconds(300),
                id = "imported-injection",
            ),
            doseRow(
                medicine = gel,
                applicationType = MedicationApplicationType.GEL,
                amountMg = 1.5,
                appliedAt = TEST_INSTANT.plusSeconds(360),
                id = "gel-catalog",
            ),
            doseRow(
                medicine = importedMedicine(
                    MedicinePreparation.ImportedGel(appliedEstradiolMg = 0.75)
                ),
                applicationType = MedicationApplicationType.GEL,
                amountMg = 0.75,
                appliedAt = TEST_INSTANT.plusSeconds(420),
                id = "gel-imported",
            ),
            doseRow(
                medicine = patchReleaseRate,
                applicationType = MedicationApplicationType.PATCH_ON,
                // A release-rate patch reports no per-unit mass; the rate travels in extras.
                amountMg = null,
                appliedAt = TEST_INSTANT.plusSeconds(480),
                id = "patch-release-rate",
            ),
            doseRow(
                medicine = patchTotalMg,
                applicationType = MedicationApplicationType.PATCH_ON,
                amountMg = 4.0,
                appliedAt = TEST_INSTANT.plusSeconds(540),
                id = "patch-total-mg",
            ),
            doseRow(
                medicine = null,
                applicationType = MedicationApplicationType.PATCH_OFF,
                category = MedicationCategory.ESTRADIOL,
                amountMg = null,
                appliedAt = TEST_INSTANT.plusSeconds(600),
                id = "patch-off",
            ),
            doseRow(
                medicine = cyproterone,
                applicationType = MedicationApplicationType.ORAL,
                amountMg = 50.0,
                appliedAt = TEST_INSTANT.plusSeconds(660),
                id = "oral-cpa",
            ),
        ).map { row -> row to row.applicationType }
    }

    /** Doses Oyama cannot carry; each must be counted as an omission, never emitted. */
    private fun unrepresentableDoses(): List<DoseExportRow> = listOf(
        // Testosterone has no MedicationKey; it can only exist as a custom medicine.
        doseRow(
            medicine = Medicine(
                uuid = testUuid("testosterone"),
                selection = com.mkx.hrttracker.model.medication.MedicineSelection.Custom("Testosterone"),
                category = MedicationCategory.TESTOSTERONE,
                preparation = MedicinePreparation.Pill(strengthMgPerTablet = 100.0),
                displayName = null,
                identityKey = "test-testosterone",
                createdAt = TEST_INSTANT,
                updatedAt = TEST_INSTANT,
                archivedAt = null,
                stock = com.mkx.hrttracker.model.medication.MedicineStock(),
            ),
            applicationType = MedicationApplicationType.INJECTION,
            category = MedicationCategory.TESTOSTERONE,
            amountMg = 100.0,
            id = "testosterone",
        ),
        // Oral benzoate: the route/compound matrix only accepts E2 and EV by mouth.
        doseRow(
            medicine = catalogMedicine(
                MedicationKey.ESTRADIOL_BENZOATE,
                MedicinePreparation.Pill(strengthMgPerTablet = 1.0),
            ),
            applicationType = MedicationApplicationType.ORAL,
            amountMg = 1.0,
            id = "oral-benzoate",
        ),
        // A user-defined medicine has no Oyama code at all.
        doseRow(
            medicine = customMedicine("My gel"),
            applicationType = MedicationApplicationType.GEL,
            category = MedicationCategory.CUSTOM,
            amountMg = 1.0,
            id = "custom-gel",
        ),
        // Spironolactone is a known Oyama enum value but the importer rejects it.
        doseRow(
            medicine = catalogMedicine(
                MedicationKey.SPIRONOLACTONE,
                MedicinePreparation.Pill(strengthMgPerTablet = 25.0),
            ),
            applicationType = MedicationApplicationType.ORAL,
            amountMg = 25.0,
            id = "spironolactone",
        ),
    )

    private fun representableLabs(): List<LabExportRow> = listOf(
        labRow(
            analyteKey = BloodAnalyteKey.E2,
            canonicalValue = 120.0,
            collectedAt = TEST_INSTANT.plusSeconds(60),
            unitSnapshot = "pg_ml",
        ),
        // Entered in pmol/L; the exporter declares pg/mL and writes the canonical value.
        labRow(
            analyteKey = BloodAnalyteKey.E2,
            canonicalValue = 95.0,
            collectedAt = TEST_INSTANT.plusSeconds(120),
            unitSnapshot = "pmol_l",
            value = 350.0,
        ),
        labRow(
            analyteKey = BloodAnalyteKey.T,
            canonicalValue = 450.0,
            collectedAt = TEST_INSTANT.plusSeconds(180),
            unitSnapshot = "ng_dl",
        ),
    )

    private fun unrepresentableLabs(): List<LabExportRow> = listOf(
        labRow(BloodAnalyteKey.PROG, canonicalValue = 1.0, collectedAt = TEST_INSTANT.plusSeconds(240)),
        labRow(BloodAnalyteKey.PRL, canonicalValue = 2.0, collectedAt = TEST_INSTANT.plusSeconds(300)),
        labRow(BloodAnalyteKey.FSH, canonicalValue = 3.0, collectedAt = TEST_INSTANT.plusSeconds(360)),
        labRow(BloodAnalyteKey.LH, canonicalValue = 4.0, collectedAt = TEST_INSTANT.plusSeconds(420)),
        // A custom analyte has no key, so no Oyama unit can describe it.
        labRow(
            analyteKey = null,
            canonicalValue = 5.0,
            collectedAt = TEST_INSTANT.plusSeconds(480),
            unitSnapshot = "mg/L",
            analyteLabel = "Custom",
        ),
    )

    private fun fullBundle(): DataExportBundle = bundleOf(
        doses = representableDoses().map { it.first } + unrepresentableDoses(),
        labs = representableLabs() + unrepresentableLabs(),
        weightKg = 62.5,
    )

    @Test
    fun `export is detected as an Oyama payload`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        assertEquals(ExternalTrackerSourceApp.OYAMA, result.sourceApp)
    }

    @Test
    fun `round trip preserves every representable dose timestamp exactly`() {
        val sources = representableDoses().map { it.first }
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        // Exact equality, not a tolerance: `timeH` is an absolute hour count, and a
        // drift of even a millisecond would mean the contract changed.
        assertEquals(
            sources.map { it.appliedAt.toEpochMilli() },
            result.medicationDoses.map { it.appliedAtEpochMillis },
        )
    }

    @Test
    fun `round trip preserves each dose route`() {
        val sources = representableDoses()
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        assertEquals(
            sources.map { (_, applicationType) -> applicationType },
            result.medicationDoses.map { it.applicationType },
        )
    }

    @Test
    fun `round trip preserves each dose category`() {
        val sources = representableDoses().map { it.first }
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        assertEquals(
            sources.map { it.category },
            result.medicationDoses.map { it.category },
        )
    }

    @Test
    fun `patch removal round trips without a medicine identity`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        val removal = result.medicationDoses.single {
            it.applicationType == MedicationApplicationType.PATCH_OFF
        }
        assertNull(removal.medicineIdentity)
    }

    @Test
    fun `every other exported dose carries a medicine identity`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        result.medicationDoses
            .filter { it.applicationType != MedicationApplicationType.PATCH_OFF }
            .forEach { dose ->
                assertNotNull(
                    "Dose at ${dose.appliedAtEpochMillis} lost its identity",
                    dose.medicineIdentity,
                )
            }
    }

    @Test
    fun `dosed estradiol round trips with a computed E2 equivalent`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        // Patches are excluded on purpose: a patch's estradiol equivalent is null by
        // design, since the PK model routes them by application type and release rate
        // rather than a per-dose mass. Cyproterone is excluded for the same reason —
        // it is not an estrogen, so it has no estradiol equivalent.
        val dosedRoutes = setOf(
            MedicationApplicationType.ORAL,
            MedicationApplicationType.SUBLINGUAL,
            MedicationApplicationType.INJECTION,
            MedicationApplicationType.GEL,
        )
        val dosed = result.medicationDoses.filter {
            it.category == MedicationCategory.ESTRADIOL && it.applicationType in dosedRoutes
        }
        assertEquals(7, dosed.size)
        dosed.forEach { dose ->
            assertNotNull("No E2 equivalent for ${dose.applicationType}", dose.equivalentE2Mg)
            assertTrue(dose.equivalentE2Mg!! > 0.0)
        }
    }

    @Test
    fun `round trip preserves every representable lab`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        assertEquals(
            listOf(
                BloodAnalyteKey.E2 to BloodUnitKey.PG_ML,
                BloodAnalyteKey.E2 to BloodUnitKey.PG_ML,
                BloodAnalyteKey.T to BloodUnitKey.NG_DL,
            ),
            result.labResults.map { it.analyteKey to it.unitKey },
        )
        assertEquals(
            listOf(TEST_INSTANT.plusSeconds(60), TEST_INSTANT.plusSeconds(120), TEST_INSTANT.plusSeconds(180))
                .map { it.toEpochMilli() },
            result.labResults.map { it.collectedAtEpochMillis },
        )
        // The value travels in the canonical unit the exporter declares, so a result
        // entered in pmol/L arrives as pg/mL rather than being dropped.
        assertEquals(
            listOf(120.0, 95.0, 450.0),
            result.labResults.map { it.value },
        )
    }

    @Test
    fun `exporter never emits a row the parser rejects`() {
        val result = parser.parse(OyamaJsonExporter.buildJson(fullBundle()))

        // Any skip means the exporter and the parser's acceptance rules have drifted,
        // which is the failure this whole test class exists to catch.
        val skips = result.warnings.filter { it.reason != ExternalImportWarningReason.SOURCE_FALLBACK }
        assertEquals(emptyList<String>(), skips.map { "${it.reason}: ${it.message}" })
    }

    @Test
    fun `the plan's omission counts match what the file leaves out`() {
        val bundle = fullBundle()
        val plan = OyamaJsonExporter.plan(bundle)

        val representableDoseCount = representableDoses().size
        val representableLabCount = representableLabs().size
        assertEquals(representableDoseCount, plan.doses.size)
        assertEquals(representableLabCount, plan.transfemLabs.size + plan.transmascLabs.size)
        assertEquals(
            bundle.doseRows.size - representableDoseCount,
            plan.omittedDoseCount,
        )
        assertEquals(
            bundle.labRows.size - representableLabCount,
            plan.omittedLabCount,
        )
    }

    @Test
    fun `labs sharing a panel timestamp and analyte are reported rather than silently merged`() {
        // The importer groups results into panels keyed on the timestamp and dedupes
        // on (panel, analyte), so two panels at the same instant carrying the same
        // analyte would collapse on re-import. The exporter must say so up front.
        val duplicatedPanelInstant = TEST_INSTANT.plusSeconds(900)
        val bundle = bundleOf(
            labs = listOf(
                labRow(BloodAnalyteKey.E2, canonicalValue = 100.0, collectedAt = duplicatedPanelInstant),
                labRow(BloodAnalyteKey.E2, canonicalValue = 200.0, collectedAt = duplicatedPanelInstant),
            ),
        )

        val plan = OyamaJsonExporter.plan(bundle)
        assertEquals(1, plan.transfemLabs.size)
        assertEquals(1, plan.omittedLabCount)

        val result = parser.parse(OyamaJsonExporter.buildJson(bundle))
        assertEquals(1, result.labResults.size)
    }

    @Test
    fun `an Oyama export of only unrepresentable data still parses as an empty Oyama payload`() {
        val bundle = bundleOf(
            doses = unrepresentableDoses(),
            labs = unrepresentableLabs(),
        )

        val result = parser.parse(OyamaJsonExporter.buildJson(bundle))
        assertEquals(ExternalTrackerSourceApp.OYAMA, result.sourceApp)
        assertEquals(0, result.medicationDoses.size)
        assertEquals(0, result.labResults.size)
    }
}
