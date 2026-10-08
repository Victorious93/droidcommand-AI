package ai.droidcommand.templates.android

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val body: String,
    @ColumnInfo(name = "built_in") val builtIn: Boolean,
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    @ColumnInfo(name = "preferred_provider_type") val preferredProviderType: String?,
    @ColumnInfo(name = "preferred_model") val preferredModel: String?,
    @ColumnInfo(name = "built_in") val builtIn: Boolean,
)

/** Non-suspend on purpose: [ai.droidcommand.templates.TemplateStore] is synchronous. Call off the main thread. */
@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(template: TemplateEntity)

    @Query("SELECT * FROM templates WHERE id = :id")
    fun find(id: String): TemplateEntity?

    // Deterministic order: built-ins first, then by name, then id to break ties.
    @Query("SELECT * FROM templates ORDER BY built_in DESC, name COLLATE NOCASE ASC, id ASC")
    fun all(): List<TemplateEntity>

    @Query("DELETE FROM templates WHERE id = :id AND built_in = 0")
    fun deleteUserTemplate(id: String): Int
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(skill: SkillEntity)

    @Query("SELECT * FROM skills WHERE id = :id")
    fun find(id: String): SkillEntity?

    @Query("SELECT * FROM skills ORDER BY built_in DESC, name COLLATE NOCASE ASC, id ASC")
    fun all(): List<SkillEntity>

    @Query("DELETE FROM skills WHERE id = :id AND built_in = 0")
    fun deleteUserSkill(id: String): Int
}

@Database(entities = [TemplateEntity::class, SkillEntity::class], version = 1, exportSchema = true)
abstract class TemplatesDatabase : RoomDatabase() {
    abstract fun templateDao(): TemplateDao

    abstract fun skillDao(): SkillDao
}
