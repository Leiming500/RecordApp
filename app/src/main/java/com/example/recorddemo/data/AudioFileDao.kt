package com.example.recorddemo.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audioFile: AudioFile): Long

    @Query("SELECT * FROM audio_files WHERE uploaded = 0 ORDER BY createdAt ASC")
    fun getPendingUploadsFlow(): Flow<List<AudioFile>>

    @Update
    suspend fun update(audioFile: AudioFile)

    @Query("SELECT * FROM audio_files ORDER BY createdAt DESC")
    fun getAllFilesFlow(): Flow<List<AudioFile>>

    @Delete
    suspend fun delete(audioFile: AudioFile)

}
