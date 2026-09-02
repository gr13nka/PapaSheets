package ru.papasheets.domain

import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.photos.PhotoStore

/**
 * Каскадное удаление журнала (M8): журнал + все его записи + их фото. FK CASCADE уносит записи вместе с
 * журналом, но фото (файлы и строки БД) каскад не трогает — иначе они утекут навсегда (collectGarbage их
 * не подберёт: строка и файлы на месте, просто больше никем не нужны).
 *
 * Порядок обязателен: photoId собираем ДО удаления журнала (после каскада записей уже не будет), затем
 * сносим журнал (каскад уносит записи), затем — фото. Фото у записи уникально (unique index на photoId),
 * поэтому удалить можно все без проверки на общее использование другими записями.
 *
 * Каскад не знает и о [LastPlace]: запомненное место живёт в настройках, а не в БД, и без явной чистки
 * запуск приложения продолжал бы вести в удалённый журнал.
 */
class DeleteJournalInteractor(
    private val journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    private val photoStore: PhotoStore,
    private val lastPlace: LastPlace,
) {
    suspend fun delete(journalId: String) {
        val photoIds = recordRepository.photoIdsForJournal(journalId)
        journalRepository.delete(journalId)
        photoIds.forEach { photoStore.delete(it) }
        lastPlace.forget(journalId)
    }
}
