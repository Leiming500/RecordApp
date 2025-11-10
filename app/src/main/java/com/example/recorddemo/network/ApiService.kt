// ApiService.kt
package com.example.recorddemo.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @Multipart
    @POST("api/v1/faults/upload") // ← 路径修正
    suspend fun uploadAudio(
        @Part("sensor_id") sensorId: RequestBody,
        @Part("level") level: RequestBody,
        @Part("latitude") latitude: RequestBody?,   // 可选
        @Part("longitude") longitude: RequestBody?, // 可选
        @Part("note") note: RequestBody?,           // 可选
        @Part file: MultipartBody.Part              // 字段名必须是 file
    ): Response<ResponseBody>
}
