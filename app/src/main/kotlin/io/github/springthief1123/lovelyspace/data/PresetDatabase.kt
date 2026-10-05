package io.github.springthief1123.lovelyspace.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "profile_presets")
data class ProfilePreset(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val name: String,
    val sex: Int,
    val years: Int?,
    val prefecture: Int?,
    val isDefault: Boolean = false,
)

@Entity(tableName = "message_presets")
data class MessagePreset(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val message: String,
    val isDefault: Boolean = false,
)

@Entity(tableName = "local_state")
data class LocalState(@PrimaryKey val key: String, val value: String)

@Dao
interface PresetDao {
    @Query("SELECT * FROM profile_presets ORDER BY isDefault DESC, label, id")
    fun profiles(): Flow<List<ProfilePreset>>
    @Query("SELECT * FROM message_presets ORDER BY isDefault DESC, label, id")
    fun messages(): Flow<List<MessagePreset>>
    @Query("SELECT * FROM profile_presets WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultProfile(): ProfilePreset?
    @Query("SELECT * FROM message_presets WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultMessage(): MessagePreset?
    @Query("SELECT * FROM profile_presets WHERE id = :id")
    suspend fun profile(id: String): ProfilePreset?
    @Query("SELECT * FROM message_presets WHERE id = :id")
    suspend fun message(id: String): MessagePreset?
    @Query("SELECT EXISTS(SELECT 1 FROM local_state WHERE `key` = 'preset_import')")
    suspend fun imported(): Boolean
    @Upsert suspend fun put(value: ProfilePreset)
    @Upsert suspend fun put(value: MessagePreset)
    @Upsert suspend fun put(value: LocalState)
    @Query("UPDATE profile_presets SET isDefault = 0") suspend fun clearProfileDefault()
    @Query("UPDATE message_presets SET isDefault = 0") suspend fun clearMessageDefault()
    @Query("DELETE FROM profile_presets WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM message_presets WHERE id = :id") suspend fun deleteMessage(id: String)
}

@Database(entities = [ProfilePreset::class, MessagePreset::class, LocalState::class], version = 1, exportSchema = true)
abstract class PresetDatabase : RoomDatabase() {
    abstract fun presets(): PresetDao
}
