package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device-real tripwire for `customRootCertificates` on Android — the
 * platform that forced the core's OR-composite verifier into existence:
 * Android's platform verifier has no extra-roots API, so custom roots there
 * ride a vane-side composite (platform trust OR the caller's roots) instead
 * of any per-OS hook. Rust's hermetic tests prove the composite against a
 * desktop root store; only this test proves it against the real Android
 * trust store over JNI, on a device, through the packaged `libvane.so`.
 *
 * Three sides of the same coin, deliberately paired the way
 * [TcpTrustStoreInstrumentedTest] pairs accept and reject: the knob admits a
 * chain from the custom CA (extend), platform trust alone still refuses that
 * chain (the knob — not some accidental trust — did the admitting), and a
 * stranger CA's chain stays refused with the knob configured (the composite
 * is a union, not "anything goes").
 *
 * `http1Only` keeps HTTP/3 out of the equation, so every verdict here comes
 * from a real rustls handshake through the composite. The server is an
 * in-process [SSLServerSocket] on 127.0.0.1 presenting a fixed test chain;
 * the fixtures are throwaway EC keys generated for this file — the "CA"
 * exists nowhere but these constants.
 */
@RunWith(AndroidJUnit4::class)
class TcpCustomRootTrustInstrumentedTest {

    @Test
    fun customCaTrustedViaTheKnobValidatesALocalServer() = runBlocking {
        LocalTlsServer(SERVER_CERT_PEM, SERVER_KEY_PEM).use { server ->
            val client = createVaneClient(
                VaneConfigurationBuilder()
                    .http1Only()
                    .timeout(30u)
                    .customRootCertificates(listOf(CUSTOM_CA_PEM))
                    .build()
            )
            val response = client.getRequest("https://127.0.0.1:${server.port}/")
            assertEquals(200, response.statusCode.toInt())
            assertTrue(response.isSuccess)
        }
    }

    /**
     * The control: the same server, no knob. If this ever starts passing,
     * the accept case above proves nothing — some path other than the
     * composite's custom arm has begun trusting the test CA.
     */
    @Test
    fun theSameServerIsRejectedWithoutTheKnob() = runBlocking {
        LocalTlsServer(SERVER_CERT_PEM, SERVER_KEY_PEM).use { server ->
            val client = createVaneClient(
                VaneConfigurationBuilder().http1Only().timeout(30u).build()
            )
            val message = runCatching {
                client.getRequest("https://127.0.0.1:${server.port}/")
            }.exceptionOrNull()?.message
            assertTrue(
                "Expected an untrusted-certificate failure, got: " +
                    (message ?: "a successful response"),
                message?.contains("UnknownIssuer") == true ||
                    message?.contains("invalid peer certificate") == true
            )
        }
    }

    /**
     * The composite must stay a union: with the custom CA configured, a
     * chain from an unrelated CA is still refused. A half-verifying arm —
     * the c3 risk in the design — would pass the accept case and fail here.
     */
    @Test
    fun aStrangerCaStillFailsWithTheKnobConfigured() = runBlocking {
        LocalTlsServer(STRANGER_SERVER_CERT_PEM, STRANGER_SERVER_KEY_PEM).use { server ->
            val client = createVaneClient(
                VaneConfigurationBuilder()
                    .http1Only()
                    .timeout(30u)
                    .customRootCertificates(listOf(CUSTOM_CA_PEM))
                    .build()
            )
            val message = runCatching {
                client.getRequest("https://127.0.0.1:${server.port}/")
            }.exceptionOrNull()?.message
            assertTrue(
                "Expected an untrusted-certificate failure, got: " +
                    (message ?: "a successful response"),
                message?.contains("UnknownIssuer") == true ||
                    message?.contains("invalid peer certificate") == true
            )
        }
    }

    /**
     * A minimal in-process HTTPS server: TLS from the given PEM identity,
     * then a fixed `200 ok` to whatever HTTP/1.1 request arrives. Failed
     * handshakes — the point of two of the three tests — surface client-side;
     * the accept loop just moves on to the next connection.
     */
    internal class LocalTlsServer(certPem: String, keyPem: String) : Closeable {
        private val socket: SSLServerSocket
        private val thread: Thread
        val port: Int get() = socket.localPort

        init {
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(certPem.byteInputStream())
                .toTypedArray()
            val keyDer = Base64.getDecoder().decode(
                keyPem.lineSequence()
                    .filterNot { it.startsWith("-----") || it.isBlank() }
                    .joinToString("")
            )
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(keyDer))
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", key, CharArray(0), certificates)
            }
            val keyManagers = KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, CharArray(0)) }
                .keyManagers
            val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
            socket = tls.serverSocketFactory.createServerSocket(
                0, 8, InetAddress.getByName("127.0.0.1")
            ) as SSLServerSocket
            thread = Thread {
                while (true) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: return@Thread
                    runCatching {
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        connection.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: 2\r\n" +
                                    "Connection: close\r\n\r\nok").toByteArray()
                            )
                            flush()
                        }
                    }
                    runCatching { connection.close() }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            runCatching { socket.close() }
            thread.join(2_000)
        }
    }

    internal companion object {
        /** Self-signed test CA — the identity the knob is asked to trust. */
        const val CUSTOM_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIBpjCCAUugAwIBAgIUUdDVaXDNp7LPLL2oIKLhFClJuEEwCgYIKoZIzj0EAwIw
JzElMCMGA1UEAwwcVmFuZSBLb3RsaW4gQmF0Y2gzIEN1c3RvbSBDQTAgFw0yNjA4
MjMxNjEyMDZaGA8yMTI2MDczMDE2MTIwNlowJzElMCMGA1UEAwwcVmFuZSBLb3Rs
aW4gQmF0Y2gzIEN1c3RvbSBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABK7p
xqeXxJMKl823hsZEPk1pb7q3OPUoQj7t/gPX3ULQivr1+cCMULjKw29bkRhVMBdu
0FmIZcgjPhbV0iwNAPijUzBRMB0GA1UdDgQWBBR6aT2DR6zpOF0gxXanwUvuS4ge
2zAfBgNVHSMEGDAWgBR6aT2DR6zpOF0gxXanwUvuS4ge2zAPBgNVHRMBAf8EBTAD
AQH/MAoGCCqGSM49BAMCA0kAMEYCIQDsy3uJ7JBjzvABBDVQlYZiqwl/akEdSn14
AURTKOS7igIhALHIYYISzP1gljOimfDRu2zKf4onmqAoh9/3WyZfmD+0
-----END CERTIFICATE-----"""

        /** Leaf for 127.0.0.1 (IP SAN), signed by [CUSTOM_CA_PEM]. */
        const val SERVER_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBkzCCATigAwIBAgIUcL8dSebbq6aE0GiMMrRNm+edzoswCgYIKoZIzj0EAwIw
JzElMCMGA1UEAwwcVmFuZSBLb3RsaW4gQmF0Y2gzIEN1c3RvbSBDQTAgFw0yNjA4
MjMxNjEyMDZaGA8yMTI2MDczMDE2MTIwNlowFDESMBAGA1UEAwwJMTI3LjAuMC4x
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3nao67YzwneEBxV8qIckfS+lA6ce
CWnohBJv2gm7pHHt+N3uw5AqIxaEYRHziD44qJllrY1F8TdhHWqsVYAbgqNTMFEw
DwYDVR0RBAgwBocEfwAAATAdBgNVHQ4EFgQUW03ozT/eJXRCx21p3jsyNV1WpXww
HwYDVR0jBBgwFoAUemk9g0es6ThdIMV2p8FL7kuIHtswCgYIKoZIzj0EAwIDSQAw
RgIhAL9TEguDU3SN74TtbFtZVIhUpwbYEISCrvq2c91MZsbvAiEAo6chRrHhn4Il
lMhsxXZsMilAoKMpitDkbgG0a/WSf2Y=
-----END CERTIFICATE-----"""

        /** PKCS#8 EC key for [SERVER_CERT_PEM]; a test fixture, not a secret. */
        const val SERVER_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgzUzm7mIgiLNmhG57
TQ3RxW/QFzagZtoV8SpNk/mXoJyhRANCAATedqjrtjPCd4QHFXyohyR9L6UDpx4J
aeiEEm/aCbukce343e7DkCojFoRhEfOIPjiomWWtjUXxN2EdaqxVgBuC
-----END PRIVATE KEY-----"""

        /**
         * Leaf for 127.0.0.1 signed by a DIFFERENT self-signed CA that is
         * configured nowhere — the stranger the union must keep refusing.
         */
        const val STRANGER_SERVER_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBlDCCATqgAwIBAgIUM1CLE9zi0oKnuTwVWvP4hX8cEhwwCgYIKoZIzj0EAwIw
KTEnMCUGA1UEAwweVmFuZSBLb3RsaW4gQmF0Y2gzIFN0cmFuZ2VyIENBMCAXDTI2
MDgyMzE2MTIwNloYDzIxMjYwNzMwMTYxMjA2WjAUMRIwEAYDVQQDDAkxMjcuMC4w
LjEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASCiIonu3FXXjVqMxDQF1mQ6BJj
I8UCe9MgOQAzuuentMOb74KCl0VGnyHwgBJVshO5szSXwiXdquKLveLOZBOGo1Mw
UTAPBgNVHREECDAGhwR/AAABMB0GA1UdDgQWBBT5YWbpQ6pQY+Z8Sg69+gUbFkzs
wzAfBgNVHSMEGDAWgBSzMxGZuXAZW0AsAhWdE/eSttEMGTAKBggqhkjOPQQDAgNI
ADBFAiBHbzh9Mn5SMg6bCRFRNWnBEDApO6bOpYpH7Rnwbs/lHwIhAIa0/EO/6gqT
gUD5DRjTprU4E54mIQ+ZMZP6HPoA9oZh
-----END CERTIFICATE-----"""

        /** PKCS#8 EC key for [STRANGER_SERVER_CERT_PEM]; also just a fixture. */
        const val STRANGER_SERVER_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg5NzunzJ2ASBMdAy7
FvW2to9YO7cnl0MpT1AWDYEc7pqhRANCAASCiIonu3FXXjVqMxDQF1mQ6BJjI8UC
e9MgOQAzuuentMOb74KCl0VGnyHwgBJVshO5szSXwiXdquKLveLOZBOG
-----END PRIVATE KEY-----"""
    }
}
