package ru.papasheets.exportkit.csv

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.exportkit.TestFixtures

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

        assertTrue(text.startsWith("Дата;Подрядчик;Локация;Вид работ;Фото\r\n"))

        // Плоский формат: только 3 фактических записи, пустая ячейка (день1/подрядчик2) строки не создаёт.
        assertEquals(4, Regex("\r\n").findAll(text).count()) // заголовок + 3 записи

        assertTrue(text.contains("01.07.2026;Иванов;1-01;\"Штукатурка <потолок>\nвторая строка\";photo-a.jpg\r\n"))
        assertTrue(text.contains("02.07.2026;Иванов;1-02;Заливка пола;\r\n"))
        assertTrue(
            text.contains(
                "02.07.2026;\"Петров & \"\"Сыновья\"\"\";\"2-05; доп\";\"Кладка \"\"кирпич\"\" & раствор\";photo-b.jpg\r\n",
            ),
        )
    }
}
