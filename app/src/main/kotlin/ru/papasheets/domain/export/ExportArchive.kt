package ru.papasheets.domain.export

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Правила ротации предыдущих версий экспорта — без Android, чтобы проверяться JVM-тестами.
 *
 * Экспорт всегда пишет один и тот же файл («Июль 2026.xlsx»), а прежнюю версию перед перезаписью
 * уносит в подпапку [DIR_NAME] под именем со временем снятия. Хранится последние [KEEP] таких копий:
 * это страховка от плохого экспорта (запустили, не заметив, что данных не хватало), а не полный
 * архив — иначе папка копила бы файлы без предела.
 *
 * Штамп времени в имени сделан так, чтобы лексикографический порядок имён совпадал с хронологическим
 * (`yyyy-MM-dd HH-mm-ss`): тогда «какие копии лишние» — это обычная сортировка строк, без разбора дат.
 * Секунды в штампе, а не только минуты: два экспорта подряд не должны налететь на одно имя, иначе
 * второй перезаписал бы копию первого и глубина архива молча просела бы.
 */
object ExportArchive {
    /** Имя подпапки с прежними версиями, рядом с самим файлом. */
    const val DIR_NAME = "Архив"

    /** Сколько последних копий хранить. */
    const val KEEP = 3

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

    /** Имя архивной копии: «Июль 2026 (2026-07-20 14-30-05).xlsx». */
    fun archiveName(fileName: String, at: LocalDateTime): String {
        val (base, ext) = splitExtension(fileName)
        return "$base (${STAMP.format(at)})$ext"
    }

    /**
     * Какие имена из [existing] относятся к [fileName] и лишние — их удаляют после добавления новой
     * копии, оставляя [keep] новейших. Чужие месяцы в той же папке ([existing] содержит архивы всех
     * журналов) не трогаются: ротация у каждого файла своя.
     */
    fun obsolete(fileName: String, existing: List<String>, keep: Int = KEEP): List<String> {
        val (base, ext) = splitExtension(fileName)
        val prefix = "$base ("
        val mine = existing.filter { it.startsWith(prefix) && it.endsWith(ext) }.sorted()
        return if (mine.size <= keep) emptyList() else mine.dropLast(keep)
    }

    /** «Июль 2026.xlsx» → («Июль 2026», «.xlsx»); без точки — расширение пустое. */
    private fun splitExtension(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName to "" else fileName.substring(0, dot) to fileName.substring(dot)
    }
}
