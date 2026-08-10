package com.inteniquetic.vanekotlin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaneCancelTokenTest {
    private fun testConfig() = VaneClientConfig(
        baseUrl = null,
        defaultHeaders = emptyMap(),
        dnsOverrides = emptyMap(),
        certificatePins = emptyMap(),
        cookiesEnabled = false,
        cookiePersistencePath = null,
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
    fun tokenCreatesEagerlyAndCancelReachesTheCore() {
        val cancelled = mutableListOf<ULong>()
        VaneCancelTokenBridge.create = { 7u }
        VaneCancelTokenBridge.cancel = { id -> cancelled += id }
        VaneCancelTokenBridge.free = {}
        try {
            val token = VaneCancelToken()

            assertEquals(7u.toULong(), token.id)
            assertFalse(token.isCancelled)

            token.cancel()

            assertTrue(token.isCancelled)
            assertEquals(listOf(7u.toULong()), cancelled)
        } finally {
            VaneCancelTokenBridge.reset()
        }
    }

    @Test
    fun doubleCloseAndCancelAfterCloseAreSafe() {
        val freed = mutableListOf<ULong>()
        VaneCancelTokenBridge.create = { 9u }
        VaneCancelTokenBridge.cancel = {}
        VaneCancelTokenBridge.free = { id -> freed += id }
        try {
            val token = VaneCancelToken()

            token.close()
            token.close()
            token.cancel()

            // The wrapper forwards every call; the core registry is what makes
            // the repeats no-ops, and ids are never reused.
            assertEquals(listOf(9u.toULong(), 9u.toULong()), freed)
            assertTrue(token.isCancelled)
        } finally {
            VaneCancelTokenBridge.reset()
        }
    }

    @Test
    fun builderCarriesCancelTokenIdOnRequests() = runBlocking {
        VaneCancelTokenBridge.create = { 42u }
        VaneCancelTokenBridge.cancel = {}
        VaneCancelTokenBridge.free = {}
        try {
            var capturedRequest: VaneRequest? = null
            val session = VaneSession(
                configuration = testConfig(),
                transportExecutor = { request ->
                    capturedRequest = request
                    VaneResponse(
                        statusCode = 204u,
                        headers = emptyMap(),
                        body = ByteArray(0),
                        bodyFilePath = null,
                        isSuccess = true,
                        url = request.url
                    )
                }
            )

            VaneCancelToken().use { token ->
                session.request("https://example.com/slow")
                    .cancelToken(token)
                    .execute()

                assertEquals(token.id, capturedRequest?.cancelTokenId)
            }

            session.request("https://example.com/plain").execute()

            assertEquals(null, capturedRequest?.cancelTokenId)
        } finally {
            VaneCancelTokenBridge.reset()
        }
    }
}
