package com.example.recorddemo.repo

import android.util.Log
import com.example.recorddemo.data.AudioFile
import com.example.recorddemo.data.AudioFileDao
import com.example.recorddemo.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class UploadRepository(
    private val api: ApiService,
    private val dao: AudioFileDao
) {

    companion object {
        private const val TAG = "UploadRepository"
    }

    suspend fun uploadWithRetry(audioFile: AudioFile, maxRetries: Int = 3): Boolean {
        var attempt = 0
        var success = false

        while (attempt < maxRetries && !success) {
            attempt++
            try {
                success = uploadFile(audioFile)
            } catch (e: Exception) {
                Log.e(TAG, "Upload attempt $attempt failed: ${e.message}", e)
                dao.update(audioFile.copy(
                    uploadAttempts = audioFile.uploadAttempts + 1,
                    lastError = e.message
                ))
            }
        }

        return success
    }
    private suspend fun uploadFile(audioFile: AudioFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(audioFile.filePath)
            if (!file.exists()) {
                Log.w(TAG, "File not found: ${audioFile.filePath}")
                dao.update(audioFile.copy(
                    uploaded = false,
                    lastError = "File not found"
                ))
                return@withContext false
            }

            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaTypeOrNull())
            )


            val sensorId = RequestBody.create("text/plain".toMediaTypeOrNull(), "Sensor_001")
            val level = RequestBody.create("text/plain".toMediaTypeOrNull(), "normal")
            val lat = RequestBody.create("text/plain".toMediaTypeOrNull(), audioFile.latitude.toString())
            val lon = RequestBody.create("text/plain".toMediaTypeOrNull(), audioFile.longitude.toString())
            val note = RequestBody.create("text/plain".toMediaTypeOrNull(), audioFile.fileName)


            val response = api.uploadAudio(sensorId, level, lat, lon, note, filePart)

            if (response.isSuccessful) {
                Log.i(TAG, "✅ Uploaded successfully: ${audioFile.fileName}")
                dao.update(
                    audioFile.copy(
                        uploaded = true,
                        uploadAttempts = audioFile.uploadAttempts + 1,
                        lastError = null
                    )
                )
                true
            } else {
                Log.w(TAG, "❌ Server responded with ${response.code()}")
                dao.update(
                    audioFile.copy(
                        uploaded = false,
                        uploadAttempts = audioFile.uploadAttempts + 1,
                        lastError = "HTTP ${response.code()}"
                    )
                )
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            dao.update(
                audioFile.copy(
                    uploaded = false,
                    uploadAttempts = audioFile.uploadAttempts + 1,
                    lastError = e.message
                )
            )
            false
        }
    }
}
