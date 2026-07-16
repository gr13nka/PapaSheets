package ru.papasheets

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import java.util.Locale
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.nav.AppNav
import ru.papasheets.ui.theme.PapaSheetsTheme

class MainActivity : ComponentActivity() {

    /**
     * Приложение принципиально русскоязычное независимо от системной локали устройства —
     * без этого системные компоненты (DatePicker и т.п.) подстраиваются под locale ОС.
     */
    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("ru")
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration)
        configuration.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
