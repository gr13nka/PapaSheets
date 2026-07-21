package ru.papasheets.exportkit.csv

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.exportkit.TestFixtures
import ru.papasheets.exportkit.model.SnapshotField

class CsvWriterTest {

    @Test
    fun `csv has utf8 BOM, semicolon delimiter, RFC4180 escaping and cyrillic`() {
        val out = ByteArrayOutputStream()
        CsvWriter.write(TestFixtures.snapshot(), out)
        val bytes = out.toByteArray()

        assertEquals(0xEF, bytes[0].toInt() and 0xFF)
        assertEquals(0xBB, bytes[1].toInt() and 0xFF)
        assertEquals(0xBF, bytes[2].toInt() and 0xFF)

        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

        // Подписи колонок полей берутся из снимка — те же, что в шапке xlsx, а не отдельная константа.
        // Колонок под фото столько же, сколько слотов у записи, и они всегда обе.
        assertTrue(text, text.startsWith("Дата;Подрядчик;Л;ВИД РАБОТ;Фото 1;Фото 2\r\n"))

        // Плоский формат: только 3 фактических записи, пустая ячейка (день1/подрядчик2) строки не создаёт.
        assertEquals(4, Regex("\r\n").findAll(text).count()) // заголовок + 3 записи

        // Одно фото — вторая колонка пустая; пустой слот не сдвигает строку относительно шапки.
        assertTrue(text, text.contains("01.07;Иванов;1-01;\"Штукатурка <потолок>\nвторая строка\";photo-a.jpg;\r\n"))
        assertTrue(text, text.contains("02.07;Иванов;1-02;Заливка пола;;\r\n"))
        assertTrue(
            text,
            text.contains(
                "02.07;\"Петров & \"\"Сыновья\"\"\";\"2-05; доп\";" +
                    "\"Кладка \"\"кирпич\"\" & раствор\";photo-b.jpg;photo-c.jpg\r\n",
            ),
        )
    }

    /** Подпись поля задаёт пользователь: `;` в ней без экранирования сломала бы разбор первой строки. */
    @Test
    fun `field titles in the header are escaped like data`() {
        val fields = listOf(
            SnapshotField(title = "Объём; м\"2", widthChars = 10.0, wrap = false),
            SnapshotField(title = "Обычное", widthChars = 10.0, wrap = false),
        )
        val out = ByteArrayOutputStream()
        CsvWriter.write(TestFixtures.snapshotWithFields(fields), out)

        val bytes = out.toByteArray()
        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        assertTrue(text, text.startsWith("Дата;Подрядчик;\"Объём; м\"\"2\";Обычное;Фото 1;Фото 2\r\n"))
    }

    /** Число колонок значений всегда равно числу полей — иначе строка «съезжает» относительно шапки. */
    @Test
    fun `data columns follow the field list`() {
        val fields = List(4) { SnapshotField(title = "Поле $it", widthChars = 10.0, wrap = false) }
        val out = ByteArrayOutputStream()
        CsvWriter.write(TestFixtures.snapshotWithFields(fields), out)

        val bytes = out.toByteArray()
        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        assertTrue(text, text.contains("01.07;Иванов;a0;a1;a2;a3;;\r\n"))
        assertTrue(text, text.contains("01.07;Петров;b0;b1;b2;b3;photo-b.jpg;\r\n"))
    }
}
