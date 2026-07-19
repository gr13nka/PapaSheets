package ru.papasheets.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.papasheets.ui.daylist.DayListScreen
import ru.papasheets.ui.journal.JournalScreen
import ru.papasheets.ui.journals.JournalListScreen
import ru.papasheets.ui.lightbox.LightboxScreen
import ru.papasheets.ui.settings.ContractorsScreen
import ru.papasheets.ui.settings.FieldsScreen

private const val ARG_JOURNAL_ID = "journalId"
private const val ROUTE_JOURNALS = "journals"
private const val ROUTE_JOURNAL = "journal/{$ARG_JOURNAL_ID}"
private const val ROUTE_SETTINGS_CONTRACTORS = "settings/contractors"
private const val ROUTE_SETTINGS_FIELDS = "settings/fields"

/** Debug-маршрут: плоский список записей того же журнала. Кнопки на него из UI нет — только по URL. */
private const val ROUTE_JOURNAL_LIST = "journal/{$ARG_JOURNAL_ID}/list"
private const val ARG_RECORD_ID = "recordId"
private const val ROUTE_LIGHTBOX = "lightbox/{$ARG_RECORD_ID}"

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_JOURNALS) {
        composable(ROUTE_JOURNALS) {
            JournalListScreen(
                onOpenJournal = { journalId -> navController.navigate("journal/$journalId") },
                onOpenContractors = { navController.navigate(ROUTE_SETTINGS_CONTRACTORS) },
                onOpenFields = { navController.navigate(ROUTE_SETTINGS_FIELDS) },
            )
        }
        composable(ROUTE_SETTINGS_CONTRACTORS) {
            ContractorsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SETTINGS_FIELDS) {
            FieldsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = ROUTE_JOURNAL,
            arguments = listOf(navArgument(ARG_JOURNAL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val journalId = backStackEntry.arguments?.getString(ARG_JOURNAL_ID) ?: return@composable
            JournalScreen(
                journalId = journalId,
                onBack = { navController.popBackStack() },
                onOpenLightbox = { recordId -> navController.navigate("lightbox/$recordId") },
                onOpenContractors = { navController.navigate(ROUTE_SETTINGS_CONTRACTORS) },
                onOpenFields = { navController.navigate(ROUTE_SETTINGS_FIELDS) },
            )
        }
        composable(
            route = ROUTE_JOURNAL_LIST,
            arguments = listOf(navArgument(ARG_JOURNAL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val journalId = backStackEntry.arguments?.getString(ARG_JOURNAL_ID) ?: return@composable
            DayListScreen(
                journalId = journalId,
                onBack = { navController.popBackStack() },
                onOpenLightbox = { recordId -> navController.navigate("lightbox/$recordId") },
            )
        }
        composable(
            route = ROUTE_LIGHTBOX,
            arguments = listOf(navArgument(ARG_RECORD_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString(ARG_RECORD_ID) ?: return@composable
            LightboxScreen(
                recordId = recordId,
                onClose = { navController.popBackStack() },
            )
        }
    }
}
