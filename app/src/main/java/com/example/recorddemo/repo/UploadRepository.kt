package com.example.recorddemo.repo

import android.util.Log
import com.example.recorddemo.data.AudioFile
import com.example.recorddemo.data.AudioFileDao
import com.example.recorddemo.network.ApiService
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class UploadRepository(
    private val api: ApiService,
    private val dao: AudioFileDao
) {
    companion object {
        private const val TAG = "UploadRepository"
    }

    /**
     * 上传单个文件，最多重试 maxRetries 次，失败会更新 dao 的 lastError 与 uploadAttempts。
     * 返回 true 表示上传成功。
     */
    suspend fun uploadWithRetry(entity: AudioFile, maxRetries: Int = 3): Boolean {
        var attempt = 0
        var lastThrowable: Throwable? = null

        while (attempt < maxRetries) {
            attempt++
            try {
                val f = File(entity.filePath)
                if (!f.exists()) {
                    // 标记为已上传（或删除记录），这里先设为 uploaded = true 并返回 false
                    dao.update(entity.copy(uploaded = true, lastError = "file_missing"))
                    return false
                }

                val reqBody = f.asRequestBody("audio/wav".toMediaType())
                val part = MultipartBody.Part.createFormData("audio", entity.fileName, reqBody)

                // metadata: JSON string or any format your backend expects
                val metaJson = """{"fileName":"${entity.fileName}","lat":${entity.latitude},"lon":${entity.longitude},"ts":${entity.createdAt}}"""
                val metaBody = metaJson.toRequestBody("application/json".toMediaType())

                val resp = api.uploadAudio(part, metaBody)
                if (resp.isSuccessful) {
                    // 上传成功，更新 DB
                    dao.update(entity.copy(uploaded = true, uploadAttempts = attempt, lastError = null))
                    return true
                } else {
                    val err = "http_${resp.code()}:${resp.message()}"
                    dao.update(entity.copy(uploadAttempts = attempt, lastError = err))
                    lastThrowable = IOException(err)
                }
            } catch (t: Throwable) {
                lastThrowable = t
                dao.update(entity.copy(uploadAttempts = attempt, lastError = t.message))
                Log.w(TAG, "upload attempt $attempt failed: ${t.message}")
            }

            delay(1000L * attempt) // 指数退避：1s,2s,3s...
        }

        // 最终失败
        Log.e(TAG, "upload failed after $attempt attempts", lastThrowable)
        return false
    }
}
