import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: AudioFileEntity)

    @Update
    suspend fun update(file: AudioFileEntity)

    @Delete
    suspend fun delete(file: AudioFileEntity)

    @Query("SELECT * FROM audio_files ORDER BY timestamp DESC")
    fun getAllFilesFlow(): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM audio_files WHERE uploadStatus = 0 ORDER BY timestamp ASC")
    fun getPendingUploadFiles(): Flow<List<AudioFileEntity>>
}
