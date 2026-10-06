package io.github.springthief1123.lovelyspace.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @Query("SELECT value FROM local_state WHERE `key` = :key")
    suspend fun state(key: String): String?
    @Upsert suspend fun put(value: LocalState)
    @Query("UPDATE profile_presets SET isDefault = 0") suspend fun clearProfileDefault()
    @Query("UPDATE message_presets SET isDefault = 0") suspend fun clearMessageDefault()
    @Query("DELETE FROM profile_presets WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM message_presets WHERE id = :id") suspend fun deleteMessage(id: String)
}

@Database(
    entities = [ProfilePreset::class, MessagePreset::class, LocalState::class, SearchPreset::class, RoomPreference::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(SearchPresetConverters::class)
abstract class PresetDatabase : RoomDatabase() {
    abstract fun presets(): PresetDao
    abstract fun searchPresets(): SearchPresetDao
    abstract fun roomPreferences(): RoomPreferenceDao

    companion object {
        // 既存のプロフィール、募集文、初回取り込み済みの記録はそのまま保持する。
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `search_presets` (
                        `id` TEXT NOT NULL, `label` TEXT NOT NULL, `genreKey` TEXT NOT NULL,
                        `criteria_name` TEXT NOT NULL, `criteria_message` TEXT NOT NULL,
                        `criteria_excluded` TEXT NOT NULL, `criteria_keywordMode` TEXT NOT NULL,
                        `criteria_gender` TEXT, `criteria_minAge` INTEGER, `criteria_maxAge` INTEGER,
                        `criteria_includeUnknownAge` INTEGER NOT NULL, `criteria_area` TEXT,
                        `criteria_waitingOnly` INTEGER, `criteria_publicOnly` INTEGER,
                        `criteria_sort` TEXT NOT NULL, PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `search_presets` ADD COLUMN `criteria_text` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `room_preferences` (
                        `host` TEXT NOT NULL, `roomId` INTEGER NOT NULL, `genreKey` TEXT NOT NULL,
                        `favorite` INTEGER NOT NULL, `hidden` INTEGER NOT NULL,
                        `snapshotStatus` TEXT NOT NULL, `snapshotAction` TEXT NOT NULL,
                        `snapshotElapsedSeconds` INTEGER, `snapshotName` TEXT,
                        `snapshotGender` TEXT NOT NULL, `snapshotAge` INTEGER,
                        `snapshotArea` TEXT, `snapshotMessage` TEXT NOT NULL,
                        `identityName` TEXT, `identityGender` TEXT, `identityAge` INTEGER,
                        `stale` INTEGER NOT NULL, PRIMARY KEY(`host`, `roomId`)
                    )
                """.trimIndent())
            }
        }
    }
}
