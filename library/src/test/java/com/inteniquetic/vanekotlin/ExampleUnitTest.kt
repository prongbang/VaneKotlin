package com.inteniquetic.vanekotlin

import kotlinx.coroutines.runBlocking
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    val config = VaneConfigurationBuilder()
        .baseUrl("http://127.0.0.1:8000")
        .defaultHeaders(mapOf("Authorization" to "Bearer token"))
        .timeout(30u)
        .build()
    private val session = VaneSession(config)

    @Test
    fun get() = runBlocking {
        val response = session.request("/get")
            .header("Accept", "application/json")
            .queryParam("page", "1")
            .responseString()
        print("response: $response")
    }
}