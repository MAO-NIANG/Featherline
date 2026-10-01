package com.mkx.hrttracker.data.export

import org.junit.Assert.assertEquals
import org.junit.Test

class DataExportSummaryTest {
    private val summary = DataExportSummary(
        doseCount = 152,
        labCount = 16,
        representableDoseCount = 144,
        representableLabCount = 6,
    )

    @Test
    fun `an Oyama export reports what it kept and what it left out`() {
        val report = summary.reportFor(oyamaCompatible = true)

        assertEquals(144, report.doses)
        assertEquals(6, report.labs)
        assertEquals(18, report.omitted)
    }

    @Test
    fun `every other format reports the totals and declares no omissions`() {
        val report = summary.reportFor(oyamaCompatible = false)

        assertEquals(152, report.doses)
        assertEquals(16, report.labs)
        assertEquals(0, report.omitted)
    }

    @Test
    fun `an export that loses nothing still names no shortfall`() {
        val lossless = DataExportSummary(
            doseCount = 10,
            labCount = 2,
            representableDoseCount = 10,
            representableLabCount = 2,
        )

        val report = lossless.reportFor(oyamaCompatible = true)

        assertEquals(0, report.omitted)
        assertEquals(10, report.doses)
        assertEquals(2, report.labs)
    }

    @Test
    fun `an empty export is reported as empty`() {
        val empty = DataExportSummary(
            doseCount = 0,
            labCount = 0,
            representableDoseCount = 0,
            representableLabCount = 0,
        )

        assertEquals(true, empty.isEmpty)
        assertEquals(0, empty.reportFor(oyamaCompatible = false).doses)
    }
}
