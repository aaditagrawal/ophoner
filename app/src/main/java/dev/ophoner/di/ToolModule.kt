package dev.ophoner.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolRegistry
import dev.ophoner.tools.impl.AppListTool
import dev.ophoner.tools.impl.DeviceControlTool
import dev.ophoner.tools.impl.FileDeleteTool
import dev.ophoner.tools.impl.FileListTool
import dev.ophoner.tools.impl.FileMoveTool
import dev.ophoner.tools.impl.FileReadTool
import dev.ophoner.tools.impl.FileWriteTool
import dev.ophoner.tools.impl.IntentLaunchTool
import dev.ophoner.tools.impl.ShellExecuteTool
import dev.ophoner.tools.impl.WebFetchTool
import dev.ophoner.tools.impl.WebSearchTool
import dev.ophoner.tools.sandbox.FileAccessManager
import dev.ophoner.tools.sandbox.SandboxedShell
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        fileAccessManager: FileAccessManager,
        shell: SandboxedShell,
        httpClient: OkHttpClient,
    ): ToolRegistry {
        val executors: Set<ToolExecutor> = setOf(
            FileReadTool(fileAccessManager),
            FileWriteTool(fileAccessManager),
            FileListTool(fileAccessManager),
            FileDeleteTool(fileAccessManager),
            FileMoveTool(fileAccessManager),
            ShellExecuteTool(shell),
            WebSearchTool(httpClient),
            WebFetchTool(httpClient),
            DeviceControlTool(context),
            IntentLaunchTool(context),
            AppListTool(context),
        )
        return ToolRegistry.from(executors)
    }
}
