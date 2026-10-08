package ai.droidcommand.knowledge.android

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

// Schema notes:
// - Properties live in their own tables (no JSON column, so no extra serialization dependency).
// - Relationships reference entities with ON DELETE CASCADE, so removing an entity removes every
//   edge touching it, in either direction, exactly like core-agent.InMemoryKnowledgeGraph.
// - Timestamps are ISO-8601 text (Instant.toString / Instant.parse) because that round-trips the
//   full nanosecond precision of java.time.Instant; epoch millis would silently truncate it.
// - Entities are written with @Upsert, never REPLACE: REPLACE deletes the old row first, and that
//   delete would cascade away the entity's relationships on every update.

@Entity(tableName = "kg_entities", indices = [Index("type")])
data class EntityRow(
    @PrimaryKey val id: String,
    val type: String,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "kg_entity_properties",
    primaryKeys = ["entity_id", "key"],
    foreignKeys = [
        ForeignKey(EntityRow::class, parentColumns = ["id"], childColumns = ["entity_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EntityPropertyRow(
    @ColumnInfo(name = "entity_id") val entityId: String,
    val key: String,
    val value: String,
)

@Entity(
    tableName = "kg_relationships",
    foreignKeys = [
        ForeignKey(EntityRow::class, parentColumns = ["id"], childColumns = ["from_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EntityRow::class, parentColumns = ["id"], childColumns = ["to_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["from_id", "type"]), Index(value = ["to_id", "type"])],
)
data class RelationshipRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_id") val fromId: String,
    @ColumnInfo(name = "to_id") val toId: String,
    val type: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(
    tableName = "kg_relationship_properties",
    primaryKeys = ["relationship_id", "key"],
    foreignKeys = [
        ForeignKey(RelationshipRow::class, parentColumns = ["id"], childColumns = ["relationship_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RelationshipPropertyRow(
    @ColumnInfo(name = "relationship_id") val relationshipId: String,
    val key: String,
    val value: String,
)

/** Non-suspend on purpose: [ai.droidcommand.agent.KnowledgeGraph] is synchronous. Call off the main thread. */
@Dao
interface KnowledgeDao {
    @Upsert
    fun upsertEntity(row: EntityRow)

    @Query("DELETE FROM kg_entity_properties WHERE entity_id = :id")
    fun deleteEntityProperties(id: String)

    @Insert
    fun insertEntityProperties(rows: List<EntityPropertyRow>)

    @Query("SELECT * FROM kg_entities WHERE id = :id")
    fun entity(id: String): EntityRow?

    @Query("SELECT * FROM kg_entities WHERE id IN (:ids)")
    fun entities(ids: List<String>): List<EntityRow>

    @Query("SELECT * FROM kg_entities WHERE type = :type")
    fun entitiesOfType(type: String): List<EntityRow>

    @Query("SELECT * FROM kg_entities")
    fun allEntities(): List<EntityRow>

    @Query("SELECT * FROM kg_entity_properties WHERE entity_id IN (:ids)")
    fun entityProperties(ids: List<String>): List<EntityPropertyRow>

    @Query("DELETE FROM kg_entities WHERE id = :id")
    fun deleteEntity(id: String): Int

    @Upsert
    fun upsertRelationship(row: RelationshipRow)

    @Query("DELETE FROM kg_relationship_properties WHERE relationship_id = :id")
    fun deleteRelationshipProperties(id: String)

    @Insert
    fun insertRelationshipProperties(rows: List<RelationshipPropertyRow>)

    @Query("SELECT * FROM kg_relationships WHERE from_id = :entityId")
    fun relationshipsFrom(entityId: String): List<RelationshipRow>

    @Query("SELECT * FROM kg_relationships WHERE to_id = :entityId")
    fun relationshipsTo(entityId: String): List<RelationshipRow>

    @Query("SELECT * FROM kg_relationship_properties WHERE relationship_id IN (:ids)")
    fun relationshipProperties(ids: List<String>): List<RelationshipPropertyRow>

    @Query("DELETE FROM kg_relationships WHERE id = :id")
    fun deleteRelationship(id: String): Int

    /** Ids one hop away in either direction, optionally restricted to one relationship type. Indexed on both ends. */
    @Query(
        "SELECT to_id FROM kg_relationships WHERE from_id = :id AND (:type IS NULL OR type = :type) " +
            "UNION SELECT from_id FROM kg_relationships WHERE to_id = :id AND (:type IS NULL OR type = :type)",
    )
    fun neighborIds(id: String, type: String?): List<String>
}

@Database(
    entities = [EntityRow::class, EntityPropertyRow::class, RelationshipRow::class, RelationshipPropertyRow::class],
    version = 1,
    exportSchema = true,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun dao(): KnowledgeDao
}
