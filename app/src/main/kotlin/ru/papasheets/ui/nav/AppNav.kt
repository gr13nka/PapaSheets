package ru.papasheets.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.papasheets.ui.journals.JournalListScreen

private const val ROUTE_JOURNALS = "journals"

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_JOURNALS) {
        composable(ROUTE_JOURNALS) {
            JournalListScreen()
        }
    }
}
