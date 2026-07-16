package ru.papasheets.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.papasheets.ui.daylist.DayListScreen
import ru.papasheets.ui.journals.JournalListScreen

private const val ROUTE_JOURNALS = "journals"
private const val ARG_JOURNAL_ID = "journalId"
private const val ROUTE_JOURNAL = "journal/{$ARG_JOURNAL_ID}"

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_JOURNALS) {
        composable(ROUTE_JOURNALS) {
            JournalListScreen(
                onOpenJournal = { journalId -> navController.navigate("journal/$journalId") },
            )
        }
        composable(
            route = ROUTE_JOURNAL,
            arguments = listOf(navArgument(ARG_JOURNAL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val journalId = backStackEntry.arguments?.getString(ARG_JOURNAL_ID) ?: return@composable
            DayListScreen(
                journalId = journalId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
