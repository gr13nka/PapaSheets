package ru.papasheets.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.matrixgrid.MatrixPalette

/**
 * Выбор цвета из палитры [MatrixPalette] — той же, что раздаёт цвета подрядчикам.
 *
 * Цвет здесь всегда можно снять ([onPick] с `null`): покрасить значение по ошибке легко, а
 * диалог без выхода заставил бы прораба искать «бесцветный» оттенок среди десяти цветных.
 * Выбор применяется сразу по нажатию — подтверждать нечего, результат виден в матрице.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    selected: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(MatrixPalette.size) { index ->
                        ColorSwatch(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MatrixPalette.color(index, dark))
                                .clickable { onPick(index) },
                            selected = index == selected,
                        )
                    }
                }
                TextButton(onClick = { onPick(null) }, enabled = selected != null) {
                    Text(stringResource(R.string.color_picker_none))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Выбранный оттенок помечен ободком, а не галочкой: галочка на тёмном цвете теряется. */
@Composable
private fun ColorSwatch(modifier: Modifier, selected: Boolean) {
    Box(
        modifier = if (selected) {
            modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
        } else {
            modifier
        },
    )
}

/**
 * Кружок текущего цвета значения — и кнопка, открывающая [ColorPickerDialog]. Бесцветное значение
 * показано пустым контуром: место кнопки постоянно, поэтому её не приходится искать заново, когда
 * цвет снят.
 */
@Composable
fun ColorSwatchButton(colorIndex: Int?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val outline = MaterialTheme.colorScheme.outline
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .then(
                    if (colorIndex == null) {
                        Modifier.border(1.dp, outline, CircleShape)
                    } else {
                        Modifier.background(MatrixPalette.color(colorIndex, dark))
                    },
                )
                .clickable(enabled = enabled, onClick = onClick),
        )
    }
}
