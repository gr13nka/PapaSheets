package ru.papasheets.exportkit.backup

/**
 * Приводит разобранный бэкап любой поддерживаемой версии к форме [BackupManifest.CURRENT_FORMAT_VERSION].
 *
 * Единственное место, которое вообще знает, чем старые версии формата отличались от текущей. Всё, что
 * лежит выше — импорт, слияние, мапперы, — видит только текущую форму и о существовании v1 не знает;
 * поэтому объект `internal` и вызывается из [BackupReader], а не из app.
 *
 * Функция чистая: ни Android, ни ввода-вывода, ни времени — вход целиком определяет выход.
 */
internal object BackupUpgrade {
    fun toCurrent(formatVersion: Int, data: BackupData): BackupData =
        if (formatVersion >= BackupManifest.CURRENT_FORMAT_VERSION) data else v1ToV2(data)

    /**
     * v1 → v2: содержимое записи переезжает из колонок в значения полей.
     *
     * Встроенные определения заводятся из [BuiltInFields] **с их константными id** — ровно теми же,
     * что и на устройстве (миграция БД и сид берут их оттуда же). Благодаря этому «Локация» из
     * бэкапа и «Локация» на телефоне при слиянии оказываются одной строкой, а не двумя колонками
     * с одинаковым названием.
     *
     * Пустые и пробельные значения строк не порождают, значения обрезаются — тот же инвариант, что
     * в `Migrations.MIGRATION_1_2_COPY` и в `RecordRepository`: «пусто» существует в одном виде —
     * как отсутствие строки.
     */
    private fun v1ToV2(data: BackupData): BackupData = data.copy(
        records = data.records.map { it.copy(locationCode = null, workText = null) },
        fieldDefs = BuiltInFields.ALL.map(::toBackupFieldDef),
        recordValues = data.records.flatMap { record ->
            listOfNotNull(
                valueOrNull(record.id, BuiltInFields.LOCATION_ID, record.locationCode),
                valueOrNull(record.id, BuiltInFields.WORK_ID, record.workText),
            )
        },
    )

    private fun valueOrNull(recordId: String, fieldId: String, raw: String?): BackupRecordValue? {
        val value = raw?.trim().orEmpty()
        return if (value.isEmpty()) null else BackupRecordValue(recordId, fieldId, value)
    }

    /**
     * `createdAt = 0`: в v1 у полей не было ни строк в БД, ни отметки времени, и взять её неоткуда.
     * Ни на что не влияет — порядок колонок задаёт `orderIndex`, а узнаётся встроенное поле по id.
     */
    private fun toBackupFieldDef(spec: BuiltInFields.Spec) = BackupFieldDef(
        id = spec.id,
        key = spec.key,
        title = spec.title,
        label = spec.label,
        orderIndex = spec.orderIndex,
        isArchived = false,
        isBuiltIn = true,
        isRequired = spec.isRequired,
        suggestFromHistory = spec.suggestFromHistory,
        columnWidthDp = spec.columnWidthDp,
        maxLines = spec.maxLines,
        showAtCompactLod = spec.showAtCompactLod,
        createdAt = 0L,
    )
}
