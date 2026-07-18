package ru.papasheets.exportkit.backup

/**
 * Встроенные определения полей записи — «Локация» и «Вид работ».
 *
 * Их идентификаторы **захардкожены константами, а не сгенерированы**, и это принципиально.
 * Поля заводятся двумя независимыми путями: устройство, обновившееся с v1, получает их из
 * миграции, чистая установка — из сида при создании БД. Со случайными UUID эти пути дали бы
 * разные id для одного и того же поля, и бэкап, снятый на одном устройстве и восстановленный
 * на другом, положил бы рядом две колонки «Локация» — слить их обратно было бы уже нечем.
 * Константа делает встроенное поле узнаваемым на любом устройстве и в любом бэкапе.
 *
 * Живут константы в `:exportkit`, а не в `:app`, потому что нужны обеим сторонам: приложению —
 * для схемы БД, формату бэкапа — для апгрейда v1→v2, который распознаёт старые записи по этим
 * же id. Зависимость `app → exportkit` уже есть, обратной быть не может.
 *
 * `:exportkit` — чистый JVM-модуль: здесь только данные, никакого Room и Android.
 */
object BuiltInFields {
    const val LOCATION_ID = "00000000-0000-4000-8000-000000000001"
    const val WORK_ID = "00000000-0000-4000-8000-000000000002"
    const val LOCATION_KEY = "location"
    const val WORK_KEY = "work"

    /**
     * Описание встроенного поля без привязки к Room и Android — общий источник для миграции и сида.
     *
     * @param key стабильный машинный ключ; у встроенного поля не меняется никогда
     * @param title подпись подколонки в матрице: «Л», «ВИД РАБОТ»
     * @param label полное имя в форме записи: «Локация», «Вид работ»
     * @param maxLines потолок строк текста в ячейке; 0 = без ограничения
     * @param showAtCompactLod показывать ли колонку в сжатом LOD («картина месяца»)
     */
    data class Spec(
        val id: String,
        val key: String,
        val title: String,
        val label: String,
        val orderIndex: Int,
        val isRequired: Boolean,
        val suggestFromHistory: Boolean,
        val columnWidthDp: Int,
        val maxLines: Int,
        val showAtCompactLod: Boolean,
    )

    /** Значения зеркалят поведение до появления гибких полей — колонки матрицы выглядят ровно так же. */
    val ALL: List<Spec> = listOf(
        Spec(
            LOCATION_ID, LOCATION_KEY, "Л", "Локация", 0,
            isRequired = false, suggestFromHistory = true,
            columnWidthDp = 56, maxLines = 2, showAtCompactLod = true,
        ),
        Spec(
            WORK_ID, WORK_KEY, "ВИД РАБОТ", "Вид работ", 1,
            isRequired = true, suggestFromHistory = true,
            columnWidthDp = 168, maxLines = 0, showAtCompactLod = false,
        ),
    )
}
