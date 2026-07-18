package ru.papasheets.photos

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papasheets.data.db.dao.PhotoDao
import ru.papasheets.data.db.entity.PhotoEntity

private const val TAG = "PhotoStore"

/** Файлы старше этого возраста подчищает [PhotoStore.collectGarbage] — grace-период на открытую форму. */
private val GARBAGE_GRACE_PERIOD_MS = TimeUnit.HOURS.toMillis(24)

/**
 * Единственная точка входа к фото записи: импорт из камеры/галереи, доступ к сжатым файлам,
 * удаление, подчистка рассинхрона файлов/БД. Скрывает EXIF, сжатие, схему файлов на диске.
 */
class PhotoStore(context: Context, private val photoDao: PhotoDao) {
    private val appContext = context.applicationContext
    private val importer = PhotoImporter(appContext)
    private val mediumDir = File(appContext.filesDir, "photos/medium").apply { mkdirs() }
    private val thumbDir = File(appContext.filesDir, "photos/thumb").apply { mkdirs() }

    fun mediumFile(id: String): File = File(mediumDir, "$id.jpg")

    fun thumbFile(id: String): File = File(thumbDir, "$id.jpg")

    /** Метаданные фото по id — размеры нужны xlsx-экспорту (M6) для EMU без декодирования JPEG. */
    suspend fun getMeta(id: String): PhotoMeta? = withContext(Dispatchers.IO) {
        photoDao.getById(id)?.let { PhotoMeta(it.id, it.width, it.height, it.sizeBytes) }
    }

    /** Все строки метаданных как есть — источник данных для полного бэкапа (M7), больше нигде не используется. */
    suspend fun getAllMeta(): List<PhotoMeta> = withContext(Dispatchers.IO) {
        photoDao.getAll().map { PhotoMeta(it.id, it.width, it.height, it.sizeBytes, it.originUri, it.createdAt) }
    }

    /** Есть ли уже строка метаданных с этим id — восстановление бэкапа (M7) решает по этому флагу, писать файлы/строку или пропустить (фото иммутабельны). */
    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) { photoDao.getById(id) != null }

    /**
     * Вставляет строку метаданных из бэкапа, если её ещё нет — путь восстановления (M7). Байты файлов
     * уже скопированы в [mediumFile]/[thumbFile] отдельно (см. [ImportInteractor][ru.papasheets.domain.backup.ImportInteractor]);
     * здесь только регистрация в БД. Существующую строку не трогает — фото иммутабельны.
     */
    suspend fun insertMetaIfAbsent(meta: PhotoMeta): Boolean = withContext(Dispatchers.IO) {
        if (photoDao.getById(meta.id) != null) return@withContext false
        photoDao.insert(PhotoEntity(meta.id, meta.width, meta.height, meta.sizeBytes, meta.originUri, meta.createdAt))
        true
    }

    /** Декодирует+сжимает [source] и заводит строку в БД. Файлы пишутся первыми: при сбое — подчищаются. */
    suspend fun import(source: PhotoSource): PhotoMeta = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        try {
            val meta = importer.importTo(id, source, mediumFile(id), thumbFile(id))
            photoDao.insert(
                PhotoEntity(
                    id = id,
                    width = meta.width,
                    height = meta.height,
                    sizeBytes = meta.sizeBytes,
                    originUri = (source as? PhotoSource.Gallery)?.uri?.toString(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            Log.d(TAG, "import: ok id=$id ${meta.width}x${meta.height} ${meta.sizeBytes}B from $source")
            meta
        } catch (e: Exception) {
            Log.e(TAG, "import: failed for $source, cleaning up id=$id", e)
            mediumFile(id).delete()
            thumbFile(id).delete()
            throw e
        }
    }

    /** Порядок специально БД → файлы: если процесс умрёт между шагами, останется файл-сирота, а не битая ссылка в БД. */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        photoDao.delete(id)
        mediumFile(id).delete()
        thumbFile(id).delete()
    }

    /**
     * Чинит рассинхрон файлов и БД, оставшийся от прерванных [import]/[delete] (например, смерть
     * процесса между записью файла и вставкой строки). Не трогает ничего младше 24ч — форма
     * записи может ещё держать только что импортированное фото несохранённым.
     */
    suspend fun collectGarbage(): Unit = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - GARBAGE_GRACE_PERIOD_MS
        val knownIds = photoDao.listAllIds().toSet()

        (mediumDir.listFiles().orEmpty().asSequence() + thumbDir.listFiles().orEmpty().asSequence())
            .filter { it.nameWithoutExtension !in knownIds && it.lastModified() < cutoff }
            .forEach { it.delete() }

        for (id in knownIds) {
            val photo = photoDao.getById(id) ?: continue
            if (photo.createdAt >= cutoff) continue
            if (!mediumFile(id).exists() || !thumbFile(id).exists()) {
                photoDao.delete(id)
            }
        }
    }
}
