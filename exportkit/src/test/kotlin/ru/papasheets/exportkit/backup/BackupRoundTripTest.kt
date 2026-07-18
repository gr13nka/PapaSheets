package ru.papasheets.exportkit.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val PHOTO_A = "photo-a"
private const val PHOTO_B = "photo-b"

class BackupRoundTripTest {

    private fun manifest() = BackupManifest(
        formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
        dbSchemaVersion = 1,
        appVersionName = "0.1",
        exportedAtMillis = 1_752_000_000_000L,
    )

    /** Кириллица и `&<>"` вперемешку — проверка, что JSON, а не XML-экранирование, не корёжит текст. */
    private fun data() = BackupData(
        journals = listOf(BackupJournal(id = "j1", year = 2026, month = 7, title = "Июль 2026", createdAt = 1)),
        contractors = listOf(
            BackupContractor(
                id = "c1", name = "Петров & \"Сыновья\"", shortName = "ПиС",
                colorIndex = 0, orderIndex = 0, isArchived = false, createdAt = 1,
            ),
        ),
        records = listOf(
            BackupRecord(
                id = "r1", journalId = "j1", dateEpochDay = 100, contractorId = "c1",
                locationCode = "1-01", workText = "Штукатурка <потолок>\nвторая строка",
                photoId = PHOTO_A, createdAt = 1, updatedAt = 2,
            ),
        ),
        photos = listOf(
            BackupPhoto(id = PHOTO_A, width = 1280, height = 720, sizeBytes = 12345, originUri = "content://a", createdAt = 1),
            BackupPhoto(id = PHOTO_B, width = 720, height = 1280, sizeBytes = 543, originUri = null, createdAt = 2),
        ),
        locationPresets = listOf(BackupLocationPreset(id = "l1", code = "1-01", orderIndex = 0)),
    )

    private fun photoBytes(id: String, kind: BackupPhotoKind): ByteArray = "$id/${kind.zipDir}-bytes".toByteArray()

    private fun photoProvider(missing: Pair<String, BackupPhotoKind>? = null): BackupPhotoProvider = object : BackupPhotoProvider {
        override fun ids() = listOf(PHOTO_A, PHOTO_B)
        override fun openMedium(id: String) =
            if (missing == id to BackupPhotoKind.MEDIUM) null else ByteArrayInputStream(photoBytes(id, BackupPhotoKind.MEDIUM))
        override fun openThumb(id: String) =
            if (missing == id to BackupPhotoKind.THUMB) null else ByteArrayInputStream(photoBytes(id, BackupPhotoKind.THUMB))
    }

    @Test
    fun `round trip preserves manifest, data and photo bytes exactly`() {
        val out = ByteArrayOutputStream()
        val writeResult = BackupWriter.write(manifest(), data(), photoProvider(), out)
        assertEquals(4, writeResult.photoFilesWritten) // 2 фото × (medium+thumb)
        assertTrue(writeResult.skippedPhotoFiles.isEmpty())

        val collectedPhotos = LinkedHashMap<Pair<String, BackupPhotoKind>, ByteArray>()
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray())) { photoId, kind, stream ->
            collectedPhotos[photoId to kind] = stream.readBytes()
        }

        assertEquals(manifest(), contents.manifest)
        assertEquals(data(), contents.data)
        assertEquals(4, collectedPhotos.size)
        for (id in listOf(PHOTO_A, PHOTO_B)) {
            for (kind in BackupPhotoKind.entries) {
                assertArrayEquals(photoBytes(id, kind), collectedPhotos.getValue(id to kind))
            }
        }
    }

    @Test
    fun `missing photo file on disk is skipped by the writer and reflected in the result`() {
        val out = ByteArrayOutputStream()
        val writeResult = BackupWriter.write(manifest(), data(), photoProvider(missing = PHOTO_B to BackupPhotoKind.THUMB), out)

        assertEquals(3, writeResult.photoFilesWritten)
        assertEquals(listOf(SkippedPhotoFile(PHOTO_B, BackupPhotoKind.THUMB)), writeResult.skippedPhotoFiles)

        val seenKinds = mutableSetOf<Pair<String, BackupPhotoKind>>()
        BackupReader.read(ByteArrayInputStream(out.toByteArray())) { photoId, kind, _ -> seenKinds += photoId to kind }
        assertTrue((PHOTO_B to BackupPhotoKind.THUMB) !in seenKinds)
        assertEquals(3, seenKinds.size)
    }

    @Test
    fun `corrupted archive throws BackupFormatException`() {
        val garbage = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        try {
            BackupReader.read(garbage) { _, _, _ -> }
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `archive without manifest throws BackupFormatException`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(Json.encodeToString(BackupData.serializer(), data()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        try {
            BackupReader.read(ByteArrayInputStream(out.toByteArray())) { _, _, _ -> }
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    @Test
    fun `unsupported format version throws BackupFormatException`() {
        val out = ByteArrayOutputStream()
        val futureManifest = manifest().copy(formatVersion = 999)
        BackupWriter.write(futureManifest, data(), photoProvider(), out)
        try {
            BackupReader.read(ByteArrayInputStream(out.toByteArray())) { _, _, _ -> }
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("999"))
        }
    }
}
