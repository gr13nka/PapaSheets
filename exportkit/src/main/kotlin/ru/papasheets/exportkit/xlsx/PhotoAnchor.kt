package ru.papasheets.exportkit.xlsx

/**
 * Один якорь фото в листе: 0-based координаты Ф-ячейки записи ([col]/[row], см. [SheetXml]),
 * готовый EMU-размер ([extCx]/[extCy], см. [Emu]) и `rId`, которым [DrawingXml] ссылается на
 * `xl/media/$photoId.jpg` через [RelsXml.drawing].
 */
internal class PhotoAnchor(
    val col: Int,
    val row: Int,
    val rId: String,
    val extCx: Long,
    val extCy: Long,
    val photoId: String,
)
