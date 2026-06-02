package com.inteniquetic.vanekotlin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaneSessionInterceptorTest {
    private fun testConfig() = VaneClientConfig(
        baseUrl = null,
        defaultHeaders = emptyMap(),
        dnsOverrides = emptyMap(),
        certificatePins = emptyMap(),
        cookiesEnabled = false,
        connectionPoolEnabled = false,
        maxIdleConnections = 4u,
        connectionIdleTimeoutSeconds = 30u,
        retryMaxAttempts = 1u,
        retryInitialDelayMillis = 100u,
        retryMaxDelayMillis = 1_000u,
        retryUnsafeMethods = false,
        maxRequestBodyBytes = 64u * 1024u * 1024u,
        maxResponseBodyBytes = 64u * 1024u * 1024u,
        timeoutSeconds = 30u,
        followRedirects = true,
        userAgent = "Vane/Test",
        protocolMode = VaneProtocolMode.HTTP3_ONLY,
        proxyUrl = null,
        proxyAuthorization = null
    )

    @Test
    fun interceptorsApplyToRequestBuilderPaths() = runBlocking {
        val session = VaneSession(
            configuration = testConfig(),
            requestInterceptors = listOf { request ->
                request.copy(
                    headers = request.headers + ("Authorization" to "Bearer intercepted"),
                    queryParams = request.queryParams + ("source" to "interceptor")
                )
            },
            responseInterceptors = listOf { response ->
                response.copy(headers = response.headers + ("x-intercepted" to "true"))
            },
            errorInterceptors = listOf {
                VaneResponse(
                    statusCode = 299u,
                    headers = emptyMap(),
                    body = "synthetic".toByteArray(),
                    isSuccess = true,
                    url = "interceptor://synthetic"
                )
            }
        ) {
            throw IllegalArgumentException("transport failed")
        }

        val response = session.request("http://example.com/get")
            .header("Accept", "application/json")
            .execute()

        assertEquals(299u.toUShort(), response.statusCode)
        assertEquals("true", response.headers["x-intercepted"])
        assertEquals("synthetic", String(response.body))
    }

    @Test
    fun requestInterceptorFailuresPropagate() = runBlocking {
        val session = VaneSession(
            configuration = testConfig(),
            requestInterceptors = listOf {
                throw IllegalStateException("blocked")
            }
        ) {
            VaneResponse(
                statusCode = 200u,
                headers = emptyMap(),
                body = ByteArray(0),
                isSuccess = true,
                url = "test://unused"
            )
        }

        try {
            session.get("http://example.com/get")
        } catch (error: IllegalStateException) {
            assertEquals("blocked", error.message)
            return@runBlocking
        }

        assertTrue("Expected request interceptor to throw", false)
    }
}
