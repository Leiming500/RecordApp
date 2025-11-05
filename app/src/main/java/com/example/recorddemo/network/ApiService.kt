package com.example.recorddemo.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // Upload audio file with metadata
    @Multipart
    @POST("api/v1/ingest/audio")
    suspend fun uploadAudio(
        @Part file: MultipartBody.Part,
        @Part("metadata") metadata: RequestBody,
        @Header("x-api-key") apiKey: String = "IT-PRO-UON-2025"
    ): Response<ResponseBody>
}
