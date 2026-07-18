package ru.papasheets.exportkit.backup

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MANIFEST_ENTRY = "manifest.json"
private const val DATA_ENTRY = "data.json"
private const val MEDIUM_PREFIX = "photos/medium/"
private const val THUMB_PREFIX = "photos/thumb/"

private val json = Json { ignoreUnknownKeys = true }

/** Разобранный .psbackup: к моменту возврата из [BackupReader.read] версия формата уже проверена. */
data class BackupContents(val manifest: BackupManifest, val data: BackupData)

/**
 * Читает .psbackup потоково: manifest.json и data.json разбираются целиком (это килобайты), фото —
 * по одному через [onPhoto], чтобы не держать сотни файлов в памяти разом. Поток, переданный в
 * [onPhoto], валиден только внутри колбэка (как обычная запись [ZipInputStream]) — закрывать его не
 * нужно, жизненным циклом владеет сам [BackupReader]; недочитанные байты досасываются при переходе
 * к следующей записи архива.
 */
object BackupReader {
    fun read(input: InputStream, onPhoto: (photoId: String, kind: BackupPhotoKind, stream: InputStream) -> Unit): BackupContents {
        var manifest: BackupManifest? = null
        var data: BackupData? = null
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == MANIFEST_ENTRY -> {
                            val parsed = parseManifest(zip.readBytes())
                            validateFormatVersion(parsed)
                            manifest = parsed
                        }
                        name == DATA_ENTRY -> data = parseData(zip.readBytes())
                        name.startsWith(MEDIUM_PREFIX) ->
                            onPhoto(photoIdFrom(name, MEDIUM_PREFIX), BackupPhotoKind.MEDIUM, NonClosingZipEntryStream(zip))
                        name.startsWith(THUMB_PREFIX) ->
                            onPhoto(photoIdFrom(name, THUMB_PREFIX), BackupPhotoKind.THUMB, NonClosingZipEntryStream(zip))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            throw BackupFormatException("Файл повреждён или не является бэкапом PapaSheets", e)
        }

        val finalManifest = manifest ?: throw BackupFormatException("В архиве нет manifest.json")
        val finalData = data ?: throw BackupFormatException("В архиве нет data.json")
        return BackupContents(finalManifest, finalData)
    }

    private fun photoIdFrom(entryName: String, prefix: String): String = entryName.removePrefix(prefix).removeSuffix(".jpg")

    private fun validateFormatVersion(manifest: BackupManifest) {
        if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
            throw BackupFormatException(
                "Формат бэкапа (версия ${manifest.formatVersion}) не поддерживается этой версией приложения",
            )
        }
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest = try {
        json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw BackupFormatException("manifest.json повреждён", e)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("manifest.json повреждён", e)
    }

    private fun parseData(bytes: ByteArray): BackupData = try {
        json.decodeFromString(BackupData.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw BackupFormatException("data.json повреждён", e)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("data.json повреждён", e)
    }

    /** close() — no-op: время жизни записи архива принадлежит циклу чтения в [read], не вызывающей стороне. */
    private class NonClosingZipEntryStream(private val zip: ZipInputStream) : InputStream() {
        override fun read(): Int = zip.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len)
        override fun close() {}
    }
}
