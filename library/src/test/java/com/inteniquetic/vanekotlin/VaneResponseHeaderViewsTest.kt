package com.inteniquetic.vanekotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the derived views over the ordered header list: [headerMap] is
 * first-wins (the core's redirect rule for `location`, RFC 9110 §10.2.2 —
 * NOT rhttp's incidental last-wins) and [setCookie] is every `set-cookie`
 * value in arrival order. Pure model tests; no native library.
 */
class VaneResponseHeaderViewsTest {
    private fun response(headers: List<VaneHeader>) = VaneResponse(
        statusCode = 200u,
        headers = headers,
        body = ByteArray(0),
        bodyFilePath = null,
        isSuccess = true,
        url = "https://example.com/"
    )

    @Test
    fun headerMapIsFirstWinsAndKeepsFirstArrivalOrder() {
        val response = response(
            listOf(
                VaneHeader("x-multi", "first"),
                VaneHeader("content-type", "application/json"),
                VaneHeader("x-multi", "second"),
                VaneHeader("set-cookie", "a=1")
            )
        )

        assertEquals("first", response.headerMap["x-multi"])
        assertEquals("application/json", response.headerMap["content-type"])
        assertEquals(
            listOf("x-multi", "content-type", "set-cookie"),
            response.headerMap.keys.toList()
        )
        assertNull(response.headerMap["x-absent"])
    }

    @Test
    fun setCookieKeepsEveryValueInArrivalOrder() {
        val response = response(
            listOf(
                VaneHeader("set-cookie", "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"),
                VaneHeader("content-type", "text/plain"),
                VaneHeader("set-cookie", "sid=2; Path=/"),
                VaneHeader("set-cookie", "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
            )
        )

        assertEquals(
            listOf(
                "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
                "sid=2; Path=/",
                "sid=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
            ),
            response.setCookie
        )
        assertEquals(emptyList<String>(), response(emptyList()).setCookie)
    }
}
