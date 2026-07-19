package ru.papasheets.domain.xlsx

import java.io.File
import ru.papasheets.exportkit.xlsx.read.PhotoRef

/** Импорт xlsx сорвался по причине, которую нужно показать пользователю дословно. */
class XlsxImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Что импорт сделает, если его подтвердить. Показывается пользователю до единой записи в БД:
 * файл выбран в системном диалоге и может оказаться чем угодно — чужой таблицей, файлом за другой
 * месяц, журналом с 28 незнакомыми подрядчиками. Молча слить такое в базу нельзя, а откатить импорт
 * после — уже нечем.
 *
 * **Ни одной своей цифры здесь нет — все выводятся из [plan].** Именно этим планом [XlsxImportInteractor.apply]
 * потом и пишет, так что показать одно, а записать другое попросту нечем: значения не передаются в
 * конструктор, а вычисляются, и второму источнику правды взяться неоткуда. Раньше они приходили
 * параметрами, и совпадение держалось на внимательности вызывающего.
 *
 * Кроме плана несёт временную копию файла: она пережидает здесь показ диалога, потому что байты
 * фото достаются из неё уже на этапе записи.
 */
class XlsxImportPreview internal constructor(
    val journalTitle: String,
    internal val plan: XlsxImportPlan,
    /** Временный файл-копия выбранного xlsx; удаляется после записи или отказа. */
    internal val sourceFile: File,
    /** Фото по ссылке из архива — отдаёт байты, пока [sourceFile] на месте. */
    internal val photoBytes: (PhotoRef) -> ByteArray?,
) {
    /** true, если журнал за этот месяц уже есть и записи добавятся в него. */
    val journalExists: Boolean get() = plan.journalExists

    val dayCount: Int get() = plan.dayCount
    val recordCount: Int get() = plan.records.size
    val photoCount: Int get() = plan.photoCount

    /** Подрядчики, которых на устройстве нет и которые будут созданы. */
    val newContractors: List<String> get() = plan.newContractors.map { it.name }

    /** Подрядчики из файла, найденные среди существующих по имени. */
    val matchedContractorCount: Int get() = plan.matchedContractorCount

    /** Подписи колонок, под которые будут заведены новые поля записи. */
    val newFields: List<String> get() = plan.newFields.map { it.title }

    /** Колонки файла, легшие на уже существующие поля (включая встроенные «Л» и «ВИД РАБОТ»). */
    val matchedFieldCount: Int get() = plan.matchedFieldCount

    /**
     * Колонки и группы, которые импорт пропустит: без подписи их не к чему привязать. Так выглядит
     * файл с вырезанной таблицей общих строк — структура на месте, а текста нет.
     */
    val skippedUnnamedContractors: Int get() = plan.skippedUnnamedContractors
    val skippedUnnamedFields: Int get() = plan.skippedUnnamedFields
}

/** Сколько чего действительно записано — итог подтверждённого импорта. */
class XlsxImportResult(
    val journalTitle: String,
    val createdContractors: Int,
    val createdFields: Int,
    val importedRecords: Int,
    val importedPhotos: Int,
)


