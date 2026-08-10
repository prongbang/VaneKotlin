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
 * on `setCookie` / `httpVersion` (order, presence, encoding), that shows up
 * here instead of as garbage at runtime on a device.
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
        setCookie: List<String> = listOf("a=1; Path=/", "b=2; HttpOnly"),
        httpVersion: VaneHttpVersion? = VaneHttpVersion.HTTP3
    ) = VaneResponse(
        statusCode = 207u,
        headers = mapOf("content-type" to "application/json", "x-multi" to "a, b"),
        body = byteArrayOf(0x7B, 0x7D, 0x00, -0x01),
        bodyFilePath = "/tmp/vane-download.bin",
        isSuccess = true,
        url = "https://example.com/résumé?q=1",
        setCookie = setCookie,
        httpVersion = httpVersion
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
        assertEquals(original.setCookie, decoded.setCookie)
        assertEquals(original.httpVersion, decoded.httpVersion)
    }

    @Test
    fun setCookieKeepsWireOrderRepeatsAndEmbeddedCommas() {
        // The comma is why Set-Cookie is a list and not a comma-joined header:
        // an Expires value contains one, so the join would be unsplittable.
        val cookies = listOf(
            "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
            "sid=2; Path=/",
            "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
            "" // a server can send an empty value; it must not be dropped
        )

        val decoded = roundTrip(populated(setCookie = cookies))

        assertEquals(4, decoded.setCookie.size)
        assertEquals(cookies, decoded.setCookie)
        assertEquals(cookies[0], decoded.setCookie[0])
        assertEquals(cookies[1], decoded.setCookie[1])
        assertEquals(cookies[2], decoded.setCookie[2])
        assertEquals(cookies[3], decoded.setCookie[3])
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
    fun defaultsRoundTripAsEmptyListAndNull() {
        // Built the way callers built it before the two fields existed.
        val original = VaneResponse(
            statusCode = 204u,
            headers = emptyMap(),
            body = ByteArray(0),
            bodyFilePath = null,
            isSuccess = true,
            url = "https://example.com/empty"
        )

        assertEquals(emptyList<String>(), original.setCookie)
        assertNull(original.httpVersion)

        val decoded = roundTrip(original)

        assertEquals(emptyList<String>(), decoded.setCookie)
        assertNull(decoded.httpVersion)
        assertEquals(0, decoded.body.size)
        assertNull(decoded.bodyFilePath)
        assertEquals(original.url, decoded.url)
    }

    @Test
    fun fieldsAreWrittenInTheOrderTheCoreDeclaresThem() {
        // Decode the buffer by hand with the per-type converters, in the order
        // vane-rs declares VaneResponse. A reordering in either half fails here
        // even when a whole-struct round trip still happens to agree.
        val original = populated(
            setCookie = listOf("only=1"),
            httpVersion = VaneHttpVersion.HTTP2
        )
        val buf = lower(original)

        assertEquals(original.statusCode, FfiConverterUShort.read(buf))
        assertEquals(original.headers, FfiConverterMapStringString.read(buf))
        assertArrayEquals(original.body, FfiConverterByteArray.read(buf))
        assertEquals(original.bodyFilePath, FfiConverterOptionalString.read(buf))
        assertEquals(original.isSuccess, FfiConverterBoolean.read(buf))
        assertEquals(original.url, FfiConverterString.read(buf))
        assertEquals(listOf("only=1"), FfiConverterSequenceString.read(buf))
        assertEquals(VaneHttpVersion.HTTP2, FfiConverterOptionalTypeVaneHttpVersion.read(buf))
        assertFalse("junk remaining after the last field", buf.hasRemaining())
    }

    @Test
    fun allocationSizeCoversWhatWriteEmits() {
        // lowerIntoRustBuffer allocates exactly allocationSize bytes, so an
        // under-count is a BufferOverflowException against a Rust-owned buffer.
        val original = populated(
            setCookie = listOf("wide=é€𝄞", "plain=1"),
            httpVersion = VaneHttpVersion.HTTP11
        )
        val size = FfiConverterTypeVaneResponse.allocationSize(original).toInt()

        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        FfiConverterTypeVaneResponse.write(original, buf)

        assertTrue("write emitted more than allocationSize promised", buf.position() <= size)
    }
}
