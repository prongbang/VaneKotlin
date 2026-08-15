package com.inteniquetic.vanekotlin

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM-side tests for [streamingBodyFlow] and the session streaming path,
 * against a fake native stream that mirrors the core's contract: `readChunk`
 * holds the stream's lock while it blocks, `closeStream` must wait for that
 * lock, and only the cancel token makes a parked read return. Design risk #3:
 * the token-then-close ordering is asserted here so a wrapper refactor cannot
 * silently break it.
 */
class VaneStreamingTest {
    /**
     * [VaneResponseStreamInterface] stand-in. Serves [chunks] then either
     * EOF (`null`), a thrown [failure], or — with [blockAfterChunks] — parks
     * inside `readChunk` holding [lock] until [interrupt] (the fake's cancel
     * token) releases it, exactly like a read waiting on a silent socket.
     */
    private class FakeStream(
        private val chunks: List<ByteArray> = emptyList(),
        private val failure: VaneException? = null,
        private val blockAfterChunks: Boolean = false,
    ) : VaneResponseStreamInterface {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val readsStarted = AtomicInteger(0)
        val readEntered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val cancelled = CountDownLatch(1)
        private val lock = ReentrantLock()
        private var next = 0

        fun interrupt() = cancelled.countDown()

        override fun head(): VaneResponse = VaneResponse(
            statusCode = 200u,
            headers = emptyMap(),
            body = ByteArray(0),
            bodyFilePath = null,
            isSuccess = true,
            url = "https://example.com/stream",
        )

        override fun readChunk(): ByteArray? = lock.withLock {
            readsStarted.incrementAndGet()
            readEntered.countDown()
            if (next < chunks.size) return chunks[next++]
            failure?.let { throw it }
            if (blockAfterChunks) {
                events.add("readParked")
                check(cancelled.await(5, TimeUnit.SECONDS)) {
                    "parked read was never interrupted — the wrapper lost the cancel token"
                }
                events.add("readReturnedAfterCancel")
                throw VaneException.Cancelled("Request was cancelled")
            }
            null
        }

        override fun closeStream() {
            // The core's close waits for an in-flight read (they serialize on
            // the stream mutex); a close issued before the token would hang
            // here for the full 5 s park and fail the promptness assertion.
            lock.withLock {
                events.add("close")
                closed.set(true)
            }
        }
    }

    @Test
    fun cancellingABlockedCollectionCancelsTheTokenThenClosesPromptly() {
        val fake = FakeStream(blockAfterChunks = true)
        val body = streamingBodyFlow(
            stream = fake,
            cancelToken = {
                fake.events.add("cancelToken")
                fake.interrupt()
            },
            releaseToken = { fake.events.add("releaseToken") },
        )
        runBlocking {
            // Off the runBlocking thread: the blocking latch await below must
            // not starve the collector out of ever running.
            val collector = launch(Dispatchers.Default) { body.collect { } }
            assertTrue(
                "the producer never reached its blocking read",
                fake.readEntered.await(2, TimeUnit.SECONDS),
            )
            val cancelMillis = measureTimeMillis {
                withTimeout(2_000) { collector.cancelAndJoin() }
            }
            assertTrue(
                "cancel took ${cancelMillis}ms — the token did not interrupt the parked read",
                cancelMillis < 1_500,
            )
        }
        // cancelAndJoin waits for the producer (a child of the collection) too,
        // so the teardown order is settled: token first, close strictly after
        // the read returned, release last.
        assertEquals(
            listOf(
                "readParked",
                "cancelToken",
                "readReturnedAfterCancel",
                "close",
                "releaseToken",
            ),
            fake.events.toList(),
        )
    }

    @Test
    fun aSlowCollectorNeverLetsTheProducerRunAhead() = runBlocking {
        val fake = FakeStream(chunks = List(5) { i -> byteArrayOf(i.toByte()) })
        val body = streamingBodyFlow(fake, cancelToken = {}, releaseToken = {})
        var consumed = 0
        body.collect { chunk ->
            consumed += 1
            assertEquals(consumed - 1, chunk[0].toInt())
            // Give an eager producer every chance to run ahead before looking.
            delay(50)
            // Rendezvous hand-off: at most the one pull already in flight.
            val started = fake.readsStarted.get()
            assertTrue(
                "producer started $started reads with only $consumed consumed — backpressure lost",
                started <= consumed + 1,
            )
        }
        assertEquals(5, consumed)
        assertTrue("EOF must still close the stream", fake.closed.get())
        assertTrue("nothing cancels on a clean EOF", fake.events.none { it == "cancelToken" })
    }

    @Test
    fun aMidStreamFailureSurfacesOnTheFlowAndStillCloses() = runBlocking {
        val fake = FakeStream(
            chunks = listOf(byteArrayOf(1)),
            failure = VaneException.Transport("connection lost mid-body"),
        )
        val body = streamingBodyFlow(fake, cancelToken = {}, releaseToken = {})
        var received = 0
        val thrown = runCatching { body.collect { received += 1 } }.exceptionOrNull()
        assertTrue(
            "expected the transport failure, got $thrown",
            thrown is VaneException.Transport,
        )
        assertEquals(1, received)
        assertTrue("a failed stream must still be closed", fake.closed.get())
    }

    @Test
    fun theBodyCanBeCollectedOnlyOnce() = runBlocking {
        val fake = FakeStream()
        val body = streamingBodyFlow(fake, cancelToken = {}, releaseToken = {})
        body.collect { }
        try {
            body.collect { }
            fail("a second collection must be refused")
        } catch (expected: IllegalStateException) {
            // The refused collection must not have touched the native stream
            // again: one EOF pull total.
            assertEquals(1, fake.readsStarted.get())
        }
    }

    @Test
    fun sessionStreamingRunsRequestInterceptorsAndSkipsResponseInterceptors() = runBlocking {
        var captured: VaneRequest? = null
        var responseInterceptorRan = false
        val session = VaneSession(
            configuration = testConfig(),
            requestInterceptors = listOf({ request ->
                request.copy(headers = request.headers + ("x-streamed" to "1"))
            }),
            responseInterceptors = listOf({ response ->
                responseInterceptorRan = true
                response
            }),
            errorInterceptors = emptyList(),
            transportExecutor = {
                throw AssertionError("a streaming request must not use the buffered transport")
            },
            streamingTransportExecutor = { request ->
                captured = request
                VaneStreamingResponse(
                    head = VaneResponse(
                        statusCode = 200u,
                        headers = emptyMap(),
                        body = ByteArray(0),
                        bodyFilePath = null,
                        isSuccess = true,
                        url = request.url,
                    ),
                    body = flowOf(byteArrayOf(1, 2, 3)),
                )
            },
        )

        val streaming = session.executeStreaming(
            VaneRequest(
                url = "https://example.com/stream",
                method = "GET",
                headers = emptyMap(),
                queryParams = emptyMap(),
                body = null,
                bodyFilePath = null,
                responseBodyPath = null,
                cancelTokenId = null,
                progressId = null,
                timeoutSeconds = null,
                followRedirects = true,
            ),
        )

        assertEquals("1", captured?.headers?.get("x-streamed"))
        assertEquals("head body is empty by contract", 0, streaming.head.body.size)
        var bytes = 0
        streaming.body.collect { bytes += it.size }
        assertEquals(3, bytes)
        assertFalse(
            "response interceptors must not run on a stream",
            responseInterceptorRan,
        )
    }

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
        proxyAuthorization = null,
    )
}
