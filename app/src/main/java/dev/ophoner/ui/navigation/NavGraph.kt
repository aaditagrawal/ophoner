package dev.ophoner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.ophoner.ui.chat.ChatScreen
import dev.ophoner.ui.conversations.ConversationListScreen
import dev.ophoner.ui.settings.SettingsScreen
import java.net.URLEncoder

object Routes {
    const val CHAT = "chat"
    const val CHAT_WITH_ID = "chat/{conversationId}"
    const val CHAT_FOLDER = "chat_folder/{folderUri}/{folderName}"
    const val SETTINGS = "settings"
    const val CONVERSATIONS = "conversations"
}

@Composable
fun OphoneNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
    ) {
        composable(
            Routes.CHAT,
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
            )
        }

        composable(
            Routes.CHAT_WITH_ID,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
            )
        }

        composable(
            Routes.CHAT_FOLDER,
            arguments = listOf(
                navArgument("folderUri") { type = NavType.StringType },
                navArgument("folderName") { type = NavType.StringType },
            ),
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
            )
        }

        composable(
            Routes.SETTINGS,
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.CONVERSATIONS,
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
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
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                onNewConversation = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                onNewFolderConversation = { uri, name ->
                    val encodedUri = URLEncoder.encode(uri, "UTF-8")
                    val encodedName = URLEncoder.encode(name, "UTF-8")
                    navController.navigate("chat_folder/$encodedUri/$encodedName") {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
            )
        }
    }
}
