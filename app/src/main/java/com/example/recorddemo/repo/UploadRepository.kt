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

            // ---- Prepare multipart body ----
            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaTypeOrNull())
            )

            // Metadata JSON
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(audioFile.createdAt))
            val metaJson = JSONObject().apply {
                put("id", audioFile.id)
                put("lat", audioFile.latitude)
                put("lng", audioFile.longitude)
                put("fault_level", JSONObject.NULL)
                put("confidence", JSONObject.NULL)
                put("description", audioFile.fileName)
                put("captured_at", timestamp)
                put("belt_id", JSONObject.NULL)
                put("section_id", JSONObject.NULL)
            }

            val metadataBody: RequestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                metaJson.toString()
            )

            // ---- Make request ----
            val response = api.uploadAudio(filePart, metadataBody)

            if (response.isSuccessful) {
                Log.i(TAG, "✅ Uploaded successfully: ${audioFile.fileName}")

                dao.update(audioFile.copy(
                    uploaded = true,
                    uploadAttempts = audioFile.uploadAttempts + 1,
                    lastError = null
                ))
                return@withContext true
            } else {
                Log.w(TAG, "❌ Server responded with ${response.code()}")
                dao.update(audioFile.copy(
                    uploaded = false,
                    uploadAttempts = audioFile.uploadAttempts + 1,
                    lastError = "HTTP ${response.code()}"
                ))
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            dao.update(audioFile.copy(
                uploaded = false,
                uploadAttempts = audioFile.uploadAttempts + 1,
                lastError = e.message
            ))
            return@withContext false
        }
    }
}
