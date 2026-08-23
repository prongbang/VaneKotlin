package com.inteniquetic.vanekotlin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the RustBuffer wire format of [VaneResponse] — field order included —
 * on the JVM half of the JNA path.
 *
 * No native library is loaded: the generated converters serialize into a plain
 * [ByteBuffer], which is exactly the buffer `lowerIntoRustBuffer` /
 * `liftFromRustBuffer` hand to Rust. If the bindings and the core ever desync
 * on `headers` / `httpVersion` / `remoteIp` (order, presence, encoding), that
 * shows up here instead of as garbage at runtime on a device. ABI v5 is where
 * this matters most: `headers` became an ordered `List<VaneHeader>`, the
 * separate `setCookie` field vanished, and `remoteIp` was appended last — a
 * layout change no UniFFI checksum guards.
 */
class VaneResponseFfiRoundTripTest {
    private fun lower(value: VaneResponse): ByteBuffer {
        val buf = ByteBuffer
            .allocate(FfiConverterTypeVaneResponse.allocationSize(value).toInt())
            .order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneResponse.write(value, buf)
        buf.flip()
        return buf
    }

    private fun roundTrip(value: VaneResponse): VaneResponse {
        val buf = lower(value)
        val read = FfiConverterTypeVaneResponse.read(buf)
        assertFalse("junk remaining in buffer after lifting", buf.hasRemaining())
        return read
    }

    private fun populated(
        headers: List<VaneHeader> = listOf(
            VaneHeader("content-type", "application/json"),
            VaneHeader("set-cookie", "a=1; Path=/"),
            VaneHeader("x-multi", "a"),
            VaneHeader("set-cookie", "b=2; HttpOnly"),
            VaneHeader("x-multi", "b")
        ),
        httpVersion: VaneHttpVersion? = VaneHttpVersion.HTTP3,
        remoteIp: String? = "203.0.113.7"
    ) = VaneResponse(
        statusCode = 207u,
        headers = headers,
        body = byteArrayOf(0x7B, 0x7D, 0x00, -0x01),
        bodyFilePath = "/tmp/vane-download.bin",
        isSuccess = true,
        url = "https://example.com/résumé?q=1",
        httpVersion = httpVersion,
        remoteIp = remoteIp
    )

    @Test
    fun fullyPopulatedResponseSurvivesTheRoundTripFieldByField() {
        val original = populated()

        val decoded = roundTrip(original)

        assertEquals(original.statusCode, decoded.statusCode)
        assertEquals(original.headers, decoded.headers)
        assertArrayEquals(original.body, decoded.body)
        assertEquals(original.bodyFilePath, decoded.bodyFilePath)
        assertEquals(original.isSuccess, decoded.isSuccess)
        assertEquals(original.url, decoded.url)
        assertEquals(original.httpVersion, decoded.httpVersion)
        assertEquals(original.remoteIp, decoded.remoteIp)
    }

    @Test
    fun headersKeepArrivalOrderRepeatsAndEmbeddedCommas() {
        // Duplicates stay separate entries in wire position — set-cookie inline
        // among them, not re-grouped at the tail. The comma is why: an Expires
        // value contains one, so any join would be unsplittable.
        val headers = listOf(
            VaneHeader("set-cookie", "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"),
            VaneHeader("x-multi", "first"),
            VaneHeader("set-cookie", "sid=2; Path=/"),
            VaneHeader("x-multi", "second"),
            VaneHeader("set-cookie", "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"),
            VaneHeader("x-empty", "") // a server can send an empty value; it must not be dropped
        )

        val decoded = roundTrip(populated(headers = headers))

        assertEquals(6, decoded.headers.size)
        assertEquals(headers, decoded.headers)
    }

    @Test
    fun everyHttpVersionRoundTripsAndSoDoesNull() {
        val cases: List<VaneHttpVersion?> = VaneHttpVersion.values().toList() + listOf(null)
        assertEquals(5, cases.size)

        for (version in cases) {
            val decoded = roundTrip(populated(httpVersion = version))
            assertEquals("httpVersion $version did not survive the round trip", version, decoded.httpVersion)
        }
    }

    @Test
    fun httpVersionDiscriminantsMatchTheCoreWireCodes() {
        // Rust's VaneHttpVersion::ffi_code is append-only: 1..4 in this order.
        // uniffi writes ordinal + 1, so the enum declaration order is the pin.
        val expected = mapOf(
            VaneHttpVersion.HTTP10 to 1,
            VaneHttpVersion.HTTP11 to 2,
            VaneHttpVersion.HTTP2 to 3,
            VaneHttpVersion.HTTP3 to 4
        )

        for ((version, code) in expected) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            FfiConverterTypeVaneHttpVersion.write(version, buf)
            buf.flip()

            assertEquals("wire code for $version", code, buf.getInt())
        }
    }

    @Test
    fun defaultsRoundTripAsNull() {
        // Built the way callers built it before the two optional fields existed.
        val original = VaneResponse(
            statusCode = 204u,
            headers = emptyList(),
            body = ByteArray(0),
            bodyFilePath = null,
            isSuccess = true,
            url = "https://example.com/empty"
        )

        assertNull(original.httpVersion)
        assertNull(original.remoteIp)

        val decoded = roundTrip(original)

        assertNull(decoded.httpVersion)
        assertNull(decoded.remoteIp)
        assertEquals(emptyList<VaneHeader>(), decoded.headers)
        assertEquals(0, decoded.body.size)
        assertNull(decoded.bodyFilePath)
        assertEquals(original.url, decoded.url)
    }

    @Test
    fun fieldsAreWrittenInTheOrderTheCoreDeclaresThem() {
        // Decode the buffer by hand with the per-type converters, in the order
        // vane-rs declares VaneResponse. A reordering in either half fails here
        // even when a whole-struct round trip still happens to agree. remoteIp
        // is last — appended in ABI v5, after httpVersion.
        val original = populated(
            headers = listOf(VaneHeader("x-only", "1")),
            httpVersion = VaneHttpVersion.HTTP2,
            remoteIp = "2001:db8::1"
        )
        val buf = lower(original)

        assertEquals(original.statusCode, FfiConverterUShort.read(buf))
        assertEquals(original.headers, FfiConverterSequenceTypeVaneHeader.read(buf))
        assertArrayEquals(original.body, FfiConverterByteArray.read(buf))
        assertEquals(original.bodyFilePath, FfiConverterOptionalString.read(buf))
        assertEquals(original.isSuccess, FfiConverterBoolean.read(buf))
        assertEquals(original.url, FfiConverterString.read(buf))
        assertEquals(VaneHttpVersion.HTTP2, FfiConverterOptionalTypeVaneHttpVersion.read(buf))
        assertEquals("2001:db8::1", FfiConverterOptionalString.read(buf))
        assertFalse("junk remaining after the last field", buf.hasRemaining())
    }

    @Test
    fun headerNameAndValueAreWrittenInDeclarationOrder() {
        // VaneHeader is the new inner record: name then value, both strings.
        val buf = ByteBuffer
            .allocate(FfiConverterTypeVaneHeader.allocationSize(VaneHeader("set-cookie", "wide=é€𝄞")).toInt())
            .order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneHeader.write(VaneHeader("set-cookie", "wide=é€𝄞"), buf)
        buf.flip()

        assertEquals("set-cookie", FfiConverterString.read(buf))
        assertEquals("wide=é€𝄞", FfiConverterString.read(buf))
        assertFalse("junk remaining after value", buf.hasRemaining())
    }

    @Test
    fun allocationSizeCoversWhatWriteEmits() {
        // lowerIntoRustBuffer allocates exactly allocationSize bytes, so an
        // under-count is a BufferOverflowException against a Rust-owned buffer.
        val original = populated(
            headers = listOf(VaneHeader("set-cookie", "wide=é€𝄞"), VaneHeader("x-plain", "1")),
            httpVersion = VaneHttpVersion.HTTP11,
            remoteIp = "2001:db8::1"
        )
        val size = FfiConverterTypeVaneResponse.allocationSize(original).toInt()

        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneResponse.write(original, buf)

        assertTrue("write emitted more than allocationSize promised", buf.position() <= size)
    }
}
