package io.github.springthief1123.lovelyspace.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "room_preferences", primaryKeys = ["host", "roomId"])
data class RoomPreference(
    val host: String,
    val roomId: Long,
    val genreKey: String,
    val favorite: Boolean,
    val hidden: Boolean,
    val snapshotStatus: String,
    val snapshotAction: String,
    val snapshotElapsedSeconds: Long?,
    val snapshotName: String?,
    val snapshotGender: String,
    val snapshotAge: Int?,
    val snapshotArea: String?,
    val snapshotMessage: String,
    val identityName: String?,
    val identityGender: String?,
    val identityAge: Int?,
    val stale: Boolean = false,
)

@Dao
interface RoomPreferenceDao {
    @Query("SELECT * FROM room_preferences ORDER BY favorite DESC, hidden DESC, host, roomId")
    fun preferences(): Flow<List<RoomPreference>>

    @Query("SELECT * FROM room_preferences WHERE host = :host AND roomId = :roomId")
    suspend fun preference(host: String, roomId: Long): RoomPreference?

    @Upsert
    suspend fun put(value: RoomPreference)

    @Query("UPDATE room_preferences SET favorite = 0 WHERE host = :host AND roomId = :roomId")
    suspend fun clearFavorite(host: String, roomId: Long)

    @Query("UPDATE room_preferences SET hidden = 0 WHERE host = :host AND roomId = :roomId")
    suspend fun clearHidden(host: String, roomId: Long)

    @Query("DELETE FROM room_preferences WHERE host = :host AND roomId = :roomId AND favorite = 0 AND hidden = 0")
    suspend fun deleteUnused(host: String, roomId: Long)
}
