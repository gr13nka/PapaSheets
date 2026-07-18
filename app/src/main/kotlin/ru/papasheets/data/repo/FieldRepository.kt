package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.entity.FieldDefEntity

/**
 * Определения полей записи. Активные поля — это одновременно подколонки матрицы, колонки экспорта и
 * строки формы записи: набор и его порядок у всех трёх один, поэтому и источник должен быть один,
 * иначе экспорт разъедется с матрицей по составу колонок.
 */
class FieldRepository(private val dao: FieldDefDao) {
    /**
     * Поля, которыми пользуются сейчас, по `orderIndex`. Архивное поле сохраняет значения в старых
     * записях, но больше не показывается и не предлагается к заполнению.
     */
    fun observeActive(): Flow<List<FieldDefEntity>> = dao.observeAll().map { fields -> fields.filter { !it.isArchived } }

    /** Все определения, включая архивные — их нужно видеть экрану полей и бэкапу. */
    suspend fun getAll(): List<FieldDefEntity> = dao.getAll()
}
