package com.inteniquetic.vanekotlin

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    constructor() {
        Vane.initialize()
    }

    val config = VaneConfigurationBuilder()
        .baseUrl("http://192.168.0.180:8000")
        .defaultHeaders(mapOf("Authorization" to "Bearer token"))
        .timeout(30u)
        .build()
    private val session = VaneSession(config)

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.inteniquetic.vanekotlin.test", appContext.packageName)
    }

    @Test
    fun get() = runBlocking {
        val response = session.request("/get")
            .header("Accept", "application/json")
            .queryParam("page", "1")
            .responseString()
        print("response: $response")
    }
}