package com.inteniquetic.vanekotlin.benchmark

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.util.concurrent.TimeUnit

interface RetrofitService {
    @GET("/get")
    suspend fun get(): Any

    @POST("/post")
    suspend fun post(@Body body: Map<String, String>): Any

    @PUT("/put")
    suspend fun put(@Body body: Map<String, String>): Any

    @PATCH("/patch")
    suspend fun patch(@Body body: Map<String, String>): Any

    @DELETE("/delete")
    suspend fun delete(): Any
}

fun createRetrofitService(baseUrl: String = "http://127.0.0.1:8000"): RetrofitService {
    // Optional interceptor for adding common headers
    val headerInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer token")
            .addHeader("User-Agent", "RetrofitBenchmark/1.0")
            .build()
        chain.proceed(request)
    }

    val client = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    return retrofit.create(RetrofitService::class.java)
}