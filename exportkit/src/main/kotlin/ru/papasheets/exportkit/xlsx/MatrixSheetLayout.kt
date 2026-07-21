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
 * **Значение, а не набор функций.** Раскладка описывается двумя числами — сколько в группе
 * фото-колонок и сколько колонок полей, — и с появлением второй колонки Ф первое перестало быть
 * константой формата: свои файлы мы пишем с [PHOTO_COLUMNS_PER_GROUP], а читать обязаны и те, где
 * колонка Ф одна (наш прошлый экспорт и журнал заказчика, docs/reference/iyun-xlsx). Держи эти
 * числа при вызовах — и каждый call site отвечал бы за передачу согласованной пары; читатель,
 * забывший параметр, молча считал бы старый файл по новой раскладке и разъехался на целую колонку.
 * Поэтому шаг фиксируется один раз при создании ([forWriting] / [ofGroupWidth]), а дальше объект
 * сам знает свою геометрию.
 *
 * Нумерация: колонки и строки — 1-based, как в ссылках Excel (`A1`). DrawingML, в отличие от
 * листа, адресует ячейки 0-based — переводы собраны в [anchorColumn] и [anchorRow], чтобы «минус
 * один» не расползался по коду.
 */
class MatrixSheetLayout private constructor(
    /** Сколько колонок Ф в группе подрядчика: 2 в нашем формате, 1 в старых и чужих файлах. */
    val photoColumns: Int,
    /** Сколько колонок полей идёт после колонок Ф. */
    val fieldCount: Int,
) {
    /** Ширина группы одного подрядчика: колонки Ф плюс по колонке на каждое поле. */
    val groupWidth: Int = photoColumns + fieldCount

    /**
     * Колонка Ф подрядчика: она же якорь фото, собственного текста не несёт. [slot] — номер фото
     * в записи (0-based), слоты идут подряд в начале группы.
     */
    fun photoColumn(contractorIndex: Int, slot: Int): Int =
        DATE_COLUMN + 1 + groupWidth * contractorIndex + slot

    /** Колонка значения поля внутри группы подрядчика — сразу за всеми колонками Ф. */
    fun fieldColumn(contractorIndex: Int, fieldIndex: Int): Int =
        DATE_COLUMN + 1 + groupWidth * contractorIndex + photoColumns + fieldIndex

    /** Всего колонок листа: дата плюс группы всех подрядчиков. */
    fun totalColumns(contractorCount: Int): Int = DATE_COLUMN + groupWidth * contractorCount

    /** Число подрядчиков, обратное к [totalColumns]; неполный «хвост» колонок отбрасывается. */
    fun contractorCount(totalColumns: Int): Int = (totalColumns - DATE_COLUMN) / groupWidth

    /** Колонка для якоря DrawingML (0-based). */
    fun anchorColumn(contractorIndex: Int, slot: Int): Int = photoColumn(contractorIndex, slot) - 1

    /**
     * Чему соответствует колонка листа. Обратная сторона [photoColumn]/[fieldColumn]: читатель
     * встречает ячейки в произвольном порядке и разреженно, поэтому раскладывает их по смыслу
     * колонки, а не по счётчику.
     */
    fun roleOf(column: Int, contractorCount: Int): ColumnRole {
        if (column == DATE_COLUMN) return ColumnRole.Date
        val offset = column - DATE_COLUMN - 1
        if (offset < 0) return ColumnRole.Outside
        val contractorIndex = offset / groupWidth
        if (contractorIndex >= contractorCount) return ColumnRole.Outside
        val withinGroup = offset % groupWidth
        return if (withinGroup < photoColumns) {
            ColumnRole.Photo(contractorIndex, withinGroup)
        } else {
            ColumnRole.Field(contractorIndex, withinGroup - photoColumns)
        }
    }

    sealed interface ColumnRole {
        /** Колонка A. */
        object Date : ColumnRole

        /** Колонка Ф подрядчика — место якоря фото [slot]. */
        data class Photo(val contractorIndex: Int, val slot: Int) : ColumnRole

        /** Колонка значения поля [fieldIndex] у подрядчика [contractorIndex]. */
        data class Field(val contractorIndex: Int, val fieldIndex: Int) : ColumnRole

        /** Колонка за пределами матрицы — в чужих файлах правее данных бывает что угодно. */
        object Outside : ColumnRole
    }

    companion object {
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

        /**
         * Сколько фото помещается в запись — и, ровно поэтому, сколько колонок Ф в группе.
         *
         * Потолок формата листа, а не предпочтение: `oneCellAnchor` привязывает картинку к одной
         * ячейке и задаёт ей явный размер, так что второе фото требует именно второй колонки.
         * Совпадает с числом слотов в схеме БД (`RecordEntity.photoId`/`photoId2`) — разойтись им
         * нельзя, иначе экспорт потерял бы фото, которое запись хранит.
         */
        const val PHOTO_COLUMNS_PER_GROUP = 2

        /** Раскладка нашего собственного экспорта. */
        fun forWriting(fieldCount: Int): MatrixSheetLayout =
            MatrixSheetLayout(PHOTO_COLUMNS_PER_GROUP, fieldCount)

        /**
         * Раскладка прочитанного листа: и ширина группы, и число колонок Ф вычитаны из шапки.
         * Ширина группы, оказавшаяся меньше числа колонок Ф, означает мусор в шапке — полей в такой
         * группе просто нет, а отрицательный [fieldCount] развалил бы обход колонок.
         */
        fun ofGroupWidth(groupWidth: Int, photoColumns: Int): MatrixSheetLayout =
            MatrixSheetLayout(photoColumns, (groupWidth - photoColumns).coerceAtLeast(0))

        /** Строка для якоря DrawingML (0-based) по порядковому номеру строки данных. */
        fun anchorRow(dataRowIndex: Int): Int = FIRST_DATA_ROW + dataRowIndex - 1

        /** Порядковый номер строки данных по 0-based строке якоря — обратное к [anchorRow]. */
        fun dataRowIndexOfAnchorRow(anchorRow: Int): Int = anchorRow - (FIRST_DATA_ROW - 1)

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
    }

    /** Ссылка `A1` (1-based, как в листе). */
    data class CellRef(val column: Int, val row: Int)
}
