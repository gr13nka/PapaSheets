package ru.papasheets.exportkit

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.PhotoBytesProvider
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotContractor
import ru.papasheets.exportkit.model.SnapshotDay
import ru.papasheets.exportkit.model.SnapshotRow

/**
 * Общий снимок для xlsx- и csv-тестов: 2 подрядчика × 2 дня × 3 записи, 2 из них с фото.
 * Нарочно содержит кириллицу и `& < > " ; \n` — экранирование обеих форматов проверяется на одних
 * и тех же строках. Использует [javax.imageio.ImageIO] (доступен на JVM тестов, но не на Android —
 * поэтому только здесь, не в main-коде exportkit) для генерации настоящих JPEG разных пропорций.
 */
object TestFixtures {
    const val PHOTO_A = "photo-a"
    const val PHOTO_B = "photo-b"

    // Разные пропорции (landscape/portrait) — проверка, что EMU сохраняет aspect ratio.
    val photoASize = 40 to 30
    val photoBSize = 24 to 40

    fun snapshot(): JournalSnapshot = JournalSnapshot(
        title = "Июль 2026",
        contractors = listOf(
            SnapshotContractor(name = "Иванов"),
            SnapshotContractor(name = "Петров & \"Сыновья\""),
        ),
        days = listOf(
            SnapshotDay(
                dateLabel = "01.07.2026",
                rows = listOf(
                    SnapshotRow(
                        cells = listOf(
                            SnapshotCell(locationCode = "1-01", workText = "Штукатурка <потолок>\nвторая строка", photoId = PHOTO_A),
                            null,
                        ),
                    ),
                ),
            ),
            SnapshotDay(
                dateLabel = "02.07.2026",
                rows = listOf(
                    SnapshotRow(
                        cells = listOf(
                            SnapshotCell(locationCode = "1-02", workText = "Заливка пола", photoId = null),
                            SnapshotCell(locationCode = "2-05; доп", workText = "Кладка \"кирпич\" & раствор", photoId = PHOTO_B),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    fun photoProvider(): PhotoBytesProvider {
        val bytes = mapOf(
            PHOTO_A to jpegBytes(photoASize.first, photoASize.second),
            PHOTO_B to jpegBytes(photoBSize.first, photoBSize.second),
        )
        val sizes = mapOf(PHOTO_A to photoASize, PHOTO_B to photoBSize)
        return object : PhotoBytesProvider {
            override fun open(photoId: String) = bytes.getValue(photoId).inputStream()
            override fun size(photoId: String) = sizes.getValue(photoId)
        }
    }
}
