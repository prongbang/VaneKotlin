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

    @Test
    fun requestBodyHelpersBuildTextAndFormRequests() = runBlocking {
        var capturedRequest: VaneRequest? = null
        val session = VaneSession(
            configuration = testConfig(),
            transportExecutor = { request ->
                capturedRequest = request
                VaneResponse(
                    statusCode = 204u,
                    headers = emptyMap(),
                    body = ByteArray(0),
                    isSuccess = true,
                    url = "test://synthetic"
                )
            }
        )

        session.request("https://example.com/post", HttpMethod.POST)
            .textBody("hello")
            .execute()

        assertEquals("text/plain; charset=utf-8", capturedRequest?.headers?.get("Content-Type"))
        assertEquals("hello", String(capturedRequest?.body ?: ByteArray(0)))

        session.request("https://example.com/post", HttpMethod.POST)
            .formBody(mapOf("space" to "hello world", "token" to "a&b"))
            .execute()

        assertEquals("application/x-www-form-urlencoded", capturedRequest?.headers?.get("Content-Type"))
        assertEquals("space=hello+world&token=a%26b", String(capturedRequest?.body ?: ByteArray(0)))
    }

    @Test
    fun responseValidationHelpersThrowOnUnexpectedStatus() {
        val response = VaneResponse(
            statusCode = 404u,
            headers = emptyMap(),
            body = "missing".toByteArray(),
            isSuccess = false,
            url = "https://example.com/missing"
        )

        assertEquals("missing", response.text)
        try {
            response.validateStatus()
        } catch (error: VaneHttpException) {
            assertEquals(404u.toUShort(), error.statusCode)
            return
        }

        assertTrue("Expected VaneHttpException", false)
    }
}
