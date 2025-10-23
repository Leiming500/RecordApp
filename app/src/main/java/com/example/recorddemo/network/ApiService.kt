package com.example.recorddemo.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    // 请根据你真实的后端接口调整路径和字段名
    @Multipart
    @POST("upload")
    suspend fun uploadAudio(
        @Part file: MultipartBody.Part,
        @Part("metadata") metadata: RequestBody
    ): Response<ResponseBody>
}