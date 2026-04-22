package dev.ophoner.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.ophoner.data.database.DatabaseMigrations
import dev.ophoner.data.db.ConversationDao
import dev.ophoner.data.db.MessageDao
import dev.ophoner.data.db.OphoneDatabase
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OphoneDatabase =
        Room.databaseBuilder(context, OphoneDatabase::class.java, "ophoner.db")
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    fun provideConversationDao(db: OphoneDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: OphoneDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
}
