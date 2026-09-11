package ru.papasheets

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import ru.papasheets.localization.applyFirstLaunchLanguage
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.nav.AppNav
import ru.papasheets.ui.theme.PapaSheetsTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Смена языка здесь пересоздаст Activity, но не всегда (система могла уже быть русской),
        // поэтому экран строится как обычно, а не только в пересозданной.
        applyFirstLaunchLanguage()
        enableEdgeToEdge()
        val graph = (application as App).graph
        setContent {
            PapaSheetsTheme {
                CompositionLocalProvider(LocalAppGraph provides graph) {
                    AppNav()
                }
            }
        }
    }
}
