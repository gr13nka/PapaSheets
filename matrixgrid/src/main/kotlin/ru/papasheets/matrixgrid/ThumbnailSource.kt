package ru.papasheets.matrixgrid

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Источник превью для ячеек. Реализация (в app) сама решает, откуда байты
 * (файлы thumb 256px) и как кэшировать декодированное; контракт рендерера:
 *
 * - [peek] — синхронно и без аллокаций в draw-проходе; null = «ещё не готово»,
 *   рендерер рисует плейсхолдер и не ждёт.
 * - [request] — неблокирующая заявка «понадобится ключ K примерно в размере px»;
 *   реализация грузит асинхронно и по готовности дёргает invalidate, переданный в [MatrixView].
 *   Повторные заявки на тот же ключ — дёшевы (дедупликация на стороне реализации).
 * - На сильном отдалении (LOD2) рендерер вообще не вызывает request — битмапы не нужны.
 */
interface ThumbnailSource {
    fun peek(key: String): ImageBitmap?
    fun request(key: String, targetPx: Int)
}
