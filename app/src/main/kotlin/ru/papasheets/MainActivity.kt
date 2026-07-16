package ru.papasheets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.papasheets.ui.nav.AppNav
import ru.papasheets.ui.theme.PapaSheetsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PapaSheetsTheme {
                AppNav()
            }
        }
    }
}
