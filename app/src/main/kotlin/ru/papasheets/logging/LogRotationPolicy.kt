package ru.papasheets.logging

/**
 * Правила ротации файла лога — без Android, чтобы проверяться JVM-тестами.
 *
 * Лог пишется в один растущий файл; по достижении [MAX_FILE_BYTES] он сдвигается в пронумерованные
 * копии (`app.log.1`, `app.log.2`, …), а самая старая, вышедшая за [KEEP_ROTATED], теряется —
 * телефон офлайн, лог живёт только на устройстве, и без потолка он рос бы неограниченно.
 */
object LogRotationPolicy {
    /** Порог размера текущего файла, после которого следующая запись начинается с ротации. */
    const val MAX_FILE_BYTES: Long = 256L * 1024

    /** Сколько пронумерованных копий хранить в дополнение к текущему файлу. */
    const val KEEP_ROTATED: Int = 2

    /** Пора ли ротировать: текущий размер достиг предела или уже превысил его. */
    fun shouldRotate(currentSizeBytes: Long, maxBytes: Long = MAX_FILE_BYTES): Boolean =
        currentSizeBytes >= maxBytes

    /** Имя копии с номером [index]: `0` и меньше — сам [baseName], иначе `"$baseName.$index"`. */
    fun rotatedName(baseName: String, index: Int): String =
        if (index <= 0) baseName else "$baseName.$index"

    /**
     * Переименования, которые нужно выполнить по порядку, чтобы освободить место под новый
     * [baseName]. Порядок — от старших номеров к младшим, а текущий файл сдвигается в `.1`
     * последним: так каждое переименование затирает файл, который уже сдвинут дальше (или не
     * существует), и ни один шаг не теряет копию раньше, чем её содержимое скопировано на следующее
     * место. Самая старая копия (номер [keep]) в план не входит — она просто перезаписывается
     * последним переименованием сдвига и тем самым выбывает.
     */
    fun rotationPlan(baseName: String, keep: Int = KEEP_ROTATED): List<Pair<String, String>> {
        val shifts = (keep - 1) downTo 1
        val plan = shifts.map { index -> rotatedName(baseName, index) to rotatedName(baseName, index + 1) }
        return plan + (baseName to rotatedName(baseName, 1))
    }
}
