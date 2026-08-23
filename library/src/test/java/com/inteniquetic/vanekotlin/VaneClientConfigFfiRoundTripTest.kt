package com.inteniquetic.vanekotlin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the RustBuffer wire format of [VaneClientConfig] — field order included
 * — on the JVM half of the JNA path, the config twin of
 * [VaneResponseFfiRoundTripTest].
 *
 * No native library is loaded: the generated converters serialize into a plain
 * [ByteBuffer], which is exactly the buffer `lowerIntoRustBuffer` /
 * `liftFromRustBuffer` hand to Rust. ABI v5 appended six knobs after
 * `proxyAuthorization` (`maxRedirects`, both TLS versions,
 * `customRootCertificates`, `clientCertificate`); adding record fields moves
 * no UniFFI checksum, so this test is the guard that the bindings and the
 * core agree on the new tail instead of desyncing silently on a device.
 */
class VaneClientConfigFfiRoundTripTest {
    private fun lower(value: VaneClientConfig): ByteBuffer {
        val buf = ByteBuffer
            .allocate(FfiConverterTypeVaneClientConfig.allocationSize(value).toInt())
            .order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneClientConfig.write(value, buf)
        buf.flip()
        return buf
    }

    private fun roundTrip(value: VaneClientConfig): VaneClientConfig {
        val buf = lower(value)
        val read = FfiConverterTypeVaneClientConfig.read(buf)
        assertFalse("junk remaining in buffer after lifting", buf.hasRemaining())
        return read
    }

    private fun populated() = VaneClientConfig(
        baseUrl = "https://api.example.com",
        defaultHeaders = mapOf("x-app" to "vane", "accept" to "application/json"),
        dnsOverrides = mapOf("internal.example" to "192.0.2.10"),
        certificatePins = mapOf("pinned.example" to listOf("sha256/AAAA", "sha256/BBBB")),
        cookiesEnabled = true,
        cookiePersistencePath = "/data/cookies.json",
        connectionPoolEnabled = true,
        maxIdleConnections = 4u,
        connectionIdleTimeoutSeconds = 25u,
        retryMaxAttempts = 3u,
        retryInitialDelayMillis = 100u,
        retryMaxDelayMillis = 1_000u,
        retryUnsafeMethods = false,
        maxRequestBodyBytes = 1_048_576u,
        maxResponseBodyBytes = 10_485_760u,
        timeoutSeconds = 30u,
        followRedirects = true,
        userAgent = "Vane/0.1.0",
        protocolMode = VaneProtocolMode.HTTP2_THEN_HTTP1,
        proxyUrl = "https://proxy.example:8443",
        proxyAuthorization = "Basic dXNlcjpwYXNz",
        maxRedirects = 5u,
        tlsMinVersion = VaneTlsVersion.TLS12,
        tlsMaxVersion = VaneTlsVersion.TLS12,
        customRootCertificates = listOf(
            "-----BEGIN CERTIFICATE-----\nQUFB\n-----END CERTIFICATE-----\n",
            "-----BEGIN CERTIFICATE-----\nQkJC\n-----END CERTIFICATE-----\n"
        ),
        clientCertificate = VaneClientCertificate(
            certificatePem = "-----BEGIN CERTIFICATE-----\nQ0ND\n-----END CERTIFICATE-----\n",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nS0VZ\n-----END PRIVATE KEY-----\n"
        )
    )

    @Test
    fun fullyPopulatedConfigSurvivesTheRoundTripFieldByField() {
        val original = populated()

        val decoded = roundTrip(original)

        assertEquals(original.baseUrl, decoded.baseUrl)
        assertEquals(original.defaultHeaders, decoded.defaultHeaders)
        assertEquals(original.dnsOverrides, decoded.dnsOverrides)
        assertEquals(original.certificatePins, decoded.certificatePins)
        assertEquals(original.cookiesEnabled, decoded.cookiesEnabled)
        assertEquals(original.cookiePersistencePath, decoded.cookiePersistencePath)
        assertEquals(original.connectionPoolEnabled, decoded.connectionPoolEnabled)
        assertEquals(original.maxIdleConnections, decoded.maxIdleConnections)
        assertEquals(original.connectionIdleTimeoutSeconds, decoded.connectionIdleTimeoutSeconds)
        assertEquals(original.retryMaxAttempts, decoded.retryMaxAttempts)
        assertEquals(original.retryInitialDelayMillis, decoded.retryInitialDelayMillis)
        assertEquals(original.retryMaxDelayMillis, decoded.retryMaxDelayMillis)
        assertEquals(original.retryUnsafeMethods, decoded.retryUnsafeMethods)
        assertEquals(original.maxRequestBodyBytes, decoded.maxRequestBodyBytes)
        assertEquals(original.maxResponseBodyBytes, decoded.maxResponseBodyBytes)
        assertEquals(original.timeoutSeconds, decoded.timeoutSeconds)
        assertEquals(original.followRedirects, decoded.followRedirects)
        assertEquals(original.userAgent, decoded.userAgent)
        assertEquals(original.protocolMode, decoded.protocolMode)
        assertEquals(original.proxyUrl, decoded.proxyUrl)
        assertEquals(original.proxyAuthorization, decoded.proxyAuthorization)
        assertEquals(original.maxRedirects, decoded.maxRedirects)
        assertEquals(original.tlsMinVersion, decoded.tlsMinVersion)
        assertEquals(original.tlsMaxVersion, decoded.tlsMaxVersion)
        assertEquals(original.customRootCertificates, decoded.customRootCertificates)
        assertEquals(original.clientCertificate, decoded.clientCertificate)
    }

    @Test
    fun fieldsAreWrittenInTheOrderTheCoreDeclaresThem() {
        // Decode the buffer by hand with the per-type converters, in the order
        // vane-rs declares VaneClientConfig — the six v5 knobs strictly after
        // proxyAuthorization. A reordering in either half fails here even when
        // a whole-struct round trip still happens to agree.
        val original = populated()
        val buf = lower(original)

        assertEquals(original.baseUrl, FfiConverterOptionalString.read(buf))
        assertEquals(original.defaultHeaders, FfiConverterMapStringString.read(buf))
        assertEquals(original.dnsOverrides, FfiConverterMapStringString.read(buf))
        assertEquals(original.certificatePins, FfiConverterMapStringSequenceString.read(buf))
        assertEquals(original.cookiesEnabled, FfiConverterBoolean.read(buf))
        assertEquals(original.cookiePersistencePath, FfiConverterOptionalString.read(buf))
        assertEquals(original.connectionPoolEnabled, FfiConverterBoolean.read(buf))
        assertEquals(original.maxIdleConnections, FfiConverterULong.read(buf))
        assertEquals(original.connectionIdleTimeoutSeconds, FfiConverterULong.read(buf))
        assertEquals(original.retryMaxAttempts, FfiConverterULong.read(buf))
        assertEquals(original.retryInitialDelayMillis, FfiConverterULong.read(buf))
        assertEquals(original.retryMaxDelayMillis, FfiConverterULong.read(buf))
        assertEquals(original.retryUnsafeMethods, FfiConverterBoolean.read(buf))
        assertEquals(original.maxRequestBodyBytes, FfiConverterULong.read(buf))
        assertEquals(original.maxResponseBodyBytes, FfiConverterULong.read(buf))
        assertEquals(original.timeoutSeconds, FfiConverterOptionalULong.read(buf))
        assertEquals(original.followRedirects, FfiConverterBoolean.read(buf))
        assertEquals(original.userAgent, FfiConverterOptionalString.read(buf))
        assertEquals(original.protocolMode, FfiConverterTypeVaneProtocolMode.read(buf))
        assertEquals(original.proxyUrl, FfiConverterOptionalString.read(buf))
        assertEquals(original.proxyAuthorization, FfiConverterOptionalString.read(buf))
        assertEquals(original.maxRedirects, FfiConverterUInt.read(buf))
        assertEquals(original.tlsMinVersion, FfiConverterOptionalTypeVaneTlsVersion.read(buf))
        assertEquals(original.tlsMaxVersion, FfiConverterOptionalTypeVaneTlsVersion.read(buf))
        assertEquals(original.customRootCertificates, FfiConverterSequenceString.read(buf))
        assertEquals(original.clientCertificate, FfiConverterOptionalTypeVaneClientCertificate.read(buf))
        assertFalse("junk remaining after the last field", buf.hasRemaining())
    }

    @Test
    fun v5KnobDefaultsMatchTheRustDefaultsTableAndRoundTrip() {
        // Built the way the pre-v5 JVM config literals build it — no v5 args.
        // The UniFFI defaults must spell the same values as Rust's
        // `impl Default for VaneClientConfig`: 10, None, None, [], None.
        val original = VaneClientConfig(
            baseUrl = null,
            defaultHeaders = emptyMap(),
            dnsOverrides = emptyMap(),
            certificatePins = emptyMap(),
            cookiesEnabled = false,
            cookiePersistencePath = null,
            connectionPoolEnabled = true,
            maxIdleConnections = 4u,
            connectionIdleTimeoutSeconds = 25u,
            retryMaxAttempts = 1u,
            retryInitialDelayMillis = 100u,
            retryMaxDelayMillis = 1_000u,
            retryUnsafeMethods = false,
            maxRequestBodyBytes = 1_048_576u,
            maxResponseBodyBytes = 10_485_760u,
            timeoutSeconds = 30u,
            followRedirects = true,
            userAgent = null,
            protocolMode = VaneProtocolMode.HTTP3_ONLY,
            proxyUrl = null,
            proxyAuthorization = null
        )

        assertEquals(10u, original.maxRedirects)
        assertNull(original.tlsMinVersion)
        assertNull(original.tlsMaxVersion)
        assertEquals(emptyList<String>(), original.customRootCertificates)
        assertNull(original.clientCertificate)

        val decoded = roundTrip(original)

        assertEquals(10u, decoded.maxRedirects)
        assertNull(decoded.tlsMinVersion)
        assertNull(decoded.tlsMaxVersion)
        assertEquals(emptyList<String>(), decoded.customRootCertificates)
        assertNull(decoded.clientCertificate)
    }

    @Test
    fun tlsVersionDiscriminantsMatchTheCoreWireCodes() {
        // Rust declares Tls12, Tls13 in this order; uniffi writes ordinal + 1,
        // so the enum declaration order is the pin on both sides.
        val expected = mapOf(
            VaneTlsVersion.TLS12 to 1,
            VaneTlsVersion.TLS13 to 2
        )

        for ((version, code) in expected) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            FfiConverterTypeVaneTlsVersion.write(version, buf)
            buf.flip()

            assertEquals("wire code for $version", code, buf.getInt())
        }
    }

    @Test
    fun clientCertificateWritesCertificateThenKey() {
        // The nested record's own field order: certificatePem first, then
        // privateKeyPem — swapping them would hand the core a key as a chain.
        val cert = VaneClientCertificate(
            certificatePem = "-----BEGIN CERTIFICATE-----\nQ0ND\n-----END CERTIFICATE-----\n",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nS0VZ\n-----END PRIVATE KEY-----\n"
        )
        val buf = ByteBuffer
            .allocate(FfiConverterTypeVaneClientCertificate.allocationSize(cert).toInt())
            .order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneClientCertificate.write(cert, buf)
        buf.flip()

        assertEquals(cert.certificatePem, FfiConverterString.read(buf))
        assertEquals(cert.privateKeyPem, FfiConverterString.read(buf))
        assertFalse("junk remaining after the key", buf.hasRemaining())
    }

    @Test
    fun allocationSizeCoversWhatWriteEmits() {
        // lowerIntoRustBuffer allocates exactly allocationSize bytes, so an
        // under-count is a BufferOverflowException against a Rust-owned buffer.
        val original = populated().apply {
            customRootCertificates = listOf("wide=é€𝄞", "plain")
        }
        val size = FfiConverterTypeVaneClientConfig.allocationSize(original).toInt()

        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneClientConfig.write(original, buf)

        assertTrue("write emitted more than allocationSize promised", buf.position() <= size)
    }
}
