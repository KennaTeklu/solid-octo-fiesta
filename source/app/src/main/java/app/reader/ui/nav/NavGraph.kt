package app.reader.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.reader.data.repo.Intake
import app.reader.data.repo.IntakeBus
import app.reader.ui.library.LibraryScreen
import app.reader.ui.library.LibraryViewModel
import app.reader.ui.paste.PasteTextScreen
import app.reader.ui.queue.QueueScreen
import app.reader.ui.queue.QueueViewModel
import app.reader.ui.reader.ReaderScreen
import app.reader.ui.reader.ReaderViewModel
import app.reader.ui.settings.SettingsScreen
import app.reader.ui.settings.SettingsViewModel
import app.reader.ui.voice.TtsEnginePickerScreen
import app.reader.ui.voice.TtsEnginePickerViewModel
import app.reader.ui.voice.VoicePickerScreen
import app.reader.ui.voice.VoicePickerViewModel

@Composable
fun ReaderNavHost(initialIntake: Intake?) {
    val navController = rememberNavController()

    // Drain pending SEND/VIEW intents — route the user to the right screen.
    LaunchedEffect(initialIntake) {
        when (val intake = initialIntake) {
            is Intake.OpenDocument -> {
                navController.navigate(Dest.Reader.path(intake.docId)) {
                    launchSingleTop = true
                }
                IntakeBus.consume()
            }
            is Intake.Text -> {
                navController.navigate(Dest.Paste.route) {
                    launchSingleTop = true
                }
                // IntakeBus stays — the Paste screen reads it on entry.
            }
            is Intake.Error -> {
                // Toast / snackbar could be shown here; for now just consume.
                IntakeBus.consume()
            }
            Intake.None, null -> { /* no-op */ }
        }
    }

    NavHost(navController = navController, startDestination = Dest.Library.route) {

        composable(Dest.Library.route) {
            val vm: LibraryViewModel = viewModel()
            LibraryScreen(
                vm = vm,
                onOpenDocument = { id -> navController.navigate(Dest.Reader.path(id)) },
                onOpenPaste = { navController.navigate(Dest.Paste.route) },
                onOpenQueue = { navController.navigate(Dest.Queue.route) },
                onOpenSettings = { navController.navigate(Dest.Settings.route) }
            )
        }

        composable(
            route = Dest.Reader.ROUTE,
            arguments = listOf(navArgument(Dest.Reader.ARG_DOC_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString(Dest.Reader.ARG_DOC_ID) ?: return@composable
            val vm: ReaderViewModel = viewModel()
            ReaderScreen(
                vm = vm,
                docId = docId,
                onBack = { navController.popBackStack() },
                onOpenBookmarks = { /* modal sheet — handled inside Reader screen */ },
                onOpenToc = { /* modal sheet — handled inside Reader screen */ }
            )
        }

        composable(Dest.Queue.route) {
            val vm: QueueViewModel = viewModel()
            QueueScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDocument = { id -> navController.navigate(Dest.Reader.path(id)) }
            )
        }

        composable(Dest.Paste.route) {
            val initialText = (initialIntake as? Intake.Text)?.text
            PasteTextScreen(
                initialText = initialText,
                onBack = {
                    IntakeBus.consume()
                    navController.popBackStack()
                },
                onSaved = { id ->
                    IntakeBus.consume()
                    navController.navigate(Dest.Reader.path(id)) {
                        popUpTo(Dest.Library.route)
                    }
                }
            )
        }

        composable(Dest.Settings.route) {
            val vm: SettingsViewModel = viewModel()
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenVoicePicker = { navController.navigate(Dest.VoicePicker.route) },
                onOpenEnginePicker = { navController.navigate(Dest.TtsEnginePicker.route) }
            )
        }

        composable(Dest.VoicePicker.route) {
            val vm: VoicePickerViewModel = viewModel()
            VoicePickerScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenEnginePicker = { navController.navigate(Dest.TtsEnginePicker.route) }
            )
        }

        composable(Dest.TtsEnginePicker.route) {
            val vm: TtsEnginePickerViewModel = viewModel()
            TtsEnginePickerScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
