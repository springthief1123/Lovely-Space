package io.github.springthief1123.lovelyspace.data

import androidx.room.withTransaction
import io.github.springthief1123.lovelyspace.core.messageWidth
import io.github.springthief1123.lovelyspace.core.validProfile
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresetRepository(private val db: PresetDatabase, private val settings: SettingsRepository) {
    private val dao = db.presets()
    private val importLock = Mutex()
    val profiles = dao.profiles()
    val messages = dao.messages()

    /** 前回入力は削除せず、一度だけプリセットへ取り込む。全削除後も再生成しない。 */
    suspend fun initialize() = importLock.withLock {
        if (dao.imported()) return@withLock
        val profile = settings.lastEntryProfile.first()
        val details = settings.lastRoomDetails.first()
        db.withTransaction {
            if (profile != null && validProfile(profile.name, profile.sex, profile.years)) {
                dao.put(ProfilePreset(id = "imported-profile", label = "前回のプロフィール", name = profile.name,
                    sex = profile.sex, years = profile.years, prefecture = details.prefecture, isDefault = true))
            }
            if (details.message.isNotBlank() && messageWidth(details.message) <= 500) {
                dao.put(MessagePreset(id = "imported-message", label = "前回の待機メッセージ", message = details.message, isDefault = true))
            }
            dao.put(LocalState("preset_import", "done"))
        }
    }

    suspend fun defaultProfile(): ProfilePreset? { initialize(); return dao.defaultProfile() }
    suspend fun defaultMessage(): MessagePreset? { initialize(); return dao.defaultMessage() }

    suspend fun save(profile: ProfilePreset) {
        require(profile.label.isNotBlank() && validProfile(profile.name, profile.sex, profile.years))
        require(profile.prefecture == null || profile.prefecture in 1..48)
        initialize()
        db.withTransaction {
            if (profile.isDefault) dao.clearProfileDefault()
            dao.put(profile.copy(label = profile.label.trim(), name = profile.name.trim()))
        }
    }

    suspend fun save(message: MessagePreset) {
        val normalized = message.message.replace('\n', ' ').trim()
        require(message.label.isNotBlank() && normalized.isNotBlank() && messageWidth(normalized) <= 500)
        initialize()
        db.withTransaction {
            if (message.isDefault) dao.clearMessageDefault()
            dao.put(message.copy(label = message.label.trim(), message = normalized))
        }
    }

    suspend fun setDefaultProfile(id: String) = db.withTransaction {
        val profile = dao.profile(id) ?: return@withTransaction
        dao.clearProfileDefault()
        dao.put(profile.copy(isDefault = true))
    }
    suspend fun setDefaultMessage(id: String) = db.withTransaction {
        val message = dao.message(id) ?: return@withTransaction
        dao.clearMessageDefault()
        dao.put(message.copy(isDefault = true))
    }
    suspend fun deleteProfile(id: String) = dao.deleteProfile(id)
    suspend fun deleteMessage(id: String) = dao.deleteMessage(id)
}
