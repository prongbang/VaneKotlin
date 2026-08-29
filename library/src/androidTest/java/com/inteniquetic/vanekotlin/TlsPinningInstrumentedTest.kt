package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
 * The TCP accept case pins against [TcpCustomRootTrustInstrumentedTest]'s
 * local server instead of a public host on purpose. A hard-coded pin over a
 * live leaf is a calendar bomb — it passes until the certificate renews; the
 * local leaf's DER is a constant in this repo, so the expected pin is computed
 * from it here and can never drift. Reject cases can safely use live hosts: a
 * wrong pin stays wrong across any rotation.
 *
 * HTTP/3 has no local server to pin against — the one here speaks TLS over
 * TCP, not QUIC — so [correctCertificatePinIsAcceptedOnHttp3] derives the
 * expected pin at runtime instead of hard-coding it, and is rotation-proof for
 * the same reason a constant is. See that test for what it additionally
 * assumes.
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
                .certificatePins(mapOf(H3_HOST to listOf(WRONG_PIN)))
                .build()
        )
        assertPinMismatch(runCatching { client.getRequest("https://$H3_HOST/") })
    }

    /**
     * The HTTP/3 accept case: a pin the peer really does match must let the
     * request through, or "rejects a wrong pin" above would also pass against
     * a build that rejected *every* pinned HTTP/3 request.
     *
     * The pin is derived at runtime from a TLS handshake to the same host over
     * TCP, which buys two things a hard-coded constant does not: it survives
     * certificate renewal, and it asserts that both transports are shown the
     * same leaf. What it assumes is exactly that — one origin certificate for
     * :443 whether the client arrives over TCP or QUIC. That holds for
     * Cloudflare today; if it ever stops holding, this fails as a pin mismatch
     * for a reason that is not a Vane bug, which is what the failure message
     * says so the next reader is not sent hunting.
     */
    @Test
    fun correctCertificatePinIsAcceptedOnHttp3() = runBlocking {
        val client = createVaneClient(
            VaneConfigurationBuilder()
                .http3Only()
                .timeout(30u)
                .certificatePins(mapOf(H3_HOST to listOf(livePinFor(H3_HOST))))
                .build()
        )
        val result = runCatching { client.getRequest("https://$H3_HOST/") }
        val failure = result.exceptionOrNull()
        if (failure?.message?.contains("Certificate pin mismatch") == true) {
            throw AssertionError(
                "HTTP/3 presented a different leaf than TCP for $H3_HOST. That is a " +
                    "changed assumption about the host, not necessarily a Vane defect — " +
                    "check whether the origin now serves per-transport certificates."
            )
        }
        val response = result.getOrThrow()
        assertEquals(200, response.statusCode.toInt())
        assertEquals(VaneHttpVersion.HTTP3, response.httpVersion)
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

    /**
     * The pin the host is presenting right now, read off a plain TCP TLS
     * handshake with the platform's own stack — nothing of Vane's is involved,
     * so this cannot agree with the code under test by sharing a bug with it.
     */
    private fun livePinFor(host: String): String {
        val socket = SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket
        return socket.use {
            it.startHandshake()
            pinOf(it.session.peerCertificates.first().encoded)
        }
    }

    /** `sha256-cert/` pins are over the leaf's DER — see `certificate_pin_values`. */
    private fun expectedPin(): String {
        val der = CertificateFactory.getInstance("X.509")
            .generateCertificate(
                TcpCustomRootTrustInstrumentedTest.SERVER_CERT_PEM.byteInputStream()
            )
            .encoded
        return pinOf(der)
    }

    private fun pinOf(der: ByteArray): String =
        "sha256-cert/" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(der))

    private companion object {
        const val WRONG_PIN = "sha256-cert/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val H3_HOST = "cloudflare-quic.com"
    }
}
