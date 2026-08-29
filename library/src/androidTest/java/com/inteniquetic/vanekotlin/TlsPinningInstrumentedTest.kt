package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Certificate pinning and chain rejection, on a device, through the packaged
 * `libvane.so`. Written 2026-08-29 to close a coverage asymmetry rather than a
 * device one: the iOS side had been checking expired and wrong-host chains and
 * pin mismatches on both transports since the first real-hardware run, while
 * Android had no pinning test at all — so "TLS tests pass on real devices" did
 * not mean the same thing on the two platforms.
 *
 * The accept case pins against [TcpCustomRootTrustInstrumentedTest]'s local
 * server instead of a public host on purpose. A pin over a live leaf is a pin
 * over a certificate that rotates, which would turn a passing test into a
 * calendar bomb; the local leaf's DER is a constant in this repo, so the
 * expected pin is computed from it here and can never drift. Reject cases can
 * safely use live hosts — a wrong pin stays wrong across any rotation.
 */
@RunWith(AndroidJUnit4::class)
class TlsPinningInstrumentedTest {

    @Test
    fun correctCertificatePinIsAcceptedOnTcp() = runBlocking {
        TcpCustomRootTrustInstrumentedTest.LocalTlsServer(
            TcpCustomRootTrustInstrumentedTest.SERVER_CERT_PEM,
            TcpCustomRootTrustInstrumentedTest.SERVER_KEY_PEM,
        ).use { server ->
            val client = createVaneClient(
                VaneConfigurationBuilder()
                    .http1Only()
                    .timeout(30u)
                    .customRootCertificates(listOf(TcpCustomRootTrustInstrumentedTest.CUSTOM_CA_PEM))
                    .certificatePins(mapOf("127.0.0.1" to listOf(expectedPin())))
                    .build()
            )
            val response = client.getRequest("https://127.0.0.1:${server.port}/")
            assertEquals(200, response.statusCode.toInt())
        }
    }

    /**
     * The control for the case above. Same server, same custom root — only the
     * pin changes, so a pass here can only be the pin check firing. Without it
     * the accept case would also pass against a build that ignored pins
     * entirely.
     */
    @Test
    fun wrongCertificatePinIsRejectedOnTcp() = runBlocking {
        TcpCustomRootTrustInstrumentedTest.LocalTlsServer(
            TcpCustomRootTrustInstrumentedTest.SERVER_CERT_PEM,
            TcpCustomRootTrustInstrumentedTest.SERVER_KEY_PEM,
        ).use { server ->
            val client = createVaneClient(
                VaneConfigurationBuilder()
                    .http1Only()
                    .timeout(30u)
                    .customRootCertificates(listOf(TcpCustomRootTrustInstrumentedTest.CUSTOM_CA_PEM))
                    .certificatePins(mapOf("127.0.0.1" to listOf(WRONG_PIN)))
                    .build()
            )
            assertPinMismatch(runCatching { client.getRequest("https://127.0.0.1:${server.port}/") })
        }
    }

    /**
     * The HTTP/3 half. Pin enforcement on QUIC runs through BoringSSL rather
     * than rustls, so a pin honoured on TCP says nothing about this path.
     * Asserting the *reason* is what keeps this from passing on a network
     * outage.
     */
    @Test
    fun wrongCertificatePinIsRejectedOnHttp3() = runBlocking {
        val client = createVaneClient(
            VaneConfigurationBuilder()
                .http3Only()
                .timeout(30u)
                .certificatePins(mapOf("cloudflare-quic.com" to listOf(WRONG_PIN)))
                .build()
        )
        assertPinMismatch(runCatching { client.getRequest("https://cloudflare-quic.com/") })
    }

    @Test
    fun expiredCertificateChainIsRejected() =
        assertChainRejected("https://expired.badssl.com/")

    @Test
    fun wrongHostCertificateChainIsRejected() =
        assertChainRejected("https://wrong.host.badssl.com/")

    private fun assertChainRejected(url: String) = runBlocking {
        val client = createVaneClient(
            VaneConfigurationBuilder().http1Only().timeout(30u).build()
        )
        val message = runCatching { client.getRequest(url) }.exceptionOrNull()?.message
        assertTrue(
            "Expected an untrusted-certificate failure for $url, got: " +
                (message ?: "a successful response"),
            message?.contains("invalid peer certificate") == true ||
                message?.contains("UnknownIssuer") == true ||
                message?.contains("NotValidForName") == true ||
                message?.contains("Expired") == true
        )
    }

    private fun assertPinMismatch(result: Result<VaneResponse>) {
        val message = result.exceptionOrNull()?.message
        assertTrue(
            "Expected a pin mismatch, got: " +
                (message ?: "a successful response with status ${result.getOrNull()?.statusCode}"),
            message?.contains("Certificate pin mismatch") == true
        )
    }

    /** `sha256-cert/` pins are over the leaf's DER — see `certificate_pin_values`. */
    private fun expectedPin(): String {
        val der = CertificateFactory.getInstance("X.509")
            .generateCertificate(
                TcpCustomRootTrustInstrumentedTest.SERVER_CERT_PEM.byteInputStream()
            )
            .encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return "sha256-cert/" + Base64.getEncoder().encodeToString(digest)
    }

    private companion object {
        const val WRONG_PIN = "sha256-cert/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
