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
                response.copy(headers = response.headers + VaneHeader("x-intercepted", "true"))
            },
            errorInterceptors = listOf {
                VaneResponse(
                    statusCode = 299u,
                    headers = emptyList(),
                    body = "synthetic".toByteArray(),
                    bodyFilePath = null,
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
        assertEquals("true", response.headerMap["x-intercepted"])
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
                headers = emptyList(),
                body = ByteArray(0),
                bodyFilePath = null,
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
    fun interceptorsCanBeAddedAndClearedAfterSessionCreation() = runBlocking {
        var capturedRequest: VaneRequest? = null
        val session = VaneSession(
            configuration = testConfig(),
            transportExecutor = { request ->
                capturedRequest = request
                VaneResponse(
                    statusCode = 204u,
                    headers = emptyList(),
                    body = ByteArray(0),
                    bodyFilePath = null,
                    isSuccess = true,
                    url = request.url
                )
            }
        )

        session
            .addRequestInterceptor { request ->
                request.copy(headers = request.headers + ("x-late" to "1"))
            }
            .addResponseInterceptor { response ->
                response.copy(headers = response.headers + VaneHeader("x-response", "1"))
            }

        val response = session.get("https://example.com/late")

        assertEquals("1", capturedRequest?.headers?.get("x-late"))
        assertEquals("1", response.headerMap["x-response"])

        session.clearInterceptors()
        session.get("https://example.com/clear")

        assertEquals(null, capturedRequest?.headers?.get("x-late"))
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
                    headers = emptyList(),
                    body = ByteArray(0),
                    bodyFilePath = null,
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
    fun requestFileHelpersPassNativePaths() = runBlocking {
        var capturedRequest: VaneRequest? = null
        val session = VaneSession(
            configuration = testConfig(),
            transportExecutor = { request ->
                capturedRequest = request
                VaneResponse(
                    statusCode = 200u,
                    headers = emptyList(),
                    body = ByteArray(0),
                    bodyFilePath = request.responseBodyPath,
                    isSuccess = true,
                    url = request.url
                )
            }
        )

        session.uploadFile("https://example.com/upload", "/tmp/input.bin")

        assertEquals("POST", capturedRequest?.method)
        assertEquals("/tmp/input.bin", capturedRequest?.bodyFilePath)
        assertEquals(null, capturedRequest?.body)

        session.download("https://example.com/report", "/tmp/report.bin")

        assertEquals("GET", capturedRequest?.method)
        assertEquals("/tmp/report.bin", capturedRequest?.responseBodyPath)
    }

    @Test
    fun multipartAndProgressHelpersDecorateRequests() = runBlocking {
        VaneProgressBridge.create = { 42u }
        VaneProgressBridge.snapshot = {
            VaneProgressSnapshot(
                uploadSent = 4u,
                uploadTotal = 8u,
                downloadReceived = 2u,
                downloadTotal = 10u,
                done = true
            )
        }
        VaneProgressBridge.free = {}
        var capturedRequest: VaneRequest? = null
        var uploadProgress: Pair<ULong, ULong>? = null
        var downloadProgress: Pair<ULong, ULong>? = null
        val session = VaneSession(
            configuration = testConfig(),
            requestInterceptors = listOf { request ->
                capturedRequest = request
                request
            },
            transportExecutor = { request ->
                capturedRequest = request
                VaneResponse(
                    statusCode = 200u,
                    headers = emptyList(),
                    body = ByteArray(0),
                    bodyFilePath = request.responseBodyPath,
                    isSuccess = true,
                    url = request.url
                )
            }
        )

        session.request("https://example.com/upload", HttpMethod.POST)
            .multipart(
                fields = mapOf("title" to "avatar"),
                files = listOf(
                    VaneMultipartFile(
                        fieldName = "photo",
                        bytes = byteArrayOf(1, 2, 3),
                        fileName = "me.jpg",
                        contentType = "image/jpeg"
                    )
                )
            )
            .downloadToFile("/tmp/result.json")
            .onUploadProgress { sent, total -> uploadProgress = sent to total }
            .onDownloadProgress { received, total -> downloadProgress = received to total }
            .execute()

        val contentType = capturedRequest?.headers?.get("Content-Type").orEmpty()
        val body = String(capturedRequest?.body ?: ByteArray(0))

        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        assertTrue(body.contains("name=\"title\""))
        assertTrue(body.contains("name=\"photo\"; filename=\"me.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertEquals("/tmp/result.json", capturedRequest?.responseBodyPath)
        assertTrue(capturedRequest?.progressId != null)
        assertTrue(uploadProgress != null)
        assertTrue(downloadProgress != null)
        VaneProgressBridge.reset()
    }

    @Test
    fun responseValidationHelpersThrowOnUnexpectedStatus() {
        val response = VaneResponse(
            statusCode = 404u,
            headers = emptyList(),
            body = "missing".toByteArray(),
            bodyFilePath = null,
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
