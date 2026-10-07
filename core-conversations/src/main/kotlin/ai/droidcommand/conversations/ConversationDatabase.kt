package ai.droidcommand.conversations

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

// Compiles (KSP/Room ran 2026-10-07e); DAO SQL is tested only via ConversationStoreTest if present. Schema shape (a conversation
// row plus ordered message rows keyed by a conversation id, cascade delete) follows the pattern
// in OpenDroid's chat_sessions/conversations tables (Apache-2.0), reduced to what
// core-agent.ConversationStore actually stores (system prompt, token budget, role+content).

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String?,
    @ColumnInfo(name = "max_tokens") val maxTokens: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id", "position"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val position: Int,
    val role: String,
    val content: String,
)

/** Non-suspend on purpose: [ai.droidcommand.agent.ConversationStore] is synchronous. Call off the main thread. */
@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: ConversationEntity)

    @Insert
    fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversation_id = :id")
    fun deleteMessages(id: String)

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun find(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :id ORDER BY position ASC")
    fun messages(id: String): List<MessageEntity>

    @Query("SELECT id FROM conversations ORDER BY id ASC")
    fun ids(): List<String>

    @Query("DELETE FROM conversations WHERE id = :id")
    fun deleteConversation(id: String): Int

    /** Replaces the whole conversation atomically so a crash can't leave a header with half its messages. */
    @Transaction
    fun replace(conversation: ConversationEntity, messages: List<MessageEntity>) {
        upsert(conversation) // REPLACE deletes the old row, cascading its messages away
        insertMessages(messages)
    }
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}
