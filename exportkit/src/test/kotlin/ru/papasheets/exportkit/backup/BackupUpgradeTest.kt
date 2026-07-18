package ru.papasheets.exportkit.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Апгрейд бэкапа v1 до текущей формы.
 *
 * v1 задан **литеральным JSON, а не собран текущими DTO**, и это главное свойство теста. Сгенерируй
 * его сегодняшним кодом — и он поедет вместе с продом: переименуют поле, перестанут писать колонку,
 * а тест продолжит зеленеть, проверяя круг из самого себя. Здесь же лежит настоящий байт-в-байт
 * снимок того, что записала версия приложения с форматом 1, и разъехаться с ним молча нельзя.
 */
class BackupUpgradeTest {

    /** Снимок data.json из .psbackup формата 1. Менять его нельзя — такие файлы уже существуют. */
    private val v1DataJson = """
        {
          "journals": [
            {"id":"j1","year":2026,"month":7,"title":"Июль 2026","createdAt":1}
          ],
          "contractors": [
            {"id":"c1","name":"Петров","shortName":"ПТР","colorIndex":0,"orderIndex":0,"isArchived":false,"createdAt":1}
          ],
          "records": [
            {"id":"r1","journalId":"j1","dateEpochDay":100,"contractorId":"c1",
             "locationCode":"1-01","workText":"Штукатурка потолка","photoId":"p1","createdAt":1,"updatedAt":2},
            {"id":"r2","journalId":"j1","dateEpochDay":101,"contractorId":"c1",
             "locationCode":"","workText":"  Стяжка пола  ","photoId":null,"createdAt":3,"updatedAt":4},
            {"id":"r3","journalId":"j1","dateEpochDay":102,"contractorId":"c1",
             "locationCode":"   ","workText":"","photoId":null,"createdAt":5,"updatedAt":6}
          ],
          "photos": [
            {"id":"p1","width":1280,"height":720,"sizeBytes":12345,"originUri":null,"createdAt":1}
          ],
          "locationPresets": [
            {"id":"l1","code":"1-01","orderIndex":0}
          ]
        }
    """.trimIndent()

    private fun v1ManifestJson(formatVersion: Int = 1) =
        """{"formatVersion":$formatVersion,"dbSchemaVersion":1,"appVersionName":"1.0","exportedAtMillis":1750000000000}"""

    private fun archive(manifestJson: String, dataJson: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(dataJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun readV1(): BackupContents =
        BackupReader.read(ByteArrayInputStream(archive(v1ManifestJson(), v1DataJson))) { _, _, _ -> }

    @Test
    fun `v1 archive is readable and reports its original format version`() {
        val contents = readV1()

        assertEquals(1, contents.manifest.formatVersion)
        assertEquals(3, contents.data.records.size)
        assertEquals(1, contents.data.journals.size)
    }

    @Test
    fun `upgrade adds built-in field defs under their constant ids`() {
        val fields = readV1().data.fieldDefs

        assertEquals(listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID), fields.map { it.id })
        assertEquals(listOf(BuiltInFields.LOCATION_KEY, BuiltInFields.WORK_KEY), fields.map { it.key })
        assertTrue(fields.all { it.isBuiltIn && !it.isArchived })
    }

    @Test
    fun `upgrade unfolds legacy columns into record values`() {
        val values = readV1().data.recordValues

        assertEquals(
            listOf(
                BackupRecordValue("r1", BuiltInFields.LOCATION_ID, "1-01"),
                BackupRecordValue("r1", BuiltInFields.WORK_ID, "Штукатурка потолка"),
                // r2: пустая локация строки не породила, вид работ перенесён обрезанным
                BackupRecordValue("r2", BuiltInFields.WORK_ID, "Стяжка пола"),
            ),
            values,
        )
    }

    @Test
    fun `upgrade creates no values for blank and whitespace-only columns`() {
        val values = readV1().data.recordValues

        // r3 — «   » и «»: пробельное значение неотличимо от пустого, строк быть не должно
        assertTrue(values.none { it.recordId == "r3" })
        assertTrue(values.none { it.value.isBlank() })
    }

    @Test
    fun `upgrade clears legacy columns so nothing reads them by accident`() {
        for (record in readV1().data.records) {
            assertNull(record.locationCode)
            assertNull(record.workText)
        }
    }

    @Test
    fun `v2 archive keeps its record values and only gets the preset step`() {
        val contents = BackupReader.read(
            ByteArrayInputStream(archive(v1ManifestJson(formatVersion = 2), v1DataJson)),
        ) { _, _, _ -> }

        // Манифест объявил v2 — значит колонки записей разворачивать не нужно, они уже развёрнуты.
        assertTrue(contents.data.fieldDefs.isEmpty())
        assertTrue(contents.data.recordValues.isEmpty())
        // А вот шаг v2 → v3 применяется: пресеты v2 ещё не знали своего поля.
        assertEquals(
            listOf(BackupFieldPreset("l1", BuiltInFields.LOCATION_ID, "1-01", 0)),
            contents.data.fieldPresets,
        )
    }

    /**
     * Главное свойство апгрейда: шаги складываются в цепочку.
     *
     * Файл v1 обязан доехать до текущей формы через все промежуточные версии — иначе появление
     * второй версии формата превратило бы самые старые бэкапы в нечитаемые ровно тогда, когда до
     * них дошло дело. Здесь это видно на одном файле: пресет v1 доезжает до v3 через шаг, который
     * его вообще не касался, и получает поле-владельца.
     */
    @Test
    fun `v1 archive is upgraded through every step up to the current format`() {
        val data = readV1().data

        assertEquals(
            listOf(BackupFieldPreset("l1", BuiltInFields.LOCATION_ID, "1-01", 0)),
            data.fieldPresets,
        )
        // Форма ≤ v2 после апгрейда пуста: второго места, где лежат пресеты, остаться не должно.
        assertTrue(data.locationPresets.isEmpty())
        // И шаг v1 → v2 при этом отработал, а не потерялся за более поздним.
        assertEquals(listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID), data.fieldDefs.map { it.id })
        assertEquals(3, data.recordValues.size)
    }

    @Test
    fun `format version newer than current is rejected`() {
        val tooNew = BackupManifest.CURRENT_FORMAT_VERSION + 1
        try {
            BackupReader.read(
                ByteArrayInputStream(archive(v1ManifestJson(formatVersion = tooNew), v1DataJson)),
            ) { _, _, _ -> }
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains(tooNew.toString()))
        }
    }
}
