package ru.papasheets.data

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository

/** Синтетический журнал для перф-проверки рендера матрицы: заведомо «ненастоящий» месяц. */
private const val FAKE_YEAR = 2000
private const val FAKE_MONTH = 1

/** Целевое число колонок — как в реальном Июнь.xlsx (28 подрядчиков). */
private const val TARGET_CONTRACTORS = 28

/** Ширина, до которой подколонка считается «кодовой»: в такую влезает код локации, но не фраза. */
private const val NARROW_FIELD_DP = 80

/**
 * Генератор нагрузочного журнала (только debug): 28 подрядчиков × ~300 строк за месяц с плотностью,
 * как в реальном файле — первый подрядчик почти в каждой строке дня, остальные разреженно. Фото нет
 * (photoId = null): нужна нагрузка на текст-раскладку и виртуализацию, не на декод битмапов.
 *
 * Идемпотентен: если фейковый журнал уже засеян, просто возвращает его id (повторный long-press
 * по FAB не удваивает данные).
 */
class FakeDataSeeder(
    private val contractorRepository: ContractorRepository,
    private val journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    private val fieldRepository: FieldRepository,
) {
    suspend fun seedAndGetJournalId(): String {
        ensureContractors()
        val journal = journalRepository.createOrGetJournal(FAKE_YEAR, FAKE_MONTH)
        if (recordRepository.observeByJournal(journal.id).first().isNotEmpty()) return journal.id

        val contractors = contractorRepository.observeActive().first().sortedBy { it.orderIndex }
        val records = generateRecords(journal.id, contractors)
        recordRepository.insertAll(records)
        recordRepository.insertValues(generateValues(records))
        return journal.id
    }

    /**
     * По значению в каждое активное поле: нагрузка на текст-раскладку должна расти вместе с числом
     * подколонок, а не оставаться привязанной к двум встроенным полям.
     */
    private suspend fun generateValues(records: List<RecordEntity>): List<RecordValueEntity> {
        val fields = fieldRepository.observeActive().first()
        if (fields.isEmpty()) return emptyList()
        val random = Random(FAKE_YEAR)
        return records.flatMap { record ->
            fields.map { field ->
                // Узкая подколонка получает код локации, широкая — фразу о ходе работ.
                val value = if (field.maxLines == 1 || field.columnWidthDp <= NARROW_FIELD_DP) {
                    LOCATIONS[random.nextInt(LOCATIONS.size)]
                } else {
                    fakeWorkText(random)
                }
                RecordValueEntity(record.id, field.id, value)
            }
        }
    }

    private suspend fun ensureContractors() {
        val active = contractorRepository.observeActive().first()
        if (active.size >= TARGET_CONTRACTORS) return
        val now = System.currentTimeMillis()
        var order = (active.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val needed = TARGET_CONTRACTORS - active.size
        for (index in 0 until needed) {
            val (name, shortName) = EXTRA_CONTRACTORS[index % EXTRA_CONTRACTORS.size]
            contractorRepository.insert(
                ContractorEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    shortName = shortName,
                    colorIndex = order,
                    orderIndex = order,
                    createdAt = now,
                ),
            )
            order++
        }
    }

    private fun generateRecords(journalId: String, contractors: List<ContractorEntity>): List<RecordEntity> {
        if (contractors.isEmpty()) return emptyList()
        val random = Random(FAKE_YEAR * 100 + FAKE_MONTH)
        val length = YearMonth.of(FAKE_YEAR, FAKE_MONTH).lengthOfMonth()
        val records = ArrayList<RecordEntity>()
        var stamp = System.currentTimeMillis()

        for (day in 1..length) {
            val epochDay = LocalDate.of(FAKE_YEAR, FAKE_MONTH, day).toEpochDay()
            // Первый подрядчик задаёт высоту дня (8..12 строк) — почти в каждой строке.
            repeat(8 + random.nextInt(5)) {
                records += fakeRecord(journalId, epochDay, contractors[0].id, stamp++)
            }
            // Остальные — разреженно: у большинства пусто, изредка 1-2 записи.
            for (columnIndex in 1 until contractors.size) {
                val count = if (random.nextInt(100) < 72) 0 else 1 + random.nextInt(2)
                repeat(count) {
                    records += fakeRecord(journalId, epochDay, contractors[columnIndex].id, stamp++)
                }
            }
        }
        return records
    }

    private fun fakeRecord(
        journalId: String,
        epochDay: Long,
        contractorId: String,
        createdAt: Long,
    ): RecordEntity = RecordEntity(
        id = UUID.randomUUID().toString(),
        journalId = journalId,
        dateEpochDay = epochDay,
        contractorId = contractorId,
        photoId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    /** Собирает реалистичный текст 40–120 символов из фраз о ходе работ. */
    private fun fakeWorkText(random: Random): String {
        val builder = StringBuilder(WORK_PHRASES[random.nextInt(WORK_PHRASES.size)])
        while (builder.length < 40) {
            builder.append(", ").append(WORK_DETAILS[random.nextInt(WORK_DETAILS.size)])
        }
        return builder.toString().take(120)
    }

    private companion object {
        val EXTRA_CONTRACTORS = listOf(
            "Электрики" to "Эл", "Сантехники" to "СТ", "Штукатуры" to "Штк", "Маляры" to "Мал",
            "Кровельщики" to "Крв", "Фасадчики" to "Фас", "Бетонщики" to "Бет", "Арматурщики" to "Арм",
            "Каменщики" to "Кам", "Гипсокартон" to "ГКЛ", "Стяжка" to "Стж", "Слаботочка" to "СлТ",
            "Вентиляция" to "Вен", "Кондиционеры" to "Конд", "Лифтовики" to "Лиф", "Ландшафт" to "Лнд",
            "Озеленение" to "Озл", "Дорожники" to "Дор", "Демонтаж" to "Дем", "Гидроизоляция" to "Гид",
            "Утеплители" to "Утп", "Стекольщики" to "Стк", "Мебельщики" to "Меб",
        )

        val LOCATIONS = listOf(
            "1-05", "1-12", "2-03", "2-14", "3-07", "3-21", "Подвал", "Кровля",
            "Холл", "Паркинг", "Лестница", "Фасад-А", "Фасад-Б", "Тех.этаж",
        )

        val WORK_PHRASES = listOf(
            "Демонтаж старой стяжки и вывоз мусора",
            "Монтаж перегородок из газоблока",
            "Прокладка электрики по потолку",
            "Штукатурка стен по маякам",
            "Укладка плитки на пол в санузле",
            "Заливка бетонной стяжки",
            "Установка оконных блоков",
            "Гидроизоляция пола и примыканий",
            "Монтаж системы вентиляции",
            "Грунтовка и шпаклёвка стен",
            "Разводка водоснабжения и канализации",
            "Устройство наливного пола",
        )

        val WORK_DETAILS = listOf(
            "проверка ровности", "приёмка узла", "подготовка основания", "герметизация швов",
            "контроль уровня", "уборка после работ", "докупка материала", "переделка участка",
        )
    }
}
