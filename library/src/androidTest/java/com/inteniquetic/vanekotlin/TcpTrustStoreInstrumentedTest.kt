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
 * `example.com` still staples OCSP, so it exercises the stapled path. The
 * no-staple path — certificates from CAs that retired OCSP, which Android
 * reports as an undeterminable rather than revoked status — is covered by
 * [TcpRevocationInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class TcpTrustStoreInstrumentedTest {

    /**
     * The HTTP/3 counterpart, guarding the other half of platform trust on
     * Android: quiche/BoringSSL gets its anchors from `load_platform_roots`,
     * which reads a CA directory rather than going through JNI. A directory
     * that exists but yields no certificates would leave this with zero trust
     * anchors, so this fails if that candidate list ever stops resolving.
     */
    @Test
    fun http3RequestVerifiesAgainstPlatformCaDirectory() = runBlocking {
        val config = VaneConfigurationBuilder().http3Only().timeout(30u).build()
        val client = createVaneClient(config)

        var last: Throwable? = null
        repeat(3) {
            runCatching { client.getRequest("https://cloudflare-quic.com/") }
                .onSuccess { response ->
                    assertEquals(200, response.statusCode.toInt())
                    return@runBlocking
                }
                .onFailure { last = it }
        }
        throw AssertionError("HTTP/3 request never succeeded; last error: ${last?.message}")
    }

    /**
     * The trust store must still refuse what it should. Deliberately paired
     * with the accept cases above: a verifier wired up wrongly enough to
     * accept everything would pass those and fail this.
     */
    @Test
    fun selfSignedCertificateIsRejected() = runBlocking {
        val config = VaneConfigurationBuilder().http1Only().timeout(30u).build()
        val message = runCatching {
            createVaneClient(config).getRequest("https://self-signed.badssl.com/")
        }.exceptionOrNull()?.message
        assertTrue(
            "Expected an untrusted-certificate failure, got: ${message ?: "a successful response"}",
            message?.contains("UnknownIssuer") == true ||
                message?.contains("invalid peer certificate") == true
        )
    }

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
