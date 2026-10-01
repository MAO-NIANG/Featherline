package com.mkx.hrttracker.data.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mkx.hrttracker.data.backup.BackupExportService
import com.mkx.hrttracker.data.repository.BloodTestRepository
import com.mkx.hrttracker.data.repository.MedicationLogRepository
import com.mkx.hrttracker.data.repository.UserProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the parts of [DataExportService] that do not need Android resources.
 *
 * The rows themselves are assembled against a localized `Context`, which a JVM
 * test cannot provide, so those paths are exercised on a device. What is testable
 * here — the filename scheme, the temp-file lifecycle, and the assertion that a
 * failed write still cleans up — is exactly the part where a mistake leaks files
 * into the cache or hands the user a misleading name.
 */
class DataExportServiceTest {
    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val destinationUri: Uri = mockk()

    private lateinit var service: DataExportService
    private lateinit var cacheDir: File

    private val exportedAt: Instant = Instant.parse("2026-10-02T08:30:00Z")

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("data-export-service-test-").toFile()
        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns contentResolver
        service = DataExportService(
            context = context,
            medicationLogRepository = mockk<MedicationLogRepository>(),
            bloodTestRepository = mockk<BloodTestRepository>(),
            userProfileRepository = mockk<UserProfileRepository>(),
            backupExportService = mockk<BackupExportService>(),
            pdfRenderer = DataExportPdfRenderer(),
        )
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun name(format: DataExportFormat, jsonFormat: JsonExportFormat? = null): String =
        DataExportService.buildExportFileName(
            format = format,
            jsonFormat = jsonFormat,
            exportedAt = exportedAt,
            zoneId = ZoneId.of("UTC"),
        )

    private fun preparedFile(contents: ByteArray = byteArrayOf(1, 2, 3)): PreparedDataExport {
        val file = File(cacheDir, "prepared-export-test.tmp")
        file.writeBytes(contents)
        return PreparedDataExport(displayName = "export.csv", tempFilePath = file.absolutePath)
    }

    /** Runs a suspend export and returns the IOException it must raise. */
    private suspend fun expectIOException(block: suspend () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected the export to fail with an IOException")
    }

    @Test
    fun `names each file so it cannot be mistaken for another`() {
        assertEquals("featherline-data-2026-10-02_08-30-00.csv", name(DataExportFormat.CSV))
        assertEquals("featherline-data-2026-10-02_08-30-00.pdf", name(DataExportFormat.PDF))
        // The two JSON shapes get different stems: only one of them can be read back
        // into this app, and identically named files would be indistinguishable later.
        assertEquals(
            "featherline-oyama-2026-10-02_08-30-00.json",
            name(DataExportFormat.JSON, JsonExportFormat.OYAMA),
        )
        assertEquals(
            "featherline-backup-plaintext-2026-10-02_08-30-00.json",
            name(DataExportFormat.JSON, JsonExportFormat.NATIVE),
        )
    }

    @Test
    fun `maps each format to the mime type the picker needs`() {
        assertEquals("text/csv", DataExportService.mimeTypeFor(DataExportFormat.CSV))
        assertEquals("application/pdf", DataExportService.mimeTypeFor(DataExportFormat.PDF))
        assertEquals("application/json", DataExportService.mimeTypeFor(DataExportFormat.JSON))
    }

    @Test
    fun `copies the prepared payload to the chosen document and drops the temp file`() = runTest {
        val prepared = preparedFile(byteArrayOf(7, 8, 9))
        val captured = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(destinationUri) } returns captured

        val exported = service.exportPreparedExport(destinationUri, prepared)

        assertEquals(listOf<Byte>(7, 8, 9), captured.toByteArray().toList())
        assertEquals("export.csv", exported.displayName)
        assertFalse("Temp file was left behind", File(prepared.tempFilePath).exists())
    }

    @Test
    fun `fails without writing when the prepared payload has already gone`() = runTest {
        val prepared = PreparedDataExport(
            displayName = "export.csv",
            tempFilePath = File(cacheDir, "never-written.tmp").absolutePath,
        )

        val failure = expectIOException {
            service.exportPreparedExport(destinationUri, prepared)
        }

        assertTrue(failure.message.orEmpty().contains("no longer available"))
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }

    @Test
    fun `a failed write still deletes the temp file and surfaces the error`() = runTest {
        val prepared = preparedFile()
        every { contentResolver.openOutputStream(destinationUri) } throws IOException("disk full")

        expectIOException {
            service.exportPreparedExport(destinationUri, prepared)
        }

        assertFalse("Temp file survived a failed export", File(prepared.tempFilePath).exists())
    }

    @Test
    fun `a provider that returns no stream is treated as a failure`() = runTest {
        val prepared = preparedFile()
        every { contentResolver.openOutputStream(destinationUri) } returns null

        expectIOException {
            service.exportPreparedExport(destinationUri, prepared)
        }

        assertFalse("Temp file survived a failed export", File(prepared.tempFilePath).exists())
    }

    @Test
    fun `discarding a prepared export removes its temp file`() = runTest {
        val prepared = preparedFile()

        service.discardPreparedExport(prepared)

        assertFalse(File(prepared.tempFilePath).exists())
    }

    @Test
    fun `sweeps abandoned temp files but leaves anything recent or unrelated alone`() {
        val stale = File(cacheDir, "prepared-export-stale.tmp").apply { writeBytes(ByteArray(4)) }
        val fresh = File(cacheDir, "prepared-export-fresh.tmp").apply { writeBytes(ByteArray(4)) }
        val unrelated = File(cacheDir, "some-other-cache-entry.tmp").apply { writeBytes(ByteArray(4)) }
        val sevenHoursAgo = System.currentTimeMillis() - 7L * 60L * 60L * 1000L
        stale.setLastModified(sevenHoursAgo)
        unrelated.setLastModified(sevenHoursAgo)

        service.sweepStalePreparedExports()

        assertFalse("Stale export temp file was left in the cache", stale.exists())
        assertTrue("A recent temp file was swept", fresh.exists())
        assertTrue("An unrelated cache file was swept", unrelated.exists())
    }

    @Test
    fun `the clipboard ceiling is below a megabyte to stay within what Binder accepts`() {
        assertTrue(DataExportService.MAX_CLIPBOARD_JSON_CHARS in 1..1_000_000)
    }

    @Test
    fun `an export writes to the stream it was handed`() = runTest {
        // Guards the shape of the write: the whole payload, then closed.
        val prepared = preparedFile(byteArrayOf(1, 2, 3, 4, 5))
        val captured = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(destinationUri) } returns captured

        service.exportPreparedExport(destinationUri, prepared)

        assertEquals(5, captured.size())
    }

    @Test
    fun `a stream that rejects the write propagates the failure`() = runTest {
        val prepared = preparedFile()
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("closed")
        }
        every { contentResolver.openOutputStream(destinationUri) } returns failing

        expectIOException {
            service.exportPreparedExport(destinationUri, prepared)
        }
        assertFalse(File(prepared.tempFilePath).exists())
    }
}
