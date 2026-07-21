package ru.papasheets.matrixgrid

/**
 * Обратные вызовы жестов по содержимому. Hit-testing целиком внутри модуля;
 * наружу отдаются только доменные события.
 */
interface MatrixCallbacks {
    /** Тап по заполненной ячейке (вне фото-зоны) — открыть запись на редактирование. */
    fun onCellTap(recordId: String)

    /** Тап по фото-превью внутри ячейки — открыть лайтбокс. */
    /** Тап по превью: [slot] — какое именно из фото записи, 0-based. */
    fun onPhotoTap(recordId: String, slot: Int)

    /** Тап по пустому слоту — создать запись с предзаполненными датой и подрядчиком. */
    fun onEmptySlotTap(dateEpochDay: Long, contractorId: String)

    fun onCellLongPress(recordId: String)
}
