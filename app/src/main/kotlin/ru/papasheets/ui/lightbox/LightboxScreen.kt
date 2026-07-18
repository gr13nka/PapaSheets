package ru.papasheets.ui.lightbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.LocalDate
import ru.papasheets.R
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.ZoomableImage

/**
 * Fullscreen-просмотр фото записи с пинч-зумом. Кнопки «Редактировать» здесь нет — придёт вместе
 * с матрицей в M3, когда появится единая точка входа в форму записи из грид-ячейки.
 */
@Composable
fun LightboxScreen(recordId: String, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: LightboxViewModel = viewModel(
        key = "lightbox-$recordId",
        factory = viewModelFactory {
            initializer {
                LightboxViewModel(
                    recordId = recordId,
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                    fieldRepository = graph.fieldRepository,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val photoId = state.photoId
        if (!state.isLoaded || photoId == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            ZoomableImage(
                model = graph.photoStore.mediumFile(photoId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = captionText(state),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        TextButton(
            onClick = onClose,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Text(stringResource(R.string.lightbox_close))
        }
    }
}

@Composable
private fun captionText(state: LightboxUiState): String {
    val epochDay = state.dateEpochDay
    val datePart = if (epochDay != null) {
        val date = remember(epochDay) { LocalDate.ofEpochDay(epochDay) }
        val monthsGenitive = stringArrayResource(R.array.month_names_genitive)
        "${date.dayOfMonth} ${monthsGenitive[date.monthValue - 1]}"
    } else {
        ""
    }
    return listOf(datePart, state.contractorShortName, state.recordLabel)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}
