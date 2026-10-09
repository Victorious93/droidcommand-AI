package ai.droidcommand.rag.android

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** One embedded chunk. [vector] is little-endian IEEE-754 floats, 4 bytes each. */
@Entity(tableName = "rag_chunks", indices = [Index(value = ["doc_id", "chunk_index"], unique = true)])
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val text: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
)

@Entity(tableName = "rag_documents")
data class DocumentEntity(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: String,
    val name: String,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Non-suspend on purpose (the vector store interface is synchronous): call off the main thread. */
@Dao
interface RagDao {
    @Insert
    fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM rag_chunks ORDER BY doc_id ASC, chunk_index ASC")
    fun allChunks(): List<ChunkEntity>

    @Query("DELETE FROM rag_chunks WHERE doc_id = :docId")
    fun deleteChunks(docId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDocument(document: DocumentEntity)

    @Query("SELECT * FROM rag_documents ORDER BY added_at ASC, doc_id ASC")
    fun documents(): List<DocumentEntity>

    @Query("DELETE FROM rag_documents WHERE doc_id = :docId")
    fun deleteDocument(docId: String): Int

    /** Chunks and listing entry go together so they cannot disagree after a crash. */
    @Transaction
    fun deleteAll(docId: String) {
        deleteChunks(docId)
        deleteDocument(docId)
    }
}

@Database(entities = [ChunkEntity::class, DocumentEntity::class], version = 1, exportSchema = true)
abstract class RagDatabase : RoomDatabase() {
    abstract fun ragDao(): RagDao
}
