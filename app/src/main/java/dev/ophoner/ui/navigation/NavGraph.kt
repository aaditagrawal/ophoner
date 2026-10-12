package dev.ophoner.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.ophoner.ui.chat.ChatScreen
import dev.ophoner.ui.conversations.ConversationListScreen
import dev.ophoner.ui.settings.SettingsScreen
import dev.ophoner.device.DeviceSetupScreen
import dev.ophoner.device.CapabilitiesScreen

object Routes {
    const val CHAT = "chat"
    const val CHAT_WITH_ID = "chat/{conversationId}"
    const val CHAT_FOLDER = "chat_folder/{folderUri}/{folderName}"
    const val SETTINGS = "settings"
    const val CONVERSATIONS = "conversations"
    const val CAPABILITIES = "capabilities"
    const val DEVICE_SETUP = "device_setup"
    const val SHIZUKU_SETUP = "shizuku_setup"
}

@Composable
fun OphoneNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
    ) {
        composable(Routes.DEVICE_SETUP) { DeviceSetupScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SHIZUKU_SETUP) { DeviceSetupScreen(onBack = { navController.popBackStack() }, shizukuOnly = true) }
        composable(Routes.CAPABILITIES) {
            CapabilitiesScreen(onBack = { navController.popBackStack() },
                onOpenSetup = { navController.navigate(Routes.DEVICE_SETUP) },
                onOpenShizuku = { navController.navigate(Routes.SHIZUKU_SETUP) })
        }
        composable(
            Routes.CHAT,
            enterTransition = { pageEnter() },
            exitTransition = { pageExit() },
            popEnterTransition = { pagePopEnter() },
            popExitTransition = { pagePopExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                onNewChat = { uri, name -> navController.startNewChat(uri, name) },
                onOpenBranch = { id, draft ->
                    navController.navigate("chat/$id")
                    navController.currentBackStackEntry?.savedStateHandle?.set("branchCreated", true)
                    if (draft != null) navController.currentBackStackEntry?.savedStateHandle?.set("promptDraft", draft)
                },
            )
        }

        composable(
            Routes.CHAT_WITH_ID,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            enterTransition = { pageEnter() },
            exitTransition = { pageExit() },
            popEnterTransition = { pagePopEnter() },
            popExitTransition = { pagePopExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                onNewChat = { uri, name -> navController.startNewChat(uri, name) },
                onOpenBranch = { id, draft ->
                    navController.navigate("chat/$id")
                    navController.currentBackStackEntry?.savedStateHandle?.set("branchCreated", true)
                    if (draft != null) navController.currentBackStackEntry?.savedStateHandle?.set("promptDraft", draft)
                },
            )
        }

        composable(
            Routes.CHAT_FOLDER,
            arguments = listOf(
                navArgument("folderUri") { type = NavType.StringType },
                navArgument("folderName") { type = NavType.StringType },
            ),
            enterTransition = { pageEnter() },
            exitTransition = { pageExit() },
            popEnterTransition = { pagePopEnter() },
            popExitTransition = { pagePopExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                onNewChat = { uri, name -> navController.startNewChat(uri, name) },
                onOpenBranch = { id, draft ->
                    navController.navigate("chat/$id")
                    navController.currentBackStackEntry?.savedStateHandle?.set("branchCreated", true)
                    if (draft != null) navController.currentBackStackEntry?.savedStateHandle?.set("promptDraft", draft)
                },
            )
        }

        composable(
            Routes.SETTINGS,
            enterTransition = { pageEnter() },
            exitTransition = { pageExit() },
            popEnterTransition = { pagePopEnter() },
            popExitTransition = { pagePopExit() },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCapabilities = { navController.navigate(Routes.CAPABILITIES) },
                onOpenDeviceSetup = { navController.navigate(Routes.DEVICE_SETUP) },
                onOpenShizuku = { navController.navigate(Routes.SHIZUKU_SETUP) },
            )
        }

        composable(
            Routes.CONVERSATIONS,
            enterTransition = { drawerEnter() },
            exitTransition = { drawerExit() },
            popEnterTransition = { drawerEnter() },
            popExitTransition = { drawerExit() },
        ) {
            ConversationListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onOpenConversation = { id ->
                    navController.navigate("chat/$id") {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNewConversation = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNewFolderConversation = { uri, name ->
                    val encodedUri = Uri.encode(uri)
                    val encodedName = Uri.encode(name)
                    navController.navigate("chat_folder/$encodedUri/$encodedName") {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/** Replaces the current chat with a fresh one, optionally scoped to a folder. */
private fun NavHostController.startNewChat(folderUri: String?, folderName: String?) {
    val route = if (folderUri != null && folderName != null) {
        "chat_folder/${Uri.encode(folderUri)}/${Uri.encode(folderName)}"
    } else {
        Routes.CHAT
    }
    navigate(route) {
        popUpTo(graph.id) { inclusive = false }
        launchSingleTop = true
    }
}
