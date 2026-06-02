package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inteniquetic.vanekotlin.benchmark.BenchmarkRunner
import com.inteniquetic.vanekotlin.benchmark.createRetrofitService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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

    companion object {
        private const val BASE_URL_ARG = "VANE_TEST_BASE_URL"

        private fun baseUrlOrSkip(): String {
            val args = InstrumentationRegistry.getArguments()
            val baseUrl = args.getString(BASE_URL_ARG)
                ?: args.getString("baseUrl")
                ?: System.getenv(BASE_URL_ARG)
                ?: ""
            assumeTrue(
                "Set $BASE_URL_ARG=https://<http3-enabled-host> as an instrumentation argument to run live Vane tests.",
                baseUrl.startsWith("https://")
            )
            return baseUrl.trimEnd('/')
        }
    }

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.inteniquetic.vanekotlin.test", appContext.packageName)
    }

    @Test
    fun get() = runBlocking {
        val config = VaneConfigurationBuilder()
            .baseUrl(baseUrlOrSkip())
            .defaultHeaders(mapOf("Authorization" to "Bearer token"))
            .http3Only()
            .timeout(30u)
            .build()
        val session = VaneSession(config)

        val response = session.request("/get")
            .header("Accept", "application/json")
            .queryParam("page", "1")
            .execute()

        assertTrue(response.isSuccess)
        assertTrue(String(response.body).contains("Bearer token"))
        assertTrue(String(response.body).contains("page"))
    }

    @Test
    fun benchmark() = runBlocking {
        val baseUrl = baseUrlOrSkip()
        val config = VaneConfigurationBuilder()
            .baseUrl(baseUrl)
            .defaultHeaders(mapOf("Authorization" to "Bearer token"))
            .http3Only()
            .timeout(30u)
            .build()
        val vane = createVaneClient(config)
        val retrofit = createRetrofitService(baseUrl)
        BenchmarkRunner.benchmarkRetrofit(retrofit)
        BenchmarkRunner.benchmarkVane(vane)
    }
}
