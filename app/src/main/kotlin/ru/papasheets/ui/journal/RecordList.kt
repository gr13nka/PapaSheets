package ru.papasheets.ui.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.papasheets.domain.RecordSort
import ru.papasheets.domain.SortKey
import ru.papasheets.matrixgrid.MatrixPalette
import ru.papasheets.photos.PhotoStore

/** Ширина фото-колонки — единственный фиксированный размер таблицы; всё остальное делится по весам. */
private val PHOTO_COLUMN_WIDTH = 52.dp

/**
 * Вид «Список»: плоская таблица записей, отсортированная по любому столбцу.
 *
 * **Текст виден целиком по построению.** Ни у ячейки, ни у строки нет заданной высоты и нет
 * `maxLines`: строка растёт под самое длинное значение, а колонки делят ширину экрана по весам,
 * унаследованным от ширин колонок матрицы ([ListColumn.weight]). Поэтому список — второй ответ на
 * требование «весь текст должен быть виден»: там, где матрица показывает картину месяца, он
 * показывает содержание записей без обрезки и без горизонтальной прокрутки.
 *
 * Шапка вынесена из [LazyColumn] и не прокручивается: столбец, по которому сортируешь, должен быть
 * виден и на середине списка, иначе стрелку направления негде прочитать.
 */
@Composable
fun RecordList(
    columns: List<ListColumn>,
    rows: List<RecordRow>,
    sort: RecordSort,
    photoStore: PhotoStore,
    onSortBy: (SortKey) -> Unit,
    onRecordClick: (String) -> Unit,
    onRecordLongClick: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        HeaderRow(columns = columns, sort = sort, onSortBy = onSortBy)
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp), // место под FAB
        ) {
            items(rows, key = { it.recordId }) { row ->
                RecordListRow(
                    row = row,
                    columns = columns,
                    photoStore = photoStore,
                    onClick = { onRecordClick(row.recordId) },
                    onLongClick = { onRecordLongClick(row.recordId) },
                    onPhotoClick = { onPhotoClick(row.recordId) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun HeaderRow(columns: List<ListColumn>, sort: RecordSort, onSortBy: (SortKey) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(modifier = Modifier.size(PHOTO_COLUMN_WIDTH))
        columns.forEach { column ->
            val active = column.key == sort.key
            Text(
                // Стрелка только у активного столбца: она показывает не «можно сортировать»,
                // а куда именно этот столбец сейчас отсортирован.
                text = if (active) "${column.title} ${if (sort.desc) "↓" else "↑"}" else column.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(column.weight)
                    .clickable { onSortBy(column.key) }
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordListRow(
    row: RecordRow,
    columns: List<ListColumn>,
    photoStore: PhotoStore,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPhotoClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(PHOTO_COLUMN_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            val photoId = row.photoId
            if (photoId != null) {
                AsyncImage(
                    model = photoStore.thumbFile(photoId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onPhotoClick),
                )
            } else {
                // Без фото цвет подрядчика остаётся единственной быстрой меткой строки — как в матрице.
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MatrixPalette.color(row.contractorColorIndex, isSystemInDarkTheme())),
                )
            }
        }
        columns.forEachIndexed { index, column ->
            Cell(text = row.cells[index], weight = column.weight)
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 6.dp),
    )
}
