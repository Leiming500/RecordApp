package com.example.recorddemo

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("upload")  // 替换为你服务器的实际端点，如 /api/upload
    fun uploadFiles(@Part files: List<MultipartBody.Part>): Call<Void>  // 如果服务器返回 JSON，用 ResponseBody 或自定义类替换 Void
}