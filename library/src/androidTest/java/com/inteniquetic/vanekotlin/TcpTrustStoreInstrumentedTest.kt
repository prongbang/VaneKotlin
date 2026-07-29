package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the Android trust-store initialization the TCP transport depends on.
 *
 * `http1Only` is the point: it removes HTTP/3 from the equation, so the request
 * can only succeed by completing a real TLS handshake verified through the
 * platform trust store over JNI. Nothing here initializes anything — that is
 * the assertion. If `VaneInitProvider` stops running, or the
 * `org.rustls.platformverifier` classes stop being packaged, this test fails
 * where an HTTP/3 test would still pass.
 *
 * Deliberately not a Cloudflare-fronted host: those staple an OCSP response
 * that `rustls-platform-verifier` forwards to Android, which answers `Revoked`
 * for it. That is upstream behaviour in the verifier, unrelated to this
 * wiring, and would make the test assert the wrong thing.
 */
@RunWith(AndroidJUnit4::class)
class TcpTrustStoreInstrumentedTest {

    @Test
    fun http1OnlyRequestVerifiesThroughPlatformTrustStore() = runBlocking {
        val config = VaneConfigurationBuilder()
            .http1Only()
            .timeout(30u)
            .build()
        val client = createVaneClient(config)

        // Retried because servers that close a keep-alive connection without a
        // TLS close_notify make rustls fail the read, which shows up here as an
        // occasional transport error. A missing trust-store init is not like
        // that — it fails every attempt identically — so retrying cannot hide
        // the regression this test exists to catch.
        var last: Throwable? = null
        repeat(3) {
            runCatching { client.getRequest("https://example.com/") }
                .onSuccess { response ->
                    assertEquals(200, response.statusCode.toInt())
                    assertTrue(response.isSuccess)
                    return@runBlocking
                }
                .onFailure { last = it }
        }
        throw AssertionError("TCP request never succeeded; last error: ${last?.message}")
    }
}
