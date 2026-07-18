package ru.papasheets.domain.xlsx

import java.io.File
import java.time.LocalDate
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.exportkit.xlsx.read.PhotoRef

/** Импорт xlsx сорвался по причине, которую нужно показать пользователю дословно. */
class XlsxImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Что импорт сделает, если его подтвердить. Показывается пользователю до единой записи в БД:
 * файл выбран в системном диалоге и может оказаться чем угодно — чужой таблицей, файлом за другой
 * месяц, журналом с 28 незнакомыми подрядчиками. Молча слить такое в базу нельзя, а откатить импорт
 * после — уже нечем.
 *
 * Цифры здесь не «прикидка», а следствие готового [plan]: показывается ровно то, что запишется.
 * Считать их отдельно от плана значило бы завести второй источник правды, который однажды разойдётся
 * с первым.
 */
class XlsxImportPreview internal constructor(
    val journalTitle: String,
    /** true, если журнал за этот месяц уже есть и записи добавятся в него. */
    val journalExists: Boolean,
    val dayCount: Int,
    val recordCount: Int,
    val photoCount: Int,
    /** Подрядчики, которых на устройстве нет и которые будут созданы. */
    val newContractors: List<String>,
    /** Подрядчики из файла, найденные среди существующих по имени. */
    val matchedContractorCount: Int,
    /** Подписи колонок, под которые будут заведены новые поля записи. */
    val newFields: List<String>,
    /** Колонки файла, легшие на уже существующие поля (включая встроенные «Л» и «ВИД РАБОТ»). */
    val matchedFieldCount: Int,
    /**
     * Колонки и группы, которые импорт пропустит: без подписи их не к чему привязать. Так выглядит
     * файл с вырезанной таблицей общих строк — структура на месте, а текста нет.
     */
    val skippedUnnamedContractors: Int,
    val skippedUnnamedFields: Int,
    internal val plan: XlsxImportPlan,
)

/** Сколько чего действительно записано — итог подтверждённого импорта. */
class XlsxImportResult(
    val journalTitle: String,
    val createdContractors: Int,
    val createdFields: Int,
    val importedRecords: Int,
    val importedPhotos: Int,
)

/**
 * Решённый план импорта: все сопоставления с содержимым БД уже сделаны, осталось записать.
 *
 * План строится один раз — на нём же считается предпросмотр, им же выполняется запись. Благодаря
 * этому подтверждённый импорт не может разойтись с показанными цифрами.
 */
internal class XlsxImportPlan(
    val year: Int,
    val month: Int,
    val newContractors: List<ContractorEntity>,
    val newFields: List<FieldDefEntity>,
    val records: List<PlannedRecord>,
    /** Временный файл-копия выбранного xlsx: из него достаются байты фото на этапе записи. */
    val sourceFile: File,
    /** Фото по ссылке из архива — отдаёт байты, пока [sourceFile] на месте. */
    val photoBytes: (PhotoRef) -> ByteArray?,
)

/**
 * Одна будущая запись журнала. Подрядчик и поля адресуются готовыми id: новые сущности получают
 * их ещё на этапе планирования, поэтому запись не зависит от того, создавались они сейчас или
 * лежали в базе с прошлого раза.
 */
internal class PlannedRecord(
    val date: LocalDate,
    val contractorId: String,
    /** id поля → значение; пустые значения сюда не попадают. */
    val values: Map<String, String>,
    val photo: PhotoRef?,
)
