package ru.papasheets.ui.journals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.journals_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {}) {
                Text("+")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.journals_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
