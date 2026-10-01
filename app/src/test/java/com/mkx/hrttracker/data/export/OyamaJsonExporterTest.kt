package com.mkx.hrttracker.data.export

import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import com.mkx.hrttracker.model.medication.DoseInstruction
import com.mkx.hrttracker.model.medication.MedicationApplicationType
import com.mkx.hrttracker.model.medication.MedicationKey
import com.mkx.hrttracker.model.medication.MedicinePreparation
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the raw JSON shape of an Oyama export.
 *
 * The round-trip test proves the payload survives this app's own importer; this
 * one pins the parts of the contract that only matter to the *other* reader —
 * above all the literal vocabulary, which Oyama compares with exact equality
 * rather than normalising. A mis-cased ester there is not a parse error: it is
 * silently rewritten to estradiol.
 */
class OyamaJsonExporterTest {
    private val moshi: Moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    /** Oyama's `Route` and `Ester` enum values, transcribed from its own source. */
    private val oyamaRoutes = setOf("oral", "sublingual", "injection", "gel", "patchApply", "patchRemove")
    private val oyamaEsters = setOf("E2", "EB", "EV", "EC", "EN", "EU", "CPA")
    private val oyamaLabUnits = setOf("pg/ml", "pmol/l", "ng/dl", "nmol/l")

    private fun parse(bundle: DataExportBundle): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return anyAdapter.fromJson(OyamaJsonExporter.buildJson(bundle)) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.asMap(key: String): Map<String, Any?> =
        getValue(key) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.asList(key: String): List<Any?> = getValue(key) as List<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.rowMap(): Map<String, Any?> = this as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.rowMap(key: String): Map<String, Any?> =
        getValue(key) as Map<String, Any?>

    private fun sampleBundle(): DataExportBundle = bundleOf(
        doses = listOf(
            doseRow(
                medicine = catalogMedicine(
                    MedicationKey.ESTRADIOL,
                    MedicinePreparation.Pill(strengthMgPerTablet = 2.0),
                ),
                applicationType = MedicationApplicationType.ORAL,
                doseInstruction = DoseInstruction.TabletFraction(numerator = 1, denominator = 1),
                amountMg = 2.0,
                id = "oral-e2",
            ),
            doseRow(
                medicine = catalogMedicine(
                    MedicationKey.ESTRADIOL_PATCH,
                    MedicinePreparation.Patch(
                        MedicinePreparation.PatchSpecification.ReleaseRateMcgPerDay(100.0)
                    ),
                ),
                applicationType = MedicationApplicationType.PATCH_ON,
                amountMg = null,
                id = "patch-release-rate",
            ),
            doseRow(
                medicine = null,
                applicationType = MedicationApplicationType.PATCH_OFF,
                amountMg = null,
                id = "patch-off",
            ),
        ),
        labs = listOf(
            labRow(BloodAnalyteKey.E2, canonicalValue = 120.0, unitSnapshot = "pg_ml"),
            labRow(BloodAnalyteKey.T, canonicalValue = 450.0, unitSnapshot = "ng_dl"),
        ),
        weightKg = 62.5,
    )

    @Test
    fun `carries the metadata Oyama writes`() {
        val json = parse(sampleBundle())

        val meta = json.asMap("meta")
        assertEquals(2.0, (meta.getValue("version") as Double), 0.0)
        assertNotNull(meta["exportedAt"])
        assertEquals("transfem", json["mode"])
    }

    @Test
    fun `omits the encrypted flag entirely`() {
        // The importer rejects a payload whose flag is anything other than an
        // explicit "not encrypted" value, and Oyama's own plaintext export has no
        // such key — so the honest shape is to leave it out.
        val json = parse(sampleBundle())

        assertFalse(json.containsKey("encrypted"))
    }

    @Test
    fun `never emits transmasc events`() {
        // Oyama replaces the other mode's records rather than merging them, so an
        // empty `transmasc.events` would wipe a transfem user's transmasc doses.
        // Nothing reads it back either: doses come from `transfem.events` alone.
        val json = parse(sampleBundle())

        val transfem = json.asMap("modes").asMap("transfem")
        val transmasc = json.asMap("modes").asMap("transmasc")
        assertTrue(transfem.containsKey("events"))
        assertTrue(transfem.containsKey("labResults"))
        assertTrue(transmasc.containsKey("labResults"))
        assertFalse(transmasc.containsKey("events"))
    }

    @Test
    fun `splits lab results across the two mode blocks by analyte`() {
        val json = parse(sampleBundle())

        val modes = json.asMap("modes")
        assertEquals(1, modes.asMap("transfem").asList("labResults").size)
        assertEquals(1, modes.asMap("transmasc").asList("labResults").size)
        // E2 by the unit pairing the importer uses to identify it.
        assertEquals("pg/ml", modes.asMap("transfem").asList("labResults")[0].rowMap()["unit"])
        assertEquals("ng/dl", modes.asMap("transmasc").asList("labResults")[0].rowMap()["unit"])
    }

    @Test
    fun `root events and lab results mirror the active mode`() {
        val json = parse(sampleBundle())

        val transfem = json.asMap("modes").asMap("transfem")
        assertEquals(transfem.asList("events"), json.asList("events"))
        assertEquals(transfem.asList("labResults"), json.asList("labResults"))
    }

    @Test
    fun `carries the weight as a plain number of kilograms`() {
        val json = parse(sampleBundle())

        assertEquals(62.5, json["weight"] as Double, 0.0)
    }

    @Test
    fun `omits the weight when it is unknown`() {
        val json = parse(bundleOf(doses = emptyList(), labs = emptyList(), weightKg = null))

        assertFalse(json.containsKey("weight"))
    }

    @Test
    fun `emits only Oyama's own route and ester literals`() {
        val rows = parse(sampleBundle()).asList("events").map { it.rowMap() }

        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue("Unexpected route ${row["route"]}", row["route"] in oyamaRoutes)
            assertTrue("Unexpected ester ${row["ester"]}", row["ester"] in oyamaEsters)
        }
        // camelCase on the patch routes specifically: Oyama compares these literally.
        assertTrue(rows.any { it["route"] == "patchApply" })
        assertTrue(rows.any { it["route"] == "patchRemove" })
    }

    @Test
    fun `emits only unit strings the importer can map back to an analyte`() {
        val json = parse(sampleBundle())
        val units = json.asMap("modes")
            .asMap("transfem").asList("labResults").map { it.rowMap()["unit"] } +
            json.asMap("modes").asMap("transmasc").asList("labResults").map { it.rowMap()["unit"] }

        assertTrue(units.isNotEmpty())
        units.forEach { unit ->
            assertTrue("Unexpected lab unit $unit", unit in oyamaLabUnits)
        }
    }

    @Test
    fun `writes timeH as an absolute hour count since the epoch`() {
        val json = parse(sampleBundle())
        val expected = TEST_INSTANT.toEpochMilli() / 3_600_000.0

        json.asList("events").map { it.rowMap() }.forEach { row ->
            assertEquals(expected, row["timeH"] as Double, 1e-6)
        }
    }

    @Test
    fun `writes the administered mass as doseMG`() {
        val json = parse(sampleBundle())
        val oral = json.asList("events")
            .map { it.rowMap() }
            .single { it["route"] == "oral" }

        assertEquals(2.0, oral["doseMG"] as Double, 0.0)
        assertFalse(oral.containsKey("extras"))
    }

    @Test
    fun `sends a patch release rate in extras rather than as a mass`() {
        val json = parse(sampleBundle())
        val patch = json.asList("events")
            .map { it.rowMap() }
            .single { it["route"] == "patchApply" }

        val extras = patch.rowMap("extras")
        assertEquals(100.0, extras["releaseRateUGPerDay"] as Double, 0.0)
        // Oyama reads the rate and ignores the mass for patches; zero keeps the field
        // well formed for readers that still expect it.
        assertEquals(0.0, patch["doseMG"] as Double, 0.0)
    }

    @Test
    fun `never emits a dose the parser would consider malformed`() {
        // Every emitted dose needs a positive mass, a release rate, or a removal route.
        val json = parse(sampleBundle())

        json.asList("events").map { it.rowMap() }.forEach { row ->
            val route = row["route"]
            val doseMg = row["doseMG"] as Double
            val hasRate = (row["extras"] as? Map<*, *>)?.containsKey("releaseRateUGPerDay") == true
            assertTrue(
                "Dose on $route carries neither a mass nor a rate",
                doseMg > 0.0 || hasRate || route == "patchRemove",
            )
        }
    }
}
