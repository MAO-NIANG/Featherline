package com.mkx.hrttracker.data.export

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DataExportNumbersTest {
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `writes an exact number without a locale's decimal separator`() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("1.5", exportNumber(1.5))
        assertEquals("0.25", exportNumber(0.25))
    }

    @Test
    fun `drops trailing zeros and avoids scientific notation`() {
        assertEquals("2", exportNumber(2.0))
        assertEquals("100", exportNumber(100.0))
        assertEquals("0.000001", exportNumber(0.000001))
    }

    @Test
    fun `renders an absent value as empty rather than as a word`() {
        assertEquals("", exportNumber(null as Double?))
    }

    @Test
    fun `renders a non-finite value as empty rather than as NaN`() {
        assertEquals("", exportNumber(Double.NaN))
        assertEquals("", exportNumber(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `rounds a derived report figure so it does not print seventeen digits`() {
        // The estradiol equivalent of a 2 mg dose arrives as a full double.
        assertEquals("0.76", exportReportNumber(0.7640953716690041))
    }

    @Test
    fun `report rounding keeps the small numbers readable`() {
        assertEquals("0.03", exportReportNumber(0.034))
        assertEquals("0", exportReportNumber(0.001))
    }

    @Test
    fun `report rounding leaves whole and short numbers alone`() {
        assertEquals("224", exportReportNumber(224.0))
        assertEquals("12.7", exportReportNumber(12.7))
    }

    @Test
    fun `report rounding writes no locale-specific separator`() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("0.76", exportReportNumber(0.7640953716690041))
    }
}
