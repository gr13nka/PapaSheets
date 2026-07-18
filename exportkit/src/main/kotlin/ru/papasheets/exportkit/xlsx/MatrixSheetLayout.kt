package ru.papasheets.exportkit.xlsx

/**
 * Раскладка листа «матрица дата × подрядчик» — единственное место, где записано, что где лежит.
 *
 * Знание раскладки нужно обеим сторонам: [SheetXml] по нему пишет лист, а
 * [ru.papasheets.exportkit.xlsx.read.XlsxReader] по нему же читает чужой. Пока оно жило только
 * в писателе, читателю пришлось бы повторить те же формулы у себя — и первая же правка шага
 * колонок развела бы форматы молча, без падения тестов. Здесь формулы одни, и прямые
 * ([photoColumn], [fieldColumn]) с обратной ([roleOf]) проверяются друг об друга.
 *
 * Нумерация: колонки и строки — 1-based, как в ссылках Excel (`A1`). DrawingML, в отличие от
 * листа, адресует ячейки 0-based — переводы собраны в [anchorColumn] и [anchorRow], чтобы «минус
 * один» не расползался по коду.
 */
object MatrixSheetLayout {
    /** Строка 1: имена подрядчиков, каждое — merge на всю группу. */
    const val CONTRACTOR_HEADER_ROW = 1

    /** Строка 2: «Ф» и подписи полей внутри каждой группы. */
    const val FIELD_HEADER_ROW = 2

    /** Первая строка данных; строка листа = одна запись журнала. */
    const val FIRST_DATA_ROW = 3

    /** Колонка A — дата записи. */
    const val DATE_COLUMN = 1

    /** Подпись фото-колонки в строке 2; по ней читатель узнаёт начало группы, если merge-шапки нет. */
    const val PHOTO_LABEL = "Ф"

    /** Ширина группы одного подрядчика: фото-колонка Ф плюс по колонке на каждое поле. */
    fun groupWidth(fieldCount: Int): Int = 1 + fieldCount

    /** Число полей, обратное к [groupWidth]. */
    fun fieldCount(groupWidth: Int): Int = groupWidth - 1

    /** Колонка Ф подрядчика: она же якорь фото, собственного текста не несёт. */
    fun photoColumn(contractorIndex: Int, groupWidth: Int): Int =
        DATE_COLUMN + 1 + groupWidth * contractorIndex

    /** Колонка значения поля внутри группы подрядчика. */
    fun fieldColumn(contractorIndex: Int, fieldIndex: Int, groupWidth: Int): Int =
        photoColumn(contractorIndex, groupWidth) + 1 + fieldIndex

    /** Всего колонок листа: дата плюс группы всех подрядчиков. */
    fun totalColumns(contractorCount: Int, groupWidth: Int): Int = DATE_COLUMN + groupWidth * contractorCount

    /** Число подрядчиков, обратное к [totalColumns]; неполный «хвост» колонок отбрасывается. */
    fun contractorCount(totalColumns: Int, groupWidth: Int): Int = (totalColumns - DATE_COLUMN) / groupWidth

    /** Колонка для якоря DrawingML (0-based). */
    fun anchorColumn(contractorIndex: Int, groupWidth: Int): Int = photoColumn(contractorIndex, groupWidth) - 1

    /** Строка для якоря DrawingML (0-based) по порядковому номеру строки данных. */
    fun anchorRow(dataRowIndex: Int): Int = FIRST_DATA_ROW + dataRowIndex - 1

    /** Порядковый номер строки данных по 0-based строке якоря — обратное к [anchorRow]. */
    fun dataRowIndexOfAnchorRow(anchorRow: Int): Int = anchorRow - (FIRST_DATA_ROW - 1)

    /**
     * Чему соответствует колонка листа. Обратная сторона [photoColumn]/[fieldColumn]: читатель
     * встречает ячейки в произвольном порядке и разреженно, поэтому раскладывает их по смыслу
     * колонки, а не по счётчику.
     */
    fun roleOf(column: Int, groupWidth: Int, contractorCount: Int): ColumnRole {
        if (column == DATE_COLUMN) return ColumnRole.Date
        val offset = column - DATE_COLUMN - 1
        if (offset < 0) return ColumnRole.Outside
        val contractorIndex = offset / groupWidth
        if (contractorIndex >= contractorCount) return ColumnRole.Outside
        val withinGroup = offset % groupWidth
        return if (withinGroup == 0) {
            ColumnRole.Photo(contractorIndex)
        } else {
            ColumnRole.Field(contractorIndex, withinGroup - 1)
        }
    }

    /** 1-based номер колонки → буквенная ссылка Excel (1→A, 27→AA, …). */
    fun columnLetter(oneIndexed: Int): String {
        var n = oneIndexed
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** Ссылка вида `A1` → 1-based колонка и строка; `null`, если ссылка не разобралась. */
    fun parseCellRef(ref: String): CellRef? {
        var i = 0
        var column = 0
        while (i < ref.length && ref[i] in 'A'..'Z') {
            column = column * 26 + (ref[i] - 'A' + 1)
            i++
        }
        if (column == 0 || i == ref.length) return null
        var row = 0
        while (i < ref.length) {
            val c = ref[i]
            if (c !in '0'..'9') return null
            row = row * 10 + (c - '0')
            i++
        }
        return if (row == 0) null else CellRef(column, row)
    }

    /** Ссылка `A1` (1-based, как в листе). */
    data class CellRef(val column: Int, val row: Int)

    sealed interface ColumnRole {
        /** Колонка A. */
        object Date : ColumnRole

        /** Колонка Ф подрядчика — место якоря фото. */
        data class Photo(val contractorIndex: Int) : ColumnRole

        /** Колонка значения поля [fieldIndex] у подрядчика [contractorIndex]. */
        data class Field(val contractorIndex: Int, val fieldIndex: Int) : ColumnRole

        /** Колонка за пределами матрицы — в чужих файлах правее данных бывает что угодно. */
        object Outside : ColumnRole
    }
}
