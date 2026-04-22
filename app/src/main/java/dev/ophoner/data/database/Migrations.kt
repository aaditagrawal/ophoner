package dev.ophoner.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room migrations for the Ophoner database.
 *
 * These replace the previous use of `.fallbackToDestructiveMigration()`, which
 * wiped all user conversations on any schema version bump. Every schema change
 * from version 2 onward must add a corresponding migration here.
 */
object DatabaseMigrations {

    /**
     * No-op migration from 1 -> 2.
     *
     * The pre-2 schema shape was never documented and the app has been shipping
     * at version 2 for the entire user-visible history. This migration exists
     * only as a safe fallback so that any hypothetical device still on version
     * 1 does not hit a missing-migration crash; there is no known structural
     * delta to apply.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op: unknown 1 -> 2 delta. Preserve whatever is there.
        }
    }

    /**
     * Adds indices to the `conversations` and `messages` tables to match the
     * `@Index` declarations added to the entities in schema version 3.
     *
     * - conversations.providerConfigId: speeds up listing/filtering per provider.
     * - conversations.createdAt: speeds up ordered listings by creation time.
     * - messages.createdAt: speeds up ordered message reads within a conversation.
     *
     * (messages.conversationId already had an index from version 2 via the
     * foreign-key declaration on MessageEntity.)
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_conversations_providerConfigId " +
                    "ON conversations(providerConfigId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_conversations_createdAt " +
                    "ON conversations(createdAt)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_createdAt " +
                    "ON messages(createdAt)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
    )

    // Future migrations (2 → 3, etc.) added here as schema evolves.
}
