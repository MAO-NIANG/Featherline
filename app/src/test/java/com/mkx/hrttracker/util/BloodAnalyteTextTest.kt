package com.mkx.hrttracker.util

import com.mkx.hrttracker.model.bloodtest.BloodAnalyteKey
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodAnalyteTextTest {
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `labels each built-in analyte with its short code`() {
        assertEquals("E2", calibrationAnalyteLabel(BloodAnalyteKey.E2))
        assertEquals("T", calibrationAnalyteLabel(BloodAnalyteKey.T))
        assertEquals("PROG", calibrationAnalyteLabel(BloodAnalyteKey.PROG))
        assertEquals("PRL", calibrationAnalyteLabel(BloodAnalyteKey.PRL))
        assertEquals("FSH", calibrationAnalyteLabel(BloodAnalyteKey.FSH))
        assertEquals("LH", calibrationAnalyteLabel(BloodAnalyteKey.LH))
    }

    @Test
    fun `the label does not depend on the default locale`() {
        // Turkish lowercases "I" to a dotless "ı" and uppercases "i" to "İ", so a
        // locale-sensitive uppercase() would change the exported analyte code.
        Locale.setDefault(Locale.forLanguageTag("tr"))

        assertEquals("E2", calibrationAnalyteLabel(BloodAnalyteKey.E2))
        assertEquals("T", calibrationAnalyteLabel(BloodAnalyteKey.T))
        assertEquals("PROG", calibrationAnalyteLabel(BloodAnalyteKey.PROG))
    }

    @Test
    fun `every built-in analyte has a full name and none collide`() {
        val resources = BloodAnalyteKey.entries.map { key ->
            val res = calibrationAnalyteFullNameRes(key)
            assertTrue("Analyte $key resolved to no string resource", res != 0)
            res
        }

        assertEquals(
            "Two analytes share a display name",
            resources.size,
            resources.toSet().size,
        )
    }
}
