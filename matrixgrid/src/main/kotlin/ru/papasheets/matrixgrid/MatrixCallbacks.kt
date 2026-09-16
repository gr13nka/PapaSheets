package ru.papasheets.matrixgrid

/**
 * Обратные вызовы жестов по содержимому. Hit-testing целиком внутри модуля;
 * наружу отдаются только доменные события.
 */
data class MatrixCellTarget(val fieldId: String? = null, val photoSlot: Int? = null)

interface MatrixCallbacks {
    fun onGroupHeaderTap(groupId: String) = Unit
    fun onFieldHeaderTap(fieldId: String) = Unit
    fun onCellTargetTap(recordId: String, target: MatrixCellTarget) = onCellTap(recordId)
    fun onEmptySlotTargetTap(dateEpochDay: Long, contractorId: String, target: MatrixCellTarget) = onEmptySlotTap(dateEpochDay, contractorId)

    /** Тап по заполненной ячейке (вне фото-зоны) — открыть запись на редактирование. */
    fun onCellTap(recordId: String)

    /** Тап по фото-превью внутри ячейки — открыть лайтбокс. */
    /** Тап по превью: [slot] — какое именно из фото записи, 0-based. */
    fun onPhotoTap(recordId: String, slot: Int)

    /** Тап по пустому слоту — создать запись с предзаполненными датой и подрядчиком. */
    fun onEmptySlotTap(dateEpochDay: Long, contractorId: String)

    fun onCellLongPress(recordId: String)
}
