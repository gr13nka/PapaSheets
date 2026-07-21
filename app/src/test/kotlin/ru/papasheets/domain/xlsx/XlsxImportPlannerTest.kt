package ru.papasheets.domain.xlsx

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.exportkit.backup.BuiltInFields
import ru.papasheets.exportkit.xlsx.read.ParsedCell
import ru.papasheets.exportkit.xlsx.read.ParsedDay
import ru.papasheets.exportkit.xlsx.read.ParsedRow
import ru.papasheets.exportkit.xlsx.read.ParsedSheet
import ru.papasheets.exportkit.xlsx.read.PhotoRef
import ru.papasheets.testing.builtInFields
import ru.papasheets.testing.testField

/**
 * Сопоставление чужой таблицы с данными устройства.
 *
 * Единственное место импорта, где ошибка не выдаёт себя ничем: файл чужой, записей в нём сотни, и
 * проверить глазами, что «Оконщики» легли на «Оконщиков», а колонка «Л» — на встроенную локацию, а
 * не на новую колонку с тем же названием, невозможно. Отсюда и тесты: id из файла взяться неоткуда,
 * значит всё держится на совпадении имён и подписей, и каждое правило этого совпадения закреплено
 * здесь отдельно.
 */
class XlsxImportPlannerTest {

    // --- фикстуры -------------------------------------------------------------------------------

    /**
     * Файл из двух колонок с теми же подписями, что у встроенных полей: так выглядит наш собственный
     * экспорт и так же — таблица заказчика, с которой всё началось.
     */
    private fun sheet(
        contractors: List<String>,
        fieldTitles: List<String> = listOf("Л", "ВИД РАБОТ"),
        days: List<ParsedDay>,
    ) = ParsedSheet(
        sheetName = "Лист2",
        contractors = contractors,
        fieldTitles = fieldTitles,
        days = days,
        // Планировщик к файлу не обращается — байты фото достаются уже на записи.
        source = File("файл-не-читается.xlsx"),
    )

    private fun day(date: LocalDate?, vararg rows: ParsedRow) =
        ParsedDay(date = date, dateLabel = date?.toString().orEmpty(), rows = rows.toList())

    private fun row(vararg cells: ParsedCell?) = ParsedRow(sheetRow = 3, cells = cells.toList())

    private fun cell(vararg values: String, photo: PhotoRef? = null, photos: List<PhotoRef> = listOfNotNull(photo)) =
        ParsedCell(values = values.toList(), photos = photos)

    private fun contractor(name: String, colorIndex: Int = 0, orderIndex: Int = 0) = ContractorEntity(
        id = "id-$name",
        name = name,
        shortName = name.take(3),
        colorIndex = colorIndex,
        orderIndex = orderIndex,
        createdAt = 0,
    )

    /** Один день, один подрядчик, одна заполненная запись — минимум, на котором план вообще строится. */
    private fun singleRecordSheet(
        contractorName: String,
        fieldTitles: List<String> = listOf("Л", "ВИД РАБОТ"),
        values: List<String> = listOf("К1", "Штукатурка"),
    ) = sheet(
        contractors = listOf(contractorName),
        fieldTitles = fieldTitles,
        days = listOf(day(DATE, row(cell(*values.toTypedArray())))),
    )

    private fun plan(
        sheet: ParsedSheet,
        contractors: List<ContractorEntity> = emptyList(),
        fields: List<FieldDefEntity> = builtInFields,
        journals: List<JournalEntity> = emptyList(),
    ) = XlsxImportPlanner.plan(sheet, contractors, fields, journals, NOW)

    // --- подрядчики -----------------------------------------------------------------------------

    @Test
    fun `a contractor is matched to the existing one by name`() {
        val result = plan(singleRecordSheet("Оконщики"), contractors = listOf(contractor("Оконщики")))

        assertEquals(1, result.matchedContractorCount)
        assertTrue("совпавший по имени подрядчик не должен заводиться заново", result.newContractors.isEmpty())
        assertEquals("id-Оконщики", result.records.single().contractorId)
    }

    /** В чужой таблице регистр произвольный: «ОКОНЩИКИ» в шапке и «Оконщики» в базе — один подрядчик. */
    @Test
    fun `contractor matching ignores case`() {
        val result = plan(singleRecordSheet("ОКОНЩИКИ"), contractors = listOf(contractor("Оконщики")))

        assertEquals(1, result.matchedContractorCount)
        assertEquals("id-Оконщики", result.records.single().contractorId)
    }

    /** Пробелы по краям в шапке — обычное дело для таблицы, которую вели руками. */
    @Test
    fun `contractor matching ignores surrounding whitespace`() {
        val result = plan(singleRecordSheet("  Оконщики  "), contractors = listOf(contractor("Оконщики")))

        assertEquals(1, result.matchedContractorCount)
        assertEquals("id-Оконщики", result.records.single().contractorId)
    }

    @Test
    fun `an unknown contractor is created and the record points at it`() {
        val result = plan(singleRecordSheet("Кровельщики"), contractors = listOf(contractor("Оконщики")))

        assertEquals(0, result.matchedContractorCount)
        val created = result.newContractors.single()
        assertEquals("Кровельщики", created.name)
        assertEquals(created.id, result.records.single().contractorId)
    }

    /** Новый подрядчик встаёт в конец: цвет и порядок не должны налезать на уже занятые. */
    @Test
    fun `a created contractor continues the existing color and order`() {
        val existing = listOf(contractor("Оконщики", colorIndex = 4, orderIndex = 7))

        val created = plan(singleRecordSheet("Кровельщики"), contractors = existing).newContractors.single()

        assertEquals(5, created.colorIndex)
        assertEquals(8, created.orderIndex)
    }

    /**
     * Группа без имени пропускается со счётчиком, а не заводится безымянным подрядчиком. Так выглядит
     * файл с вырезанной таблицей общих строк: структура на месте, а текста нет — и тогда безымянными
     * окажутся все 28 групп разом.
     */
    @Test
    fun `an unnamed contractor group is skipped with a counter`() {
        val sheet = sheet(
            contractors = listOf("  ", "Оконщики"),
            days = listOf(day(DATE, row(cell("К1", "Демонтаж"), cell("К2", "Штукатурка")))),
        )

        val result = plan(sheet)

        assertEquals(1, result.skippedUnnamedContractors)
        assertEquals(1, result.newContractors.size)
        // Запись безымянной группы не переносится вовсе — привязать её не к чему.
        assertEquals(1, result.records.size)
        assertEquals("Оконщики", result.newContractors.single().name)
    }

    /** Одно имя дважды в шапке — это та же колонка, а не второй подрядчик с тем же названием. */
    @Test
    fun `a name repeated in the header creates a single contractor`() {
        val sheet = sheet(
            contractors = listOf("Оконщики", "Оконщики"),
            days = listOf(day(DATE, row(cell("К1", "Демонтаж"), cell("К2", "Штукатурка")))),
        )

        val result = plan(sheet)

        assertEquals(1, result.newContractors.size)
        assertEquals(1, result.records.map { it.contractorId }.distinct().size)
    }

    // --- поля -----------------------------------------------------------------------------------

    /**
     * Главный случай сопоставления полей: «Л» и «ВИД РАБОТ» обязаны лечь на встроенные поля по их
     * константным id. Заведи импорт рядом свои колонки с теми же подписями — и журнал получил бы две
     * «Локации», которые потом нечем слить, а бэкап растащил бы их по разным устройствам.
     */
    @Test
    fun `built-in column titles land on the built-in fields instead of doubling them`() {
        val result = plan(singleRecordSheet("Оконщики"))

        assertTrue("встроенные поля не должны заводиться заново", result.newFields.isEmpty())
        assertEquals(2, result.matchedFieldCount)
        assertEquals(
            mapOf(BuiltInFields.LOCATION_ID to "К1", BuiltInFields.WORK_ID to "Штукатурка"),
            result.records.single().values,
        )
    }

    @Test
    fun `an unknown column title becomes a new field`() {
        val result = plan(
            singleRecordSheet("Оконщики", fieldTitles = listOf("Л", "ОБЪЁМ"), values = listOf("К1", "12 м²")),
        )

        val created = result.newFields.single()
        assertEquals("ОБЪЁМ", created.title)
        assertFalse("заведённое импортом поле не встроенное", created.isBuiltIn)
        assertEquals(1, result.matchedFieldCount)
        assertEquals(
            mapOf(BuiltInFields.LOCATION_ID to "К1", created.id to "12 м²"),
            result.records.single().values,
        )
    }

    /** Совпасть по подписи должно и со своим полем прораба, а не только со встроенным. */
    @Test
    fun `a column title matching an existing custom field reuses it`() {
        val volume = testField("f-volume", title = "ОБЪЁМ")

        val result = plan(
            singleRecordSheet("Оконщики", fieldTitles = listOf("объём"), values = listOf("12 м²")),
            fields = builtInFields + volume,
        )

        assertTrue(result.newFields.isEmpty())
        assertEquals(1, result.matchedFieldCount)
        assertEquals(mapOf("f-volume" to "12 м²"), result.records.single().values)
    }

    @Test
    fun `an unnamed column is skipped with a counter and contributes no value`() {
        val result = plan(
            singleRecordSheet("Оконщики", fieldTitles = listOf("Л", "   "), values = listOf("К1", "потеряется")),
        )

        assertEquals(1, result.skippedUnnamedFields)
        assertTrue(result.newFields.isEmpty())
        assertEquals(mapOf(BuiltInFields.LOCATION_ID to "К1"), result.records.single().values)
    }

    /** Пустое значение — это отсутствие строки в `record_values`, а не строка с пустым текстом. */
    @Test
    fun `blank cell values do not become record values`() {
        val result = plan(
            singleRecordSheet("Оконщики", values = listOf("   ", "Штукатурка")),
        )

        assertEquals(mapOf(BuiltInFields.WORK_ID to "Штукатурка"), result.records.single().values)
    }

    // --- журнал и месяц -------------------------------------------------------------------------

    @Test
    fun `the month comes from the dates in the file`() {
        val result = plan(singleRecordSheet("Оконщики"))

        assertEquals(2026, result.year)
        assertEquals(7, result.month)
    }

    @Test
    fun `an existing journal for that month is reused`() {
        val existing = JournalEntity(id = "j1", year = 2026, month = 7, title = "Июль 2026", createdAt = 0)

        val result = plan(singleRecordSheet("Оконщики"), journals = listOf(existing))

        assertTrue(result.journalExists)
    }

    @Test
    fun `a journal for another month does not count as existing`() {
        val other = JournalEntity(id = "j1", year = 2026, month = 6, title = "Июнь 2026", createdAt = 0)

        val result = plan(singleRecordSheet("Оконщики"), journals = listOf(other))

        assertFalse(result.journalExists)
    }

    /**
     * Файл-месяц обычно задевает соседний день на стыке — последнее число предыдущего месяца в первой
     * строке. Месяц журнала решает большинство, но запись меньшинства НЕ отбрасывается: потерять её
     * хуже, чем показать в журнале соседнего месяца, тем более что дата у неё остаётся своя.
     */
    @Test
    fun `mixed months fall into the majority journal without losing the odd day`() {
        val june30 = LocalDate.of(2026, 6, 30)
        val sheet = sheet(
            contractors = listOf("Оконщики"),
            days = listOf(
                day(june30, row(cell("К0", "Стык"))),
                day(LocalDate.of(2026, 7, 1), row(cell("К1", "Штукатурка"))),
                day(LocalDate.of(2026, 7, 2), row(cell("К2", "Плитка"))),
            ),
        )

        val result = plan(sheet)

        assertEquals(7, result.month)
        assertEquals(2026, result.year)
        assertEquals(3, result.records.size)
        assertEquals(june30, result.records.first().date)
    }

    @Test
    fun `a day without a recognised date contributes no records`() {
        val sheet = sheet(
            contractors = listOf("Оконщики"),
            days = listOf(
                day(null, row(cell("К0", "Без даты"))),
                day(DATE, row(cell("К1", "Штукатурка"))),
            ),
        )

        val result = plan(sheet)

        assertEquals(1, result.records.size)
        assertEquals(DATE, result.records.single().date)
    }

    // --- отказы ---------------------------------------------------------------------------------

    /**
     * Без года в датах месяц взять неоткуда: имя листа у заказчика «Лист2», в имени файла года тоже
     * нет. Угадать — значит увезти записи в чужой год, поэтому импорт отказывается.
     */
    @Test
    fun `a file whose dates carry no year is refused`() {
        val sheet = sheet(
            contractors = listOf("Оконщики"),
            days = listOf(day(null, row(cell("К1", "Штукатурка")))),
        )

        val error = assertThrows { plan(sheet) }
        assertTrue(error.message!!.contains("даты с годом"))
    }

    @Test
    fun `a file without a single transferable record is refused`() {
        val sheet = sheet(
            contractors = listOf("Оконщики"),
            days = listOf(day(DATE, row(cell("  ", "  ")))),
        )

        val error = assertThrows { plan(sheet) }
        assertTrue(error.message!!.contains("ни одной записи"))
    }

    /** Фото без байтов в архиве — не фото: эталон (docs/reference/iyun-xlsx) выглядит именно так. */
    @Test
    fun `a record survives on a photo alone but only if the photo is really there`() {
        val withBytes = plan(
            sheet(
                contractors = listOf("Оконщики"),
                days = listOf(day(DATE, row(cell("", "", photo = PhotoRef("xl/media/i1.jpg", isPresent = true))))),
            ),
        )
        assertEquals(1, withBytes.records.size)
        assertEquals(1, withBytes.photoCount)

        // Оба фото записи учитываются, а отсутствующие байты — нет: одно фото при двух ссылках.
        val twoPhotos = plan(
            sheet(
                contractors = listOf("Оконщики"),
                days = listOf(
                    day(
                        DATE,
                        row(
                            cell(
                                "", "",
                                photos = listOf(
                                    PhotoRef("xl/media/i1.jpg", isPresent = true),
                                    PhotoRef("xl/media/i2.jpg", isPresent = false),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, twoPhotos.records.size)
        assertEquals(1, twoPhotos.photoCount)
        assertEquals(1, twoPhotos.records.single().photos.size)

        val sheetWithoutBytes = sheet(
            contractors = listOf("Оконщики"),
            days = listOf(day(DATE, row(cell("", "", photo = PhotoRef("xl/media/i1.jpg", isPresent = false))))),
        )
        val error = assertThrows { plan(sheetWithoutBytes) }
        assertTrue(error.message!!.contains("ни одной записи"))
    }

    // --- предпросмотр против записи ---------------------------------------------------------------

    /**
     * **Главный тест раздела.** Диалог показывает числа до единой строки в БД, а пишет потом тот же
     * план — и разойтись эти две ветки не должны никогда: пользователь подтверждает то, что увидел.
     *
     * Раньше числа приходили в предпросмотр отдельными параметрами, и совпадение держалось на
     * внимательности вызывающего. Теперь передать их мимо плана попросту нечем — [XlsxImportPreview]
     * своих полей не хранит, — и тест закрепляет это свойство, чтобы оно не вернулось.
     */
    @Test
    fun `preview reports exactly what the plan will write`() {
        val sheet = sheet(
            contractors = listOf("Оконщики", "Кровельщики", "  "),
            fieldTitles = listOf("Л", "ВИД РАБОТ", "ОБЪЁМ"),
            days = listOf(
                day(
                    DATE,
                    row(
                        cell("К1", "Штукатурка", "12 м²", photo = PhotoRef("xl/media/i1.jpg", isPresent = true)),
                        cell("К2", "Кровля", ""),
                        cell("К3", "Потеряется", ""),
                    ),
                ),
                day(DATE.plusDays(1), row(cell("К4", "Плитка", ""), null, null)),
            ),
        )
        val result = plan(sheet, contractors = listOf(contractor("Оконщики")))
        val preview = XlsxImportPreview(
            journalTitle = "Июль 2026",
            plan = result,
            sourceFile = File("не-важно"),
            photoBytes = { null },
        )

        // Каждое число предпросмотра — это ровно содержимое плана, которым потом пишет apply().
        assertEquals(result.records.size, preview.recordCount)
        assertEquals(result.newContractors.size, preview.newContractors.size)
        assertEquals(result.newFields.size, preview.newFields.size)
        assertEquals(result.photoCount, preview.photoCount)
        assertEquals(result.dayCount, preview.dayCount)
        assertEquals(result.matchedContractorCount, preview.matchedContractorCount)
        assertEquals(result.matchedFieldCount, preview.matchedFieldCount)
        assertEquals(result.skippedUnnamedContractors, preview.skippedUnnamedContractors)
        assertEquals(result.skippedUnnamedFields, preview.skippedUnnamedFields)

        // И сами числа те, что ожидаются от этого файла, — иначе равенство выше сошлось бы на нуле.
        assertEquals(3, preview.recordCount)
        assertEquals(2, preview.dayCount)
        assertEquals(1, preview.photoCount)
        assertEquals(listOf("Кровельщики"), preview.newContractors)
        assertEquals(listOf("ОБЪЁМ"), preview.newFields)
        assertEquals(1, preview.matchedContractorCount)
        assertEquals(1, preview.skippedUnnamedContractors)
    }

    /**
     * План обязан быть замкнут по ссылкам: каждая запись указывает на подрядчика и поля, которые к
     * моменту вставки уже есть в базе — либо лежали там, либо создаются этим же планом. Промах здесь
     * не заметен в предпросмотре и всплывёт нарушением внешнего ключа посреди транзакции, когда
     * половина чужого месяца уже разобрана.
     */
    @Test
    fun `every planned record points at an entity that will exist`() {
        val existingContractor = contractor("Оконщики")
        val sheet = sheet(
            contractors = listOf("Оконщики", "Кровельщики"),
            fieldTitles = listOf("Л", "ОБЪЁМ"),
            days = listOf(day(DATE, row(cell("К1", "12 м²"), cell("К2", "8 м²")))),
        )

        val result = plan(sheet, contractors = listOf(existingContractor))

        val contractorIds = (listOf(existingContractor) + result.newContractors).mapTo(HashSet()) { it.id }
        val fieldIds = (builtInFields + result.newFields).mapTo(HashSet()) { it.id }
        for (record in result.records) {
            assertTrue("подрядчик записи не будет существовать", record.contractorId in contractorIds)
            assertTrue("поле значения не будет существовать", fieldIds.containsAll(record.values.keys))
        }
    }

    private fun assertThrows(block: () -> Unit): XlsxImportException = try {
        block()
        throw AssertionError("ожидалось XlsxImportException")
    } catch (e: XlsxImportException) {
        e
    }

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 7, 1)
        const val NOW = 1_750_000_000_000L
    }
}
